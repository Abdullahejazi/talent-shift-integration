package com.talentshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

record AgentSearchSummary(boolean configured, boolean completed, int webSearches, int discovered, int inserted, int updated,
        int rejected, String message) {}

@Service
class TavilyJobDiscoveryService {
    private static final List<String> LOCATIONS = List.of("Saudi Arabia", "Riyadh", "Jeddah", "Dammam", "Khobar",
            "Dhahran", "NEOM", "Tabuk", "Makkah", "Madinah", "Jubail", "Yanbu", "AlUla", "Remote MENA");
    private static final List<String> INDUSTRIES = List.of(
            "software engineering", "cybersecurity", "data and AI", "cloud infrastructure", "finance and banking",
            "sales and marketing", "healthcare", "construction", "mechanical and electrical engineering",
            "operations and logistics", "human resources", "graduate internships", "hospitality and tourism",
            "aviation", "consulting", "education", "renewable energy", "legal and compliance",
            "fintech", "e-commerce", "cloud computing", "finance", "retail", "logistics", "telecom",
            "manufacturing", "oil and gas", "biotech", "pharmaceuticals", "gaming");
    private static final List<String> ATS_HOSTS = List.of("jobs.lever.co", "job-boards.greenhouse.io",
            "jobs.ashbyhq.com", "jobs.smartrecruiters.com", "myworkdayjobs.com");
    private static final List<String> SAUDI_MARKERS = List.of("saudi", "riyadh", "jeddah", "dammam", "khobar",
            "dhahran", "neom", "tabuk", "makkah", "mecca", "madinah", "medina", "jubail", "yanbu");

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final AgentJobIngestionService ingestion;
    private final JdbcClient jdbc;
    private final String apiKey;
    private final String baseUrl;
    private final int maximumJobs;
    private final int newJobSearchCredits;
    private final int seedSearchCredits;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean enabled;

    TavilyJobDiscoveryService(RestClient restClient, ObjectMapper mapper, AgentJobIngestionService ingestion, JdbcClient jdbc,
            @Value("${app.jobs.tavily-api-key:}") String apiKey,
            @Value("${app.jobs.tavily-base-url:https://api.tavily.com}") String baseUrl,
            @Value("${app.jobs.tavily-maximum-jobs:100}") int maximumJobs,
            @Value("${app.jobs.tavily-new-job-search-credits:200}") int newJobSearchCredits,
            @Value("${app.jobs.tavily-seed-search-credits:200}") int seedSearchCredits,
            @Value("${app.jobs.credential-collectors-enabled:false}") boolean enabled) {
        this.restClient = restClient;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.mapper = mapper;
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.apiKey = apiKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.maximumJobs = Integer.MAX_VALUE;
        this.newJobSearchCredits = Integer.MAX_VALUE;
        this.seedSearchCredits = Integer.MAX_VALUE;
        this.enabled = enabled;
    }

    AgentSearchSummary searchNow() {
        if (!enabled) return notConfigured();
        return search(false);
    }

    AgentSearchSummary searchSeedJobsNow() {
        if (!enabled || apiKey.isBlank()) return notConfigured();
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("The AI job search is already running");
        try { return runSeedJobSearch(); }
        catch (TavilyProviderException exception) { return providerFailure(exception); }
        finally { running.set(false); }
    }

    private AgentSearchSummary search(boolean sourcesOnly) {
        if (apiKey.isBlank()) return notConfigured();
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("The AI job search is already running");
        try {
            return runSearch(sourcesOnly);
        } catch (TavilyProviderException exception) { return providerFailure(exception); }
        finally { running.set(false); }
    }

    private AgentSearchSummary runSearch(boolean sourcesOnly) {
        Map<String,AgentJobSubmission> found = new LinkedHashMap<>();
        int searches = 0;
        int activatedSeeds = 0;
        int skipped = 0;
        for (SearchQuery search : discoveryQueries()) {
            if (searchWasUsed(search.query())) { skipped++; continue; }
            JsonNode request = mapper.createObjectNode()
                    .put("api_key", apiKey).put("query", search.query()).put("topic", "general")
                    .put("search_depth", "basic").put("max_results", 20)
                    .put("include_answer", false).put("include_raw_content", false);
            String body = tavilySearch(request);
            searches++;
            int resultCount=0, acceptedInQuery=0, seedsInQuery=0;
            for (JsonNode result : read(body).path("results")) {
                resultCount++;
                if (targetWasSeen(text(result, "url"), search.kind())) continue;
                recordDiscoveryTarget(result, search.kind(), "DISCOVERED");
                if (activateVerifiedPublicBoard(result)) {
                    activatedSeeds++;
                    seedsInQuery++;
                    recordDiscoveryTarget(result, "ATS_BOARD", "PROMOTED");
                    continue;
                }
                AgentJobSubmission job = verifyJobPage(result);
                if (job != null && found.putIfAbsent(AuthService.hash(job.applyUrl()),job)==null) acceptedInQuery++;
            }
            recordSearch(search,resultCount,acceptedInQuery,seedsInQuery);
        }
        AgentIngestionSummary result = found.isEmpty() ? new AgentIngestionSummary(0, 0, 0) : ingestion.ingest(List.copyOf(found.values()));
        return new AgentSearchSummary(true, true, searches, found.size(), result.inserted(), result.updated(), result.rejected(),
                "AI discovery completed " + searches + " searches with no internal credit limit, promoted " + activatedSeeds
                        + " verified seeds, skipped " + skipped + " previously searched targets, and accepted only direct JobPosting pages");
    }

    private AgentSearchSummary runSeedJobSearch() {
        List<SeedSource> seeds=new ArrayList<>(jdbc.sql("""
                SELECT id,organization_name,coalesce(nullif(official_domain,''),source_url)
                FROM seed_candidates WHERE searched_at IS NULL
                ORDER BY CASE WHEN verification_status='AI_AGENT_RECHECK_REQUIRED' THEN 0 ELSE 1 END,
                  imported_at DESC,organization_name
                """).query((rs,n)->new SeedSource(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),true)).list());
        seeds.addAll(jdbc.sql("""
                SELECT company_name,careers_url FROM job_sources
                WHERE enabled=true AND permission_status IN ('PUBLIC_ALLOWED','API_LICENSED')
                ORDER BY company_name
                """).query((rs,n)->new SeedSource(null,rs.getString(1),rs.getString(2),false)).list());
        List<AgentJobSubmission> found=new ArrayList<>();
        int searches=0;
        int skipped=0;
        for(SeedSource seed:seeds){
            URI sourceUri;
            try{sourceUri=URI.create(seed.url().contains("://")?seed.url():"https://"+seed.url());}catch(RuntimeException invalid){continue;}
            String host=sourceUri.getHost();if(host==null)continue;
            String query=seed.candidate()
                    ? "current Saudi Arabia jobs official careers direct application for \""+seed.company()+"\""
                    : "current Saudi Arabia or fully remote jobs direct apply at "+seed.company()+" site:"+host;
            if(searchWasUsed(query)){skipped++;markCandidateSearched(seed);continue;}
            JsonNode request=mapper.createObjectNode().put("api_key",apiKey).put("query",query).put("topic","general")
                    .put("search_depth","basic").put("max_results",20).put("include_answer",false).put("include_raw_content",false);
            String body;
            try{body=tavilySearch(request);searches++;}
            catch(TavilyProviderException failure){if(failure.status()==429)throw failure;continue;}
            int resultCount=0,accepted=0,promoted=0;
            for(JsonNode result:read(body).path("results")){
                resultCount++;if(targetWasSeen(text(result,"url"),"SEEDS"))continue;recordDiscoveryTarget(result,"SEEDS","DISCOVERED");
                if(activateVerifiedPublicBoard(result)){promoted++;recordDiscoveryTarget(result,"ATS_BOARD","PROMOTED");continue;}
                AgentJobSubmission job=verifyJobPage(result,seed.candidate()?null:host);
                if(job!=null){found.add(job);accepted++;}
            }
            recordSearch(new SearchQuery(query,"SEEDS"),resultCount,accepted,promoted);
            markCandidateSearched(seed);
        }
        AgentIngestionSummary stored=found.isEmpty()?new AgentIngestionSummary(0,0,0):ingestion.ingest(found);
        return new AgentSearchSummary(true,true,searches,found.size(),stored.inserted(),stored.updated(),stored.rejected(),
                "AI checked "+searches+" previously unsearched approved seeds with no internal credit limit, skipped "+skipped
                        +" already checked seeds, and accepted only verified direct-application job pages");
    }

    private List<SearchQuery> discoveryQueries() {
        List<SearchQuery> queries=new ArrayList<>();int hostIndex=0;
        for(String location:LOCATIONS)for(String industry:INDUSTRIES){
            queries.add(new SearchQuery(location+" "+industry+" current vacancy direct employer application","JOBS"));
            queries.add(new SearchQuery(location+" "+industry+" hiring companies official careers jobs","COMPANIES"));
            queries.add(new SearchQuery(location+" "+industry+" public job board site:"+ATS_HOSTS.get(hostIndex++%ATS_HOSTS.size()),"PLATFORMS"));
            queries.add(new SearchQuery(location+" "+industry+" career fair hiring event exhibitors current jobs direct apply","EVENTS"));
        }
        queries.add(new SearchQuery("fully remote worldwide English jobs direct apply site:jobs.lever.co","PLATFORMS"));
        queries.add(new SearchQuery("fully remote worldwide English jobs direct apply site:job-boards.greenhouse.io","PLATFORMS"));
        queries.add(new SearchQuery("fully remote worldwide English jobs direct apply site:jobs.ashbyhq.com","PLATFORMS"));
        return queries;
    }

    private boolean searchWasUsed(String query) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_discovery_searches WHERE query_hash=:hash)")
                .param("hash",AuthService.hash(query)).query(Boolean.class).single();
    }

    private boolean targetWasSeen(String url, String kind) {
        if (url == null || !isHttp(url)) return true;
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_discovery_targets WHERE target_key=:key)")
                .param("key", discoveryTargetKey(url, kind)).query(Boolean.class).single();
    }

    private void recordSearch(SearchQuery search,int results,int jobs,int seeds) {
        jdbc.sql("""
                INSERT INTO ai_discovery_searches(query_hash,query_text,search_kind,result_count,jobs_accepted,seeds_promoted)
                VALUES (:hash,:query,:kind,:results,:jobs,:seeds) ON CONFLICT (query_hash) DO NOTHING
                """).param("hash",AuthService.hash(search.query())).param("query",search.query()).param("kind",search.kind())
                .param("results",results).param("jobs",jobs).param("seeds",seeds).update();
    }

    private void recordDiscoveryTarget(JsonNode result,String kind,String status) {
        String url=text(result,"url");if(!isHttp(url))return;
        try{
            URI uri=URI.create(url);String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
            if(host.isBlank())return;String targetType=switch(kind){case "EVENTS"->"EVENT";case "PLATFORMS"->"PLATFORM";case "COMPANIES"->"COMPANY";case "ATS_BOARD"->"ATS_BOARD";default->"JOB";};
            String key=discoveryTargetKey(url,kind);
            jdbc.sql("""
                    INSERT INTO ai_discovery_targets(target_key,discovered_url,host,target_type,status)
                    VALUES (:key,:url,:host,:type,:status)
                    ON CONFLICT (target_key) DO UPDATE SET last_seen_at=now(),seen_count=ai_discovery_targets.seen_count+1,
                      status=CASE WHEN EXCLUDED.status='PROMOTED' THEN 'PROMOTED' ELSE ai_discovery_targets.status END,
                      discovered_url=EXCLUDED.discovered_url
                    """).param("key",limit(key,500)).param("url",url).param("host",limit(host,255))
                    .param("type",targetType).param("status",status).update();
        }catch(RuntimeException ignored){}
    }

    private AgentSearchSummary notConfigured(){return new AgentSearchSummary(false,false,0,0,0,0,0,"Tavily is not configured. Add TAVILY_API_KEY to .env and restart the app.");}
    private AgentSearchSummary providerFailure(TavilyProviderException exception){String message=exception.status()==429?"Tavily rejected the search because the account quota or rate limit was reached.":"Tavily request failed with status "+exception.status()+".";return new AgentSearchSummary(true,false,0,0,0,0,0,message);}
    private String tavilySearch(JsonNode request){
        try{
            String json=mapper.writeValueAsString(request);
            HttpRequest httpRequest=HttpRequest.newBuilder(URI.create(baseUrl+"/search"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                    .header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=httpClient.sendAsync(httpRequest,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .orTimeout(30,java.util.concurrent.TimeUnit.SECONDS).join();
            if(response.statusCode()<200||response.statusCode()>=300)throw new TavilyProviderException(response.statusCode());
            return response.body();
        }catch(TavilyProviderException exception){throw exception;}
        catch(Exception exception){throw new TavilyProviderException(504);}
    }
    private static final class TavilyProviderException extends RuntimeException{private final int status;TavilyProviderException(int status){this.status=status;}int status(){return status;}}
    private record SeedSource(UUID candidateId,String company,String url,boolean candidate){}
    private void markCandidateSearched(SeedSource seed){if(seed.candidateId()!=null)jdbc.sql("UPDATE seed_candidates SET searched_at=now() WHERE id=:id").param("id",seed.candidateId()).update();}

    private Set<String> existingSourceKeys() {
        return new HashSet<>(jdbc.sql("SELECT careers_url FROM companies WHERE active=true")
                .query(String.class).list().stream().map(TavilyJobDiscoveryService::sourceKey).toList());
    }

    private static String sourceKey(String value) {
        try {
            URI uri = URI.create(value);
            String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            if (host.equals("jobs.lever.co")) return host + "/" + firstPathSegment(uri).toLowerCase(Locale.ROOT);
            if (host.equals("job-boards.greenhouse.io") || host.equals("boards.greenhouse.io"))
                return "job-boards.greenhouse.io/" + firstPathSegment(uri).toLowerCase(Locale.ROOT);
            if (host.equals("jobs.ashbyhq.com")) return host + "/" + firstPathSegment(uri).toLowerCase(Locale.ROOT);
            if (host.equals("jobs.smartrecruiters.com") || host.equals("careers.smartrecruiters.com"))
                return "jobs.smartrecruiters.com/" + firstPathSegment(uri).toLowerCase(Locale.ROOT);
            return host;
        } catch (RuntimeException exception) { return "invalid:" + value; }
    }

    private static String discoveryTargetKey(String value, String kind) {
        if ("PLATFORMS".equals(kind) || "COMPANIES".equals(kind) || "ATS_BOARD".equals(kind)) {
            return limit(sourceKey(value), 500);
        }
        try {
            URI uri = URI.create(value);
            String normalized = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
            return "url:" + AuthService.hash(normalized);
        } catch (Exception invalid) {
            return "url:" + AuthService.hash(value);
        }
    }

    private boolean activateVerifiedPublicBoard(JsonNode result) {
        String url = text(result, "url");
        if (!isHttp(url)) return false;
        try {
            URI uri = URI.create(url);
            String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
            String token = firstPathSegment(uri);
            URI board;
            URI api;
            if (host.equals("jobs.lever.co")) {
                board = URI.create("https://jobs.lever.co/" + token);
                api = URI.create("https://api.lever.co/v0/postings/" + token + "?mode=json");
                if (!read(restClient.get().uri(api).retrieve().body(String.class)).isArray()) return false;
            } else if (host.equals("job-boards.greenhouse.io") || host.equals("boards.greenhouse.io")) {
                board = URI.create("https://job-boards.greenhouse.io/" + token);
                api = URI.create("https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs?content=true");
                if (!read(restClient.get().uri(api).retrieve().body(String.class)).path("jobs").isArray()) return false;
            } else if (host.equals("jobs.ashbyhq.com")) {
                board=URI.create("https://jobs.ashbyhq.com/"+token);
                api=URI.create("https://api.ashbyhq.com/posting-api/job-board/"+token);
                if(!read(restClient.get().uri(api).retrieve().body(String.class)).path("jobs").isArray())return false;
            } else if (host.equals("jobs.smartrecruiters.com") || host.equals("careers.smartrecruiters.com")) {
                board=URI.create("https://jobs.smartrecruiters.com/"+token);
                api=URI.create("https://api.smartrecruiters.com/v1/companies/"+token+"/postings?limit=1");
                if(!read(restClient.get().uri(api).retrieve().body(String.class)).path("content").isArray())return false;
            } else return false;
            String slug = "ats-" + token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            String title = Objects.toString(text(result, "title"), token).replaceAll("(?i)jobs at|careers at|jobs", "").trim();
            String name = limit(title.isBlank() ? token : title, 180);
            jdbc.sql("""
                    INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
                    VALUES (:slug,:name,:url,:url,'PUBLIC_ATS_API',true,true)
                    ON CONFLICT (slug) DO UPDATE SET careers_url=EXCLUDED.careers_url,automated=true,active=true
                    """).param("slug", slug).param("name", name).param("url", board.toString()).update();
            String sourceType=host.equals("jobs.lever.co")?"LEVER":host.contains("greenhouse.io")?"GREENHOUSE":
                    host.equals("jobs.ashbyhq.com")?"ASHBY":"SMARTRECRUITERS";
            return jdbc.sql("""
                    INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,
                        board_identifier,permission_status,refresh_interval_minutes)
                    SELECT id,name,careers_url,:type,:type,:token,'PUBLIC_ALLOWED',10 FROM companies WHERE slug=:slug
                    ON CONFLICT (lower(careers_url)) DO UPDATE SET enabled=true,permission_status='PUBLIC_ALLOWED',
                        source_type=EXCLUDED.source_type,ats_provider=EXCLUDED.ats_provider,
                        board_identifier=EXCLUDED.board_identifier,refresh_interval_minutes=10,updated_at=now()
                    """).param("type",sourceType).param("token",token).param("slug",slug).update()==1;
        } catch (RuntimeException exception) { return false; }
    }

    private AgentJobSubmission verifyJobPage(JsonNode result) { return verifyJobPage(result,null); }
    private AgentJobSubmission verifyJobPage(JsonNode result,String allowedSeedHost) {
        String url = text(result, "url");
        if (url == null || !isHttp(url) || isNonJobUrl(url)
                || (!isTrustedApplicationHost(url) && !sameHost(url,allowedSeedHost))) return null;
        try {
            String html = restClient.get().uri(url)
                    .header("User-Agent", "TalentShiftJobVerifier/1.0 (+public-job-validation)")
                    .retrieve().body(String.class);
            Document page = Jsoup.parse(Objects.requireNonNull(html), url);
            for (Element script : page.select("script[type=application/ld+json]")) {
                try {
                    JsonNode posting = findJobPosting(mapper.readTree(script.data()));
                    if (posting != null) {
                        AgentJobSubmission job = fromPosting(posting, url,allowedSeedHost);
                        if (job != null) return job;
                    }
                } catch (Exception ignored) { }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private AgentJobSubmission fromPosting(JsonNode posting, String discoveredUrl,String allowedSeedHost) {
        String title = text(posting, "title");
        String company = nestedText(posting, "hiringOrganization", "name");
        String descriptionHtml = text(posting, "description");
        String description = descriptionHtml == null ? null : Jsoup.parse(descriptionHtml).text();
        String location = postingLocation(posting);
        String country = postingCountry(posting);
        boolean remote = "TELECOMMUTE".equalsIgnoreCase(text(posting, "jobLocationType"))
                || Objects.toString(location, "").toLowerCase(Locale.ROOT).contains("remote");
        String evidence = String.join(" ", Objects.toString(location, ""), Objects.toString(country, ""),
                Objects.toString(description, ""));
        if (title == null || company == null || (!remote && !hasSaudiEvidence(evidence) && !"SA".equalsIgnoreCase(country))) return null;
        title = Jsoup.parse(title).text();
        company = Jsoup.parse(company).text();
        String structuredUrl = text(posting, "url");
        String applyUrl = structuredUrl != null && isHttp(structuredUrl) && !isNonJobUrl(structuredUrl)
                ? structuredUrl : discoveredUrl;
        if(!isTrustedApplicationHost(applyUrl)&&!sameHost(applyUrl,allowedSeedHost))return null;
        return new AgentJobSubmission("TAVILY_WEB", AuthService.hash(applyUrl), limit(title, 240), limit(company, 200),
                location == null ? (remote ? "Remote" : "Saudi Arabia") : limit(location, 200), text(posting, "employmentType"),
                remote, null, null,
                limit(description, 12_000), limit(text(posting, "qualifications"), 12_000), applyUrl,
                discoveredUrl, instant(text(posting, "datePosted")));
    }

    private static JsonNode findJobPosting(JsonNode node) {
        if (node == null) return null;
        if (isJobPostingType(node.path("@type"))) return node;
        if (node.isContainerNode()) for (JsonNode child : node) {
            JsonNode found = findJobPosting(child);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isJobPostingType(JsonNode type) {
        if (type.isTextual()) return "JobPosting".equalsIgnoreCase(type.asText());
        if (type.isArray()) for (JsonNode item : type) if ("JobPosting".equalsIgnoreCase(item.asText())) return true;
        return false;
    }

    private static String postingLocation(JsonNode posting) {
        JsonNode address = firstLocation(posting).path("address");
        return join(text(address, "addressLocality"), text(address, "addressRegion"), countryValue(address.path("addressCountry")));
    }

    private static String postingCountry(JsonNode posting) {
        return countryValue(firstLocation(posting).path("address").path("addressCountry"));
    }

    private static JsonNode firstLocation(JsonNode posting) {
        JsonNode locations = posting.path("jobLocation");
        return locations.isArray() && !locations.isEmpty() ? locations.get(0) : locations;
    }

    private static String countryValue(JsonNode country) {
        if (country.isTextual()) return country.asText().trim();
        String name = text(country, "name");
        return name == null ? text(country, "@id") : name;
    }

    private static String join(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank() && !present.contains(value)) present.add(value);
        return present.isEmpty() ? null : String.join(", ", present);
    }

    private static boolean hasSaudiEvidence(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return SAUDI_MARKERS.stream().anyMatch(normalized::contains);
    }

    private static boolean isNonJobUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("youtube.com") || normalized.contains("youtu.be") || normalized.contains("/watch?")
                || normalized.contains("/blog/") || normalized.contains("/article/") || normalized.contains("/news/")
                || normalized.contains("/learning/") || normalized.contains("/search?")
                || normalized.matches(".*\\/(jobs|careers)\\/?$");
    }

    private static boolean isTrustedApplicationHost(String value) {
        try {
            String host = Objects.toString(URI.create(value).getHost(), "").toLowerCase(Locale.ROOT);
            String path = Objects.toString(URI.create(value).getPath(), "").toLowerCase(Locale.ROOT);
            if (host.equals("linkedin.com") || host.endsWith(".linkedin.com")) return path.startsWith("/jobs/view/");
            if (host.equals("indeed.com") || host.endsWith(".indeed.com"))
                return path.startsWith("/viewjob") || path.contains("/rc/clkjob");
            if (host.equals("jooble.org") || host.endsWith(".jooble.org"))
                return path.contains("/desc/") || path.contains("/jdp/") || path.contains("/job/");
            if (host.equals("glassdoor.com") || host.endsWith(".glassdoor.com"))
                return path.contains("/job-listing/") || path.contains("/partner/joblisting");
            if (host.equals("bayt.com") || host.endsWith(".bayt.com")) return path.contains("/jobs/");
            if (host.equals("naukrigulf.com") || host.endsWith(".naukrigulf.com")) return path.contains("job");
            if (host.equals("foundit.in") || host.endsWith(".foundit.in")) return path.contains("job");
            return List.of("myworkdayjobs.com", "workdayjobs.com", "jobs.lever.co", "greenhouse.io",
                    "smartrecruiters.com", "icims.com", "successfactors.com", "oraclecloud.com", "taleo.net",
                    "workable.com", "ashbyhq.com", "recruitee.com", "bamboohr.com", "careers-page.com")
                    .stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        } catch (RuntimeException exception) { return false; }
    }

    private static boolean sameHost(String value,String allowedHost){if(allowedHost==null)return false;try{return allowedHost.equalsIgnoreCase(URI.create(value).getHost());}catch(RuntimeException e){return false;}}

    private JsonNode read(String body) {
        try { return mapper.readTree(Objects.requireNonNull(body)); }
        catch (Exception exception) { throw new IllegalStateException("Invalid response from Tavily", exception); }
    }

    private static boolean isHttp(String value) {
        try { String scheme = URI.create(value).getScheme(); return "http".equals(scheme) || "https".equals(scheme); }
        catch (RuntimeException exception) { return false; }
    }
    private static String firstPathSegment(URI uri) {
        for (String value : uri.getPath().split("/")) if (!value.isBlank()) return value;
        throw new IllegalArgumentException("Missing board token");
    }
    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }
    private static String nestedText(JsonNode node, String parent, String field) { return text(node.path(parent), field); }
    private static String limit(String value, int size) {
        if (value == null) return null;
        return value.length() > size ? value.substring(0, size) : value;
    }
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) {
            try { return java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
            catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }
    private record SearchQuery(String query, String kind) {}
}
