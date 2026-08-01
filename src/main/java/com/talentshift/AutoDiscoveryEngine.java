package com.talentshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AutoDiscoveryEngine — self-sustaining job & seed discovery pipeline.
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  PHASE 1  │  LinkedIn focus  │  LinkedIn jobs in DB < target (300) │
 * │           │  54 queries      │  Only LinkedIn seeds & jobs          │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  PHASE 2  │  Broad ATS       │  LinkedIn jobs >= target             │
 * │           │  45 queries      │  Greenhouse, Lever, Ashby, Workday…  │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  PHASE 3  │  Open sources    │  All fixed queries exhausted (no     │
 * │           │  ∞ dynamic       │  new seeds for 2+ full rounds)       │
 * │           │                  │  • DuckDuckGo with generated queries │
 * │           │                  │  • Remotive API (free, no key)       │
 * │           │                  │  • Arbeitnow API (free, no key)      │
 * │           │                  │  • Jobicy API (free, no key)         │
 * │           │                  │  Jobs saved directly to the DB       │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * At the end of every phase, newly discovered seeds are immediately available
 * (next_retry_at = NOW) so RegistryJobSource picks them up in the next 5-min
 * cycle.  Direct jobs from Phase 3 open APIs are written straight to the jobs
 * table, bypassing the collector pipeline entirely.
 *
 * Runs 60 s after startup, then every 30 minutes.
 */
@Component
public class AutoDiscoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(AutoDiscoveryEngine.class);

    // ─── ATS URL patterns ─────────────────────────────────────────────────────
    private static final List<AtsPattern> ATS_PATTERNS = List.of(
        new AtsPattern("boards(?:-api)?\\.greenhouse\\.io/(?:v1/boards/)?([A-Za-z0-9_-]+)", "GREENHOUSE"),
        new AtsPattern("jobs\\.lever\\.co/([A-Za-z0-9_-]+)",                                "LEVER"),
        new AtsPattern("jobs\\.ashbyhq\\.com/([A-Za-z0-9_-]+)",                            "ASHBY"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.workable\\.com",                                 "WORKABLE"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.breezy\\.hr",                                   "BREEZY"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.recruitee\\.com",                               "RECRUITEE"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.myworkdayjobs\\.com",                           "WORKDAY"),
        new AtsPattern("app\\.smartrecruiters\\.com/(?:jobs/)?([A-Za-z0-9_-]+)",           "SMARTRECRUITERS"),
        new AtsPattern("linkedin\\.com/company/([A-Za-z0-9_-]+)/jobs",                     "LINKEDIN")
    );

    // ─── Phase 1 — LinkedIn queries ───────────────────────────────────────────
    private static final List<String> LINKEDIN_QUERIES = List.of(
        "site:linkedin.com/company Saudi Arabia jobs",
        "site:linkedin.com/company Riyadh jobs",
        "site:linkedin.com/company Jeddah jobs",
        "site:linkedin.com/company NEOM jobs",
        "site:linkedin.com/company Saudi Arabia engineer",
        "site:linkedin.com/company Saudi Arabia manager",
        "site:linkedin.com/company Saudi Arabia finance",
        "site:linkedin.com/company Saudi Arabia operations",
        "site:linkedin.com/company Saudi Arabia technology",
        "site:linkedin.com/company Saudi Arabia healthcare",
        "site:linkedin.com/company Saudi Arabia construction",
        "site:linkedin.com/company Saudi Arabia logistics",
        "site:linkedin.com/company Saudi Arabia banking",
        "site:linkedin.com/company Saudi Arabia consulting",
        "site:linkedin.com/company Saudi Arabia sales",
        "site:linkedin.com/company Saudi Arabia marketing",
        "site:linkedin.com/company Saudi Arabia human resources",
        "site:linkedin.com/company Saudi Arabia data analyst",
        "site:linkedin.com/company Saudi Arabia software",
        "site:linkedin.com/company Saudi Arabia accountant",
        "linkedin.com/company saudi-aramco jobs",
        "linkedin.com/company sabic jobs",
        "linkedin.com/company stc jobs",
        "linkedin.com/company neom jobs",
        "linkedin.com/company almarai jobs",
        "linkedin.com/company acwa-power jobs",
        "linkedin.com/company pif jobs",
        "linkedin.com/company al-rajhi-bank jobs",
        "linkedin.com/company mobily jobs",
        "linkedin.com/company nhc jobs",
        "linkedin.com/company mckinsey jobs saudi",
        "linkedin.com/company deloitte jobs saudi",
        "linkedin.com/company pwc jobs saudi",
        "linkedin.com/company kpmg jobs saudi",
        "linkedin.com/company ey jobs saudi",
        "linkedin.com/company bcg jobs saudi",
        "linkedin.com/company google jobs riyadh",
        "linkedin.com/company microsoft jobs riyadh",
        "linkedin.com/company amazon jobs saudi",
        "linkedin.com/company oracle jobs saudi",
        "linkedin.com/company sap jobs saudi",
        "linkedin.com/company siemens jobs saudi",
        "linkedin.com/company ibm jobs saudi",
        "linkedin.com/company cisco jobs saudi",
        "وظائف السعودية site:linkedin.com/company",
        "وظائف الرياض site:linkedin.com/company",
        "وظائف جدة site:linkedin.com/company",
        "شركات السعودية وظائف linkedin"
    );

    // ─── Phase 2 — Broad ATS queries ─────────────────────────────────────────
    private static final List<String> BROAD_QUERIES = List.of(
        "site:boards.greenhouse.io Saudi Arabia jobs",
        "site:boards.greenhouse.io Riyadh jobs",
        "site:boards.greenhouse.io Jeddah jobs",
        "site:boards.greenhouse.io NEOM jobs",
        "site:boards.greenhouse.io Saudi Arabia engineer",
        "site:boards.greenhouse.io Saudi Arabia manager",
        "site:boards.greenhouse.io Saudi Arabia finance",
        "site:boards.greenhouse.io Saudi Arabia operations",
        "site:jobs.lever.co Saudi Arabia jobs",
        "site:jobs.lever.co Riyadh",
        "site:jobs.lever.co Jeddah",
        "site:jobs.lever.co Saudi engineer",
        "site:jobs.lever.co Saudi manager",
        "site:jobs.ashbyhq.com Saudi Arabia",
        "site:jobs.ashbyhq.com Riyadh OR Jeddah",
        "site:jobs.workable.com Saudi Arabia",
        "site:workable.com Saudi Arabia jobs",
        "site:breezy.hr Saudi Arabia jobs",
        "site:recruitee.com Saudi Arabia jobs",
        "site:myworkdayjobs.com Saudi Arabia",
        "site:myworkdayjobs.com Riyadh OR Jeddah",
        "site:jobs.smartrecruiters.com Saudi Arabia",
        "site:linkedin.com/company jobs Saudi Arabia",
        "careers Saudi Arabia site:greenhouse.io OR site:lever.co OR site:ashbyhq.com",
        "وظائف السعودية site:greenhouse.io",
        "وظائف الرياض site:lever.co",
        "NEOM jobs careers apply greenhouse lever ashby",
        "Saudi Vision 2030 jobs careers apply",
        "Aramco careers site:greenhouse.io OR site:lever.co",
        "SABIC careers site:greenhouse.io OR site:lever.co",
        "STC careers jobs Riyadh greenhouse lever",
        "Almarai careers jobs apply",
        "Mobily careers jobs Riyadh",
        "Al-Rajhi Bank jobs careers",
        "NHC National Housing Company jobs Saudi",
        "ACWA Power careers jobs Saudi",
        "PIF Saudi Arabia jobs careers apply",
        "McKinsey Riyadh jobs careers",
        "Deloitte Saudi Arabia careers jobs",
        "EY Ernst Young Riyadh jobs",
        "KPMG Saudi Arabia jobs",
        "PwC Saudi Arabia careers",
        "Google Saudi Arabia jobs careers",
        "Microsoft Riyadh jobs careers",
        "Amazon Saudi Arabia careers jobs"
    );

    private static final List<String> LINKEDIN_JOB_KEYWORDS = List.of(
        "software engineer", "data analyst", "cybersecurity", "cloud engineer",
        "project manager", "product manager", "finance", "accountant", "sales",
        "marketing", "human resources", "operations", "logistics", "healthcare",
        "construction", "electrical engineer", "mechanical engineer", "civil engineer",
        "customer service", "business analyst", "Arabic", "remote"
    );

    private static final List<String> PLATFORM_JOB_QUERIES = List.of(
        "site:jooble.org Saudi Arabia jobs", "site:jooble.org Riyadh jobs",
        "site:indeed.com Saudi Arabia jobs", "site:indeed.com Riyadh jobs",
        "site:glassdoor.com/job-listing Saudi Arabia", "site:glassdoor.com/job-listing Riyadh",
        "site:bayt.com Saudi Arabia jobs", "site:bayt.com Riyadh jobs",
        "site:naukrigulf.com Saudi Arabia jobs", "site:naukrigulf.com Riyadh jobs",
        "site:foundit.in Saudi Arabia jobs", "site:foundit.in remote Saudi Arabia"
    );

    // ─── Phase 3 — Open job API endpoints ────────────────────────────────────
    /** Free public APIs that return JSON job listings — no auth required. */
    private static final List<OpenApiSource> OPEN_APIS = List.of(
        new OpenApiSource("Remotive",   "https://remotive.com/api/remote-jobs?search=saudi+arabia&limit=100",  "remotive"),
        new OpenApiSource("Remotive-R", "https://remotive.com/api/remote-jobs?search=riyadh&limit=100",        "remotive"),
        new OpenApiSource("Arbeitnow",  "https://www.arbeitnow.com/api/job-board-api?page=1",                  "arbeitnow"),
        new OpenApiSource("Jobicy",     "https://jobicy.com/api/v2/remote-jobs?count=100&tag=saudi+arabia",     "jobicy"),
        new OpenApiSource("Jobicy-R",   "https://jobicy.com/api/v2/remote-jobs?count=100&tag=remote",          "jobicy")
    );

    // ─── Config ───────────────────────────────────────────────────────────────
    private static final String   DDG_URL         = "https://html.duckduckgo.com/html/?q=";
    private static final Duration REQUEST_DELAY   = Duration.ofSeconds(3);
    private static final int      STALE_ROUNDS    = 2; // rounds with 0 new seeds → trigger Phase 3

    private final SafeSourceHttpClient http;
    private final JdbcClient           jdbc;
    private final ObjectMapper         mapper;
    private final boolean              enabled;
    private final int                  linkedInTarget;
    private final LinkedInCollector    linkedInCollector;
    private final SaudiJobPolicy       saudiJobPolicy;
    private final JobRepository        jobs;
    private final OfficialCareersDiscoveryService careersDiscovery;
    private final RuntimeLeaseService leases;

    // ─── State (survives across scheduled runs) ───────────────────────────────
    private final Set<String> executedQueries   = Collections.synchronizedSet(new HashSet<>());
    private       int         emptyRounds       = 0;   // rounds where no new seeds were found
    private       boolean     phase3Active      = false;

    public AutoDiscoveryEngine(SafeSourceHttpClient http, JdbcClient jdbc, ObjectMapper mapper,
            LinkedInCollector linkedInCollector, SaudiJobPolicy saudiJobPolicy, JobRepository jobs,
            OfficialCareersDiscoveryService careersDiscovery,
            RuntimeLeaseService leases,
            @Value("${app.jobs.web-seed-discovery-enabled:true}") boolean enabled,
            @Value("${app.jobs.linkedin-job-target:300}") int linkedInTarget) {
        this.http          = http;
        this.jdbc          = jdbc;
        this.mapper        = mapper;
        this.enabled       = enabled;
        this.linkedInTarget = linkedInTarget;
        this.linkedInCollector = linkedInCollector;
        this.saudiJobPolicy = saudiJobPolicy;
        this.jobs = jobs;
        this.careersDiscovery = careersDiscovery;
        this.leases = leases;
    }

    // ─── Main scheduled entry-point ───────────────────────────────────────────

    @Scheduled(initialDelayString = "${app.jobs.web-seed-discovery-initial-delay-ms:60000}",
            fixedDelayString = "${app.jobs.web-seed-discovery-poll-ms:5400000}")
    public void run() {
        UUID lease = leases.acquire("auto-discovery", Duration.ofHours(2));
        if (lease == null) {
            log.info("[AutoDiscovery] Another application instance owns the discovery lease");
            return;
        }
        try { runUnlocked(lease); }
        finally { leases.release("auto-discovery", lease); }
    }

    private void runUnlocked(UUID lease) {
        if (!enabled) {
            log.info("[AutoDiscovery] Disabled - skipping.");
            return;
        }
        AtomicInteger newSeeds = new AtomicInteger();
        AtomicInteger newJobs = new AtomicInteger();
        executedQueries.clear();

        log.info("[AutoDiscovery] Starting combined 90-minute discovery cycle");
        runLinkedInJobSearch(newJobs);
        leases.renew("auto-discovery", lease, Duration.ofHours(2));
        runPlatformJobSearch(newJobs);
        leases.renew("auto-discovery", lease, Duration.ofHours(2));
        runSearchQueries(buildRotatingQueries(), false, newSeeds, newJobs);
        leases.renew("auto-discovery", lease, Duration.ofHours(2));
        runPhase3(newSeeds, newJobs);
        careersDiscovery.discoverNextBatch();
        log.info("[AutoDiscovery] Combined cycle complete - {} validated seeds, {} accepted jobs",
                newSeeds.get(), newJobs.get());
    }

    private List<String> buildRotatingQueries() {
        List<String> locations = List.of("Saudi Arabia","Riyadh","Jeddah","Dammam","Khobar","NEOM",
                "Makkah","Madinah","Jubail","Yanbu","Tabuk","Remote Saudi Arabia");
        List<String> roles = List.of("software engineer","data analyst","cybersecurity","accountant",
                "project manager","sales","marketing","human resources","operations","logistics",
                "civil engineer","mechanical engineer","electrical engineer","healthcare","customer service");
        List<String> sites = List.of("site:boards.greenhouse.io","site:jobs.lever.co","site:jobs.ashbyhq.com",
                "site:myworkdayjobs.com","site:jobs.smartrecruiters.com","site:workable.com");
        List<String> generated = new ArrayList<>(BROAD_QUERIES);
        long cycle = Instant.now().getEpochSecond() / 5400;
        int total = locations.size() * roles.size() * sites.size();
        int start = Math.floorMod((int)cycle * 47, total);
        for (int offset=0; offset<60; offset++) {
            int index=(start+offset*17)%total;
            String location=locations.get(index%locations.size());
            String role=roles.get((index/locations.size())%roles.size());
            String site=sites.get((index/(locations.size()*roles.size()))%sites.size());
            generated.add(site+" \""+role+"\" \""+location+"\"");
        }
        return generated;
    }

    private void runPlatformJobSearch(AtomicInteger newJobs) {
        int reviewed = 0;
        int inserted = 0;
        Set<String> seen = new HashSet<>();
        for (String query : PLATFORM_JOB_QUERIES) {
            try {
                if (!reserveDiscoveryQuery(query, "PLATFORMS")) continue;
                int insertedBeforeQuery = inserted;
                List<String> results = searchDuckDuckGoPages(query);
                for (String url : results) {
                    if (!seen.add(url.toLowerCase(Locale.ROOT)) || !isSupportedPlatformJobUrl(url)) continue;
                    reviewed++;
                    CollectedJob job = readStructuredPlatformJob(url);
                    if (job == null || !saudiJobPolicy.accepts(job)) continue;
                    if (jobs.upsert(job) == JobRepository.UpsertResult.INSERTED) {
                        inserted++;
                        newJobs.incrementAndGet();
                    }
                }
                completeDiscoveryQuery(query, results.size(), inserted-insertedBeforeQuery, 0);
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                log.debug("[AutoDiscovery] Platform query '{}' failed: {}", query, failure.getMessage());
            }
        }
        log.info("[AutoDiscovery] Platform search reviewed {} job pages and inserted {} new jobs",
                reviewed, inserted);
    }

    private CollectedJob readStructuredPlatformJob(String url) {
        try {
            URI uri = URI.create(url);
            if (!http.robotsAllowed(uri)) return null;
            SourceHttpResponse response = http.get(uri, null);
            if (response.status() < 200 || response.status() >= 400 || response.body().isBlank()) return null;
            Document page = Jsoup.parse(response.body(), url);
            for (Element script : page.select("script[type=application/ld+json]")) {
                JsonNode posting = findJobPosting(mapper.readTree(script.data()));
                if (posting == null) continue;
                String title = jsonText(posting, "title");
                String company = jsonText(posting.path("hiringOrganization"), "name");
                String description = Optional.ofNullable(jsonText(posting, "description"))
                        .map(value -> Jsoup.parse(value).text()).orElse(null);
                JsonNode address = firstJobLocation(posting).path("address");
                String location = joinParts(jsonText(address, "addressLocality"),
                        jsonText(address, "addressRegion"), countryValue(address.path("addressCountry")));
                boolean remote = "TELECOMMUTE".equalsIgnoreCase(jsonText(posting, "jobLocationType")) ||
                        Objects.toString(location, "").toLowerCase(Locale.ROOT).contains("remote");
                if (title == null || company == null) return null;
                String source = "PLATFORM_" + Objects.toString(uri.getHost(), "unknown")
                        .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
                return new CollectedJob(source, sha256(url).substring(0, 40), title, company,
                        Objects.toString(location, remote ? "Remote" : "Saudi Arabia"),
                        jsonText(posting, "employmentType"), remote, null, null, description,
                        jsonText(posting, "qualifications"), url, url,
                        parseInstant(jsonText(posting, "datePosted")), countryValue(address.path("addressCountry")));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static JsonNode findJobPosting(JsonNode node) {
        if (node == null) return null;
        JsonNode type = node.path("@type");
        if ((type.isTextual() && "JobPosting".equalsIgnoreCase(type.asText())) ||
                (type.isArray() && java.util.stream.StreamSupport.stream(type.spliterator(), false)
                        .anyMatch(value -> "JobPosting".equalsIgnoreCase(value.asText())))) return node;
        if (node.isContainerNode()) for (JsonNode child : node) {
            JsonNode found = findJobPosting(child);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonNode firstJobLocation(JsonNode posting) {
        JsonNode location = posting.path("jobLocation");
        return location.isArray() && !location.isEmpty() ? location.get(0) : location;
    }

    private static String jsonText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText().trim();
    }

    private static String countryValue(JsonNode country) {
        if (country == null || country.isMissingNode() || country.isNull()) return null;
        return country.isTextual() ? country.asText().trim() : jsonText(country, "name");
    }

    private static String joinParts(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .distinct().reduce((left, right) -> left + ", " + right).orElse(null);
    }

    private static boolean isSupportedPlatformJobUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
            String path = Objects.toString(uri.getPath(), "").toLowerCase(Locale.ROOT);
            if (host.equals("indeed.com") || host.endsWith(".indeed.com"))
                return path.startsWith("/viewjob") || path.contains("/rc/clkjob");
            if (host.equals("jooble.org") || host.endsWith(".jooble.org"))
                return path.contains("/desc/") || path.contains("/jdp/") || path.contains("/job/");
            if (host.equals("glassdoor.com") || host.endsWith(".glassdoor.com"))
                return path.contains("/job-listing/") || path.contains("/partner/joblisting");
            if (host.equals("bayt.com") || host.endsWith(".bayt.com")) return path.contains("/jobs/");
            if (host.equals("naukrigulf.com") || host.endsWith(".naukrigulf.com")) return path.contains("job");
            return (host.equals("foundit.in") || host.endsWith(".foundit.in")) && path.contains("job");
        } catch (RuntimeException invalid) { return false; }
    }

    private void runLinkedInJobSearch(AtomicInteger newJobs) {
        int accepted = 0;
        Set<String> checkedEmployers = new HashSet<>();
        for (String keyword : LINKEDIN_JOB_KEYWORDS) {
            if (accepted >= linkedInTarget) break;
            try {
                RegisteredJobSource search = new RegisteredJobSource(
                        UUID.randomUUID(), null, "LinkedIn employer",
                        URI.create("https://www.linkedin.com/jobs/search/"), "LINKEDIN", "LINKEDIN",
                        keyword, "SA", 90, null, null, null, 0);
                SourceCollectionResult result = linkedInCollector.collect(
                        search, new SyncCursor(null, null, null), linkedInTarget - accepted);
                for (CollectedJob job : result.jobs()) {
                    if (!saudiJobPolicy.accepts(job)) continue;
                    accepted++;
                    if (jobs.upsert(job) == JobRepository.UpsertResult.INSERTED)
                        newJobs.incrementAndGet();
                    if (checkedEmployers.add(job.company().toLowerCase(Locale.ROOT)))
                        queueOfficialEmployer(job.company());
                    if (accepted >= linkedInTarget) break;
                }
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[AutoDiscovery] LinkedIn query '{}' failed: {}", keyword, e.getMessage());
            }
        }
        log.info("[AutoDiscovery] LinkedIn search reviewed {} filtered openings across {} employers; {} were new",
                accepted, checkedEmployers.size(), newJobs.get());
    }

    private boolean queueOfficialEmployer(String company) {
        if (company == null || company.isBlank() || "LinkedIn employer".equals(company)) return false;
        String query = "\"" + company.replace("\"", "") + "\" official careers Saudi Arabia";
        if (!reserveDiscoveryQuery(query, "COMPANIES")) return false;
        List<String> employerResults = searchDuckDuckGoPages(query);
        for (String candidate : employerResults) {
            try {
                URI uri = URI.create(candidate);
                String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
                // Platforms remain valid final job sources, but they must not be
                // mistaken for the employer's own official domain.
                if (host.isBlank() || isNonEmployerPlatform(host) || !http.robotsAllowed(uri)) continue;
                SourceHttpResponse response = http.get(uri, null);
                if (response.status() < 200 || response.status() >= 400 || response.body().isBlank()) continue;
                String pageText = Jsoup.parse(response.body()).text().toLowerCase(Locale.ROOT);
                String companyToken = company.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
                String strongestToken = Arrays.stream(companyToken.split("\\s+"))
                        .filter(token -> token.length() >= 4).findFirst().orElse("");
                if (!strongestToken.isBlank() && !host.contains(strongestToken) && !pageText.contains(companyToken)) continue;
                int inserted = jdbc.sql("""
                        INSERT INTO seed_candidates(external_seed_id,organization_name,official_domain,source_url,
                          source_type,employer_search_query,country_code,permission_status,verification_status,
                          terms_match_status,enabled)
                        VALUES(:id,:company,:domain,:url,'OFFICIAL_EMPLOYER_DISCOVERY',:query,'SA',
                          'NOT_YET_APPROVED','PUBLIC_PAGE_REACHABLE','ROBOTS_ALLOWED',false)
                        ON CONFLICT (lower(organization_name),lower(coalesce(official_domain,''))) DO NOTHING
                        """).param("id", sha256(company + "|" + host).substring(0, 40))
                        .param("company", company).param("domain", host).param("url", candidate)
                        .param("query", query).update();
                if (inserted > 0) {
                    completeDiscoveryQuery(query, employerResults.size(), 0, 1);
                    return true;
                }
            } catch (RuntimeException ignored) { }
        }
        completeDiscoveryQuery(query, employerResults.size(), 0, 0);
        return false;
    }

    private List<String> searchDuckDuckGoPages(String query) {
        String url = DDG_URL + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        try {
            SourceHttpResponse response = http.get(URI.create(url), null);
            if (response.status() != 200 || response.body().isBlank()) return List.of();
            Document document = Jsoup.parse(response.body(), "https://duckduckgo.com");
            for (Element link : document.select("a.result__a[href], a[data-testid=result-title-a][href]")) {
                String href = decodeDuckDuckGoUrl(link.attr("href"));
                if (href.startsWith("https://") || href.startsWith("http://")) found.add(href);
                if (found.size() >= 8) break;
            }
        } catch (RuntimeException ignored) { }
        return List.copyOf(found);
    }

    private static String decodeDuckDuckGoUrl(String href) {
        try {
            URI uri = URI.create(href.startsWith("//") ? "https:" + href : href);
            String query = uri.getRawQuery();
            if (query != null) for (String part : query.split("&")) {
                if (part.startsWith("uddg=")) return java.net.URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
            }
            return uri.isAbsolute() ? uri.toString() : "";
        } catch (RuntimeException invalid) { return ""; }
    }

    private static boolean isNonEmployerPlatform(String host) {
        return host.equals("linkedin.com") || host.endsWith(".linkedin.com") ||
                host.contains("indeed.") || host.contains("glassdoor.") || host.contains("jooble.") ||
                host.contains("bayt.") || host.contains("naukrigulf.") || host.contains("foundit.") ||
                host.contains("duckduckgo.") || host.contains("google.") || host.contains("youtube.");
    }

    /** Retained for manual diagnostics; the scheduler uses the combined cycle above. */
    public void runLegacy() {
        if (!enabled) { log.info("[AutoDiscovery] Disabled — skipping."); return; }

        int linkedInJobs  = countLinkedInJobs();
        int totalSeeds    = countTotalSeeds();
        int totalJobs     = countTotalJobs();
        boolean phase1Done = linkedInJobs >= linkedInTarget;
        boolean allQueriesDone = executedQueries.containsAll(LINKEDIN_QUERIES) &&
                                 executedQueries.containsAll(BROAD_QUERIES);

        log.info("[AutoDiscovery] ═══════════════════════════════════════════════");
        log.info("[AutoDiscovery] LinkedIn jobs: {} / {}  |  Total jobs: {}  |  Seeds: {}",
                linkedInJobs, linkedInTarget, totalJobs, totalSeeds);
        log.info("[AutoDiscovery] Phase 1 done: {}  |  All queries done: {}  |  Phase 3 active: {}",
                phase1Done, allQueriesDone, phase3Active);

        AtomicInteger newSeeds = new AtomicInteger(0);
        AtomicInteger newJobs  = new AtomicInteger(0);

        // ── PHASE 1: LinkedIn focus ───────────────────────────────────────────
        if (!phase1Done) {
            log.info("[AutoDiscovery] ▶ PHASE 1 — LinkedIn focus ({} LinkedIn jobs so far)",
                    linkedInJobs);
            runSearchQueries(LINKEDIN_QUERIES, true, newSeeds, newJobs);
        }

        // ── PHASE 2: Broad ATS discovery ─────────────────────────────────────
        else if (!allQueriesDone) {
            log.info("[AutoDiscovery] ▶ PHASE 2 — Broad ATS discovery");
            runSearchQueries(BROAD_QUERIES, false, newSeeds, newJobs);
        }

        // ── PHASE 3: Open sources + dynamic queries ───────────────────────────
        else {
            if (newSeeds.get() == 0) emptyRounds++;
            else emptyRounds = 0;

            if (emptyRounds >= STALE_ROUNDS || phase3Active) {
                phase3Active = true;
                log.info("[AutoDiscovery] ▶ PHASE 3 — Open sources + dynamic queries " +
                        "(empty rounds: {})", emptyRounds);
                runPhase3(newSeeds, newJobs);
            } else {
                // Reset and run all queries again (new jobs appear on existing seeds)
                log.info("[AutoDiscovery] ↺  All queries done — resetting for fresh round");
                executedQueries.clear();
                runSearchQueries(BROAD_QUERIES, false, newSeeds, newJobs);
            }
        }

        log.info("[AutoDiscovery] ✅ Round complete — {} new seeds, {} direct jobs saved",
                newSeeds.get(), newJobs.get());
        log.info("[AutoDiscovery] ═══════════════════════════════════════════════");
    }

    // ─── Phase 1 & 2: DuckDuckGo search → seeds ──────────────────────────────

    private void runSearchQueries(List<String> queries, boolean linkedInOnly,
                                  AtomicInteger newSeeds, AtomicInteger newJobs) {
        Set<String> seenUrls = new HashSet<>();
        for (String query : queries) {
            if (executedQueries.contains(query)) continue;    // skip already-run queries this session
            if (!reserveDiscoveryQuery(query, linkedInOnly ? "JOBS" : "SEEDS")) continue;
            executedQueries.add(query);

            try {
                int seedsBeforeQuery = newSeeds.get();
                List<String> urls = searchDuckDuckGo(query);
                for (String url : urls) {
                    if (!seenUrls.add(url.toLowerCase(Locale.ROOT))) continue;
                    AtsMatch match = detectAts(url);
                    if (match == null) continue;
                    if (linkedInOnly && !"LINKEDIN".equals(match.sourceType())) continue;
                    String careersUrl = normaliseUrl(match);
                    if (upsertSeed(careersUrl, match)) {
                        newSeeds.incrementAndGet();
                        log.info("[AutoDiscovery] ✅ New {} seed: {}", match.sourceType(), match.slug());
                    }
                }
                completeDiscoveryQuery(query, urls.size(), 0, newSeeds.get()-seedsBeforeQuery);
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                log.warn("[AutoDiscovery] Search error on '{}': {}", query, e.getMessage());
            }
        }
    }

    // ─── Phase 3: Open APIs + dynamic queries ─────────────────────────────────

    private void runPhase3(AtomicInteger newSeeds, AtomicInteger newJobs) {
        // 3a. Query free open job APIs
        for (OpenApiSource api : OPEN_APIS) {
            try {
                int saved = fetchOpenApi(api);
                newJobs.addAndGet(saved);
                log.info("[AutoDiscovery] {} → {} direct jobs saved", api.name(), saved);
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                log.warn("[AutoDiscovery] Open API error {}: {}", api.name(), e.getMessage());
            }
        }

        // 3b. Generate dynamic queries from content already in DB
        List<String> dynamicQueries = generateDynamicQueries();
        log.info("[AutoDiscovery] Generated {} dynamic queries from DB content", dynamicQueries.size());

        Set<String> seenUrls = new HashSet<>();
        for (String query : dynamicQueries) {
            try {
                if (!reserveDiscoveryQuery(query, "SEEDS")) continue;
                int seedsBeforeQuery = newSeeds.get();
                List<String> urls = searchDuckDuckGo(query);
                for (String url : urls) {
                    if (!seenUrls.add(url.toLowerCase(Locale.ROOT))) continue;
                    AtsMatch match = detectAts(url);
                    if (match == null) continue;
                    String careersUrl = normaliseUrl(match);
                    if (upsertSeed(careersUrl, match)) {
                        newSeeds.incrementAndGet();
                        log.info("[AutoDiscovery] ✅ Phase3 new {} seed: {}", match.sourceType(), match.slug());
                    }
                }
                completeDiscoveryQuery(query, urls.size(), 0, newSeeds.get()-seedsBeforeQuery);
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception e) {
                log.warn("[AutoDiscovery] Dynamic query error: {}", e.getMessage());
            }
        }
    }

    // ─── Open API parsers ─────────────────────────────────────────────────────

    private int fetchOpenApi(OpenApiSource api) throws Exception {
        SourceHttpResponse resp = http.get(URI.create(api.url()), null);
        if (resp.status() != 200 || resp.body().isBlank()) return 0;
        JsonNode root = mapper.readTree(resp.body());
        int saved = 0;

        // Remotive schema: {"jobs": [{id,title,company_name,candidate_required_location,...}]}
        // Arbeitnow schema: {"data": [{slug,title,company_name,location,...}]}
        // Jobicy schema: {"jobs": [{id,jobTitle,companyName,jobGeo,...}]}
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) jobs = root.path("data");
        if (!jobs.isArray()) return 0;

        for (JsonNode j : jobs) {
            try {
                String title       = textOf(j, "title", "jobTitle");
                String company     = textOf(j, "company_name", "companyName", "company");
                String location    = textOf(j, "candidate_required_location", "location", "jobGeo");
                String applyUrl    = textOf(j, "url", "job_posting_url", "jobExcerptLink");
                String description = textOf(j, "description", "jobDescription");
                String postedRaw   = textOf(j, "publication_date", "created_at", "pubDate");
                String externalId  = textOf(j, "id", "slug");

                if (title == null || applyUrl == null || company == null) continue;

                // Filter: only Saudi-relevant or remote
                String locLower = location == null ? "" : location.toLowerCase(Locale.ROOT);
                boolean saudiRelevant = locLower.contains("saudi") || locLower.contains("riyadh") ||
                        locLower.contains("jeddah") || locLower.contains("worldwide") ||
                        locLower.contains("remote") || locLower.contains("anywhere") || locLower.isBlank();
                if (!saudiRelevant) continue;

                boolean inserted = insertDirectJob(
                        api.sourceTag() + ":" + (externalId != null ? externalId : sha256(applyUrl).substring(0, 16)),
                        title, company, location, applyUrl, description, postedRaw,
                        locLower.contains("remote") || locLower.contains("worldwide") || locLower.contains("anywhere"),
                        saudiRelevant
                );
                if (inserted) saved++;
            } catch (Exception e) {
                log.trace("[AutoDiscovery] Job parse error: {}", e.getMessage());
            }
        }
        return saved;
    }

    // ─── Dynamic query generation ─────────────────────────────────────────────

    private List<String> generateDynamicQueries() {
        List<String> queries = new ArrayList<>();
        try {
            // Generate queries from company names in DB that might have ATS boards
            List<String> companies = jdbc.sql("""
                    SELECT company_name FROM (
                      SELECT DISTINCT company_name FROM job_sources
                      WHERE enabled = true AND company_name IS NOT NULL
                    ) companies
                    ORDER BY RANDOM() LIMIT 30
                    """).query(String.class).list();

            for (String company : companies) {
                if (company == null || company.isBlank() || company.startsWith("Saudi Tech Corp")) continue;
                queries.add("\"" + company + "\" careers jobs Saudi Arabia site:greenhouse.io OR site:lever.co OR site:ashbyhq.com");
                queries.add("\"" + company + "\" jobs Riyadh OR Jeddah");
            }

            // Generate category-based queries
            List<String> categories = List.of(
                "software engineer", "product manager", "data scientist", "accountant",
                "civil engineer", "sales manager", "marketing manager", "HR manager",
                "project manager", "operations manager", "financial analyst", "nurse",
                "doctor", "lawyer", "architect", "construction manager", "procurement",
                "supply chain", "logistics coordinator", "business analyst",
                "customer service", "network engineer", "cybersecurity", "DevOps",
                "mechanical engineer", "electrical engineer", "chemical engineer"
            );
            List<String> atsSites = List.of(
                "site:boards.greenhouse.io", "site:jobs.lever.co", "site:jobs.ashbyhq.com",
                "site:jobs.workable.com", "site:breezy.hr"
            );
            Random rng = new Random();
            for (String cat : categories) {
                String site = atsSites.get(rng.nextInt(atsSites.size()));
                queries.add(site + " \"" + cat + "\" Saudi Arabia");
                queries.add(site + " \"" + cat + "\" Riyadh OR Jeddah");
            }

            // Location-based discovery
            List<String> locations = List.of(
                "Riyadh", "Jeddah", "Dammam", "Khobar", "NEOM", "Makkah", "Madinah",
                "Yanbu", "Jubail", "Tabuk", "Dhahran", "Abha", "Hail"
            );
            for (String loc : locations) {
                queries.add("site:boards.greenhouse.io \"" + loc + "\" jobs");
                queries.add("site:jobs.lever.co \"" + loc + "\"");
            }
        } catch (Exception e) {
            log.warn("[AutoDiscovery] Dynamic query generation error: {}", e.getMessage());
        }
        return queries;
    }

    // ─── DuckDuckGo search ────────────────────────────────────────────────────

    private List<String> searchDuckDuckGo(String query) {
        String url = DDG_URL + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        try {
            SourceHttpResponse resp = http.get(URI.create(url), null);
            if (resp.status() != 200 || resp.body().isBlank()) return found;
            Document doc = Jsoup.parse(resp.body());
            for (Element a : doc.select("a[href]")) {
                String href = a.absUrl("href");
                if (href.isBlank()) href = a.attr("href");
                if (isAtsUrl(href)) found.add(href);
            }
            // Raw text fallback
            Matcher m = Pattern.compile(
                    "https?://[^\\s\"'<>]+(?:greenhouse\\.io|lever\\.co|ashbyhq\\.com|" +
                    "workable\\.com|breezy\\.hr|recruitee\\.com|myworkdayjobs\\.com|" +
                    "smartrecruiters\\.com|linkedin\\.com/company)[^\\s\"'<>]*").matcher(resp.body());
            while (m.find()) found.add(m.group());
        } catch (Exception e) {
            log.trace("[AutoDiscovery] DDG error: {}", e.getMessage());
        }
        return found;
    }

    private boolean reserveDiscoveryQuery(String query, String kind) {
        String hash = sha256(kind + "|" + query);
        return jdbc.sql("""
                INSERT INTO backend_discovery_queries(query_hash,query_text,search_kind,last_searched_at)
                VALUES(:hash,:query,:kind,now())
                ON CONFLICT(query_hash) DO UPDATE SET last_searched_at=now(),run_count=backend_discovery_queries.run_count+1
                WHERE backend_discovery_queries.last_searched_at < now()-interval '20 hours'
                RETURNING query_hash
                """).param("hash",hash).param("query",query).param("kind",kind)
                .query(String.class).optional().isPresent();
    }

    private void completeDiscoveryQuery(String query, int results, int accepted, int seeds) {
        jdbc.sql("""
                UPDATE backend_discovery_queries SET result_count=:results,jobs_accepted=:accepted,
                  seeds_promoted=:seeds WHERE query_hash IN (:jobsHash,:seedsHash,:platformHash,:companyHash)
                """).param("results",results).param("accepted",accepted).param("seeds",seeds)
                .param("jobsHash",sha256("JOBS|"+query)).param("seedsHash",sha256("SEEDS|"+query))
                .param("platformHash",sha256("PLATFORMS|"+query)).param("companyHash",sha256("COMPANIES|"+query)).update();
    }

    private boolean isAtsUrl(String url) {
        return url.contains("greenhouse.io") || url.contains("lever.co") ||
               url.contains("ashbyhq.com") || url.contains("workable.com") ||
               url.contains("breezy.hr") || url.contains("recruitee.com") ||
               url.contains("myworkdayjobs.com") || url.contains("smartrecruiters.com") ||
               url.contains("linkedin.com/company");
    }

    // ─── ATS detection ────────────────────────────────────────────────────────

    private AtsMatch detectAts(String url) {
        for (AtsPattern p : ATS_PATTERNS) {
            Matcher m = p.pattern().matcher(url);
            if (m.find()) {
                String slug = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (slug.length() < 2 || slug.length() > 100) continue;
                if (slug.equals("jobs") || slug.equals("company") || slug.equals("careers")) continue;
                return new AtsMatch(p.sourceType(), slug);
            }
        }
        return null;
    }

    private String normaliseUrl(AtsMatch match) {
        return switch (match.sourceType()) {
            case "GREENHOUSE"      -> "https://job-boards.greenhouse.io/"    + match.slug();
            case "LEVER"           -> "https://jobs.lever.co/"               + match.slug();
            case "ASHBY"           -> "https://jobs.ashbyhq.com/"            + match.slug();
            case "WORKABLE"        -> "https://" + match.slug() + ".workable.com/jobs";
            case "BREEZY"          -> "https://" + match.slug() + ".breezy.hr";
            case "RECRUITEE"       -> "https://" + match.slug() + ".recruitee.com";
            case "WORKDAY"         -> "https://" + match.slug() + ".myworkdayjobs.com/jobs";
            case "SMARTRECRUITERS" -> "https://jobs.smartrecruiters.com/"    + match.slug();
            case "LINKEDIN"        -> "https://www.linkedin.com/company/"    + match.slug() + "/jobs";
            default                -> match.slug();
        };
    }

    // ─── Seed upsert ──────────────────────────────────────────────────────────

    private boolean upsertSeed(String careersUrl, AtsMatch match) {
        int exists = jdbc.sql("SELECT COUNT(*) FROM job_sources WHERE lower(careers_url)=lower(:u)")
                .param("u", careersUrl).query(Integer.class).single();
        if (exists > 0) return false;
        if (!isReachablePublicSource(careersUrl)) return false;

        String slug        = match.slug();
        String companyName = toCompanyName(slug);

        jdbc.sql("""
                INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated)
                VALUES(:slug,:name,:website,:careers,'PUBLIC_ATS_API',true)
                ON CONFLICT DO NOTHING
                """)
                .param("slug", slug).param("name", companyName)
                .param("website", "https://www." + slug + ".com")
                .param("careers", careersUrl).update();

        Optional<UUID> cid = jdbc.sql("SELECT id FROM companies WHERE slug=:s")
                .param("s", slug).query(UUID.class).optional();

        jdbc.sql("""
                INSERT INTO job_sources
                    (company_id,company_name,careers_url,source_type,ats_provider,
                     board_identifier,country,permission_status,refresh_interval_minutes,next_retry_at)
                VALUES(:cid,:cname,:url,:type,:provider,:board,'SA','PUBLIC_ALLOWED',60,now())
                ON CONFLICT DO NOTHING
                """)
                .param("cid", cid.orElse(null)).param("cname", companyName)
                .param("url", careersUrl).param("type", match.sourceType())
                .param("provider", match.sourceType()).param("board", slug).update();
        return true;
    }

    private boolean isReachablePublicSource(String careersUrl) {
        try {
            SourceHttpResponse response = http.get(URI.create(careersUrl), null);
            return response.status() >= 200 && response.status() < 400 && !response.body().isBlank();
        } catch (Exception error) {
            log.debug("[AutoDiscovery] Rejected inaccessible source {}: {}", careersUrl, error.getMessage());
            return false;
        }
    }

    // ─── Direct job insert (Phase 3) ──────────────────────────────────────────

    @Transactional
    boolean insertDirectJob(String externalId, String title, String company,
                            String location, String applyUrl, String description,
                            String postedRaw, boolean remote, boolean saudiRelevant) {
        CollectedJob candidate;
        try {
            candidate = new CollectedJob("OPEN_API", externalId, title, company, location,
                    null, remote, null, null, description, null, applyUrl, applyUrl,
                    parseInstant(postedRaw), saudiRelevant ? "SA" : null);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (!saudiJobPolicy.accepts(candidate)) return false;

        String source      = "OPEN_API";
        String dedupKey    = sha256(source + ":" + externalId).substring(0, 64);
        String fingerprint = sha256(title.toLowerCase(Locale.ROOT) + company.toLowerCase(Locale.ROOT));

        int exists = jdbc.sql("SELECT COUNT(*) FROM jobs WHERE dedup_key=:d")
                .param("d", dedupKey).query(Integer.class).single();
        if (exists > 0) return false;

        Instant postedAt  = parseInstant(postedRaw);
        Instant expiresAt = Instant.now().plus(Duration.ofDays(30));

        jdbc.sql("""
                INSERT INTO jobs(
                    source, external_id, title, company, location, remote,
                    apply_url, source_url, description, dedup_key, posted_at,
                    expires_at, canonical_application_url, content_fingerprint,
                    saudi_relevant, source_quality, collection_status)
                VALUES(
                    :source, :extId, :title, :company, :location, :remote,
                    :applyUrl, :applyUrl, :desc, :dedupKey, :postedAt,
                    :expiresAt, :applyUrl, :fingerprint,
                    :saudiRelevant, 4, 'COMPLETE')
                ON CONFLICT DO NOTHING
                """)
                .param("source",       source)
                .param("extId",        externalId)
                .param("title",        title.length()   > 240 ? title.substring(0, 240)   : title)
                .param("company",      company.length() > 200 ? company.substring(0, 200) : company)
                .param("location",     location == null ? null
                        : location.length() > 200 ? location.substring(0, 200) : location)
                .param("remote",       remote)
                .param("applyUrl",     applyUrl)
                .param("desc",         description)
                .param("dedupKey",     dedupKey)
                .param("postedAt",     postedAt == null ? null : java.sql.Timestamp.from(postedAt))
                .param("expiresAt",    java.sql.Timestamp.from(expiresAt))
                .param("fingerprint",  fingerprint.substring(0, 64))
                .param("saudiRelevant", saudiRelevant)
                .update();
        return true;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Instant.parse(raw); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(raw).toInstant(); } catch (DateTimeParseException ignored) {}
        return null;
    }

    // ─── DB counters ──────────────────────────────────────────────────────────

    private int countLinkedInJobs() {
        try {
            return jdbc.sql("""
                    SELECT COUNT(*) FROM jobs j
                    JOIN job_sources s ON j.source_id = s.id
                    WHERE s.source_type = 'LINKEDIN'
                    """).query(Integer.class).single();
        } catch (Exception e) { return 0; }
    }

    private int countTotalJobs() {
        try { return jdbc.sql("SELECT COUNT(*) FROM jobs WHERE status='ACTIVE'")
                .query(Integer.class).single(); } catch (Exception e) { return 0; }
    }

    private int countTotalSeeds() {
        try { return jdbc.sql("SELECT COUNT(*) FROM job_sources WHERE enabled=true")
                .query(Integer.class).single(); } catch (Exception e) { return 0; }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String textOf(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    private static String toCompanyName(String slug) {
        StringBuilder sb = new StringBuilder();
        for (String part : slug.split("[-_]"))
            if (!part.isBlank()) sb.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT)).append(' ');
        return sb.toString().trim();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { return input.hashCode() + "0".repeat(60); }
    }

    // ─── Inner types ──────────────────────────────────────────────────────────

    private static final class AtsPattern {
        private final String  sourceType;
        private final Pattern compiled;
        AtsPattern(String raw, String type) { sourceType = type; compiled = Pattern.compile(raw, Pattern.CASE_INSENSITIVE); }
        String  sourceType() { return sourceType; }
        Pattern pattern()    { return compiled; }
    }

    private record AtsMatch(String sourceType, String slug) {}
    private record OpenApiSource(String name, String url, String sourceTag) {}
}
