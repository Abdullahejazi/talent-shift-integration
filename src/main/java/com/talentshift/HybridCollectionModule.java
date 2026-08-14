package com.talentshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

record RegisteredJobSource(UUID id, UUID companyId, String companyName, URI careersUrl, String sourceType,
        String atsProvider, String boardIdentifier, String country, int refreshMinutes, String etag,
        String lastModified, String responseChecksum, int consecutiveFailures) {}

record SyncCursor(String etag, String lastModified, String checksum) {}

record SourceCollectionResult(List<CollectedJob> jobs, boolean completeSnapshot, int httpStatus,
        long responseBytes, String etag, String lastModified, String checksum, boolean notModified) {
    static SourceCollectionResult empty(int status, boolean notModified) {
        return new SourceCollectionResult(List.of(), false, status, 0, null, null, null, notModified);
    }
}

interface RegisteredSourceCollector {
    boolean supports(RegisteredJobSource source);
    SourceCollectionResult collect(RegisteredJobSource source, SyncCursor cursor);
    default int priority() { return 100; }
}

@Repository
class JobSourceRegistry {
    private final JdbcClient jdbc;

    JobSourceRegistry(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<RegisteredJobSource> due(int limit) {
        return jdbc.sql("""
                SELECT id, company_id, company_name, careers_url, source_type, ats_provider, board_identifier,
                       country, refresh_interval_minutes, etag, last_modified, response_checksum, consecutive_failures
                FROM job_sources
                WHERE enabled=true AND permission_status IN ('PUBLIC_ALLOWED','API_LICENSED')
                  AND next_retry_at <= now()
                  AND (circuit_open_until IS NULL OR circuit_open_until <= now())
                ORDER BY next_retry_at, source_quality_order(source_type), company_name
                LIMIT :limit
                """.replace("source_quality_order(source_type)", "CASE WHEN source_type IN ('GREENHOUSE','LEVER','ASHBY','SMARTRECRUITERS','WORKDAY','WORKABLE','BREEZY','RECRUITEE','LINKEDIN') THEN 1 WHEN source_type IN ('JSON_LD','RSS') THEN 2 ELSE 3 END"))
                .param("limit", limit)
                .query((rs, row) -> new RegisteredJobSource(rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class), rs.getString("company_name"),
                        URI.create(rs.getString("careers_url")), rs.getString("source_type"),
                        rs.getString("ats_provider"), rs.getString("board_identifier"), rs.getString("country"),
                        rs.getInt("refresh_interval_minutes"), rs.getString("etag"), rs.getString("last_modified"),
                        rs.getString("response_checksum"), rs.getInt("consecutive_failures"))).list();
    }

    void forceAllDue() {
        jdbc.sql("""
                UPDATE job_sources SET next_retry_at=now(),circuit_open_until=NULL,
                    etag=NULL,last_modified=NULL,response_checksum=NULL,updated_at=now()
                WHERE enabled=true AND permission_status IN ('PUBLIC_ALLOWED','API_LICENSED')
                """).update();
    }

    @Transactional
    void success(RegisteredJobSource source, SourceCollectionResult result, Instant started, Instant finished) {
        int adaptiveMinutes = result.notModified()
                ? Math.min(1440, Math.max(30, source.refreshMinutes() * 2))
                : result.jobs().isEmpty()
                    ? Math.min(720, Math.max(60, source.refreshMinutes() * 2))
                    : Math.max(10, Math.min(source.refreshMinutes(), 60));
        jdbc.sql("""
                UPDATE job_sources SET last_success_at=:finished, consecutive_failures=0, last_error_code=NULL,
                    circuit_open_until=NULL,
                    next_retry_at=:next,refresh_interval_minutes=:refresh,
                    productive_runs=productive_runs+CASE WHEN :productive THEN 1 ELSE 0 END,
                    empty_runs=empty_runs+CASE WHEN :productive THEN 0 ELSE 1 END,
                    etag=COALESCE(:etag,etag), last_modified=COALESCE(:lastModified,last_modified),
                    response_checksum=COALESCE(:checksum,response_checksum), updated_at=now()
                WHERE id=:id
                """).param("finished", at(finished)).param("next", at(finished.plus(Duration.ofMinutes(adaptiveMinutes))))
                .param("refresh",adaptiveMinutes).param("productive",!result.jobs().isEmpty())
                .param("etag", result.etag()).param("lastModified", result.lastModified())
                .param("checksum", result.checksum()).param("id", source.id()).update();
        fetchLog(source.id(), started, finished, result.httpStatus(), result.jobs().size(), result.responseBytes(),
                result.checksum(), result.notModified() ? "NOT_MODIFIED" : "SUCCESS", null);
    }

    @Transactional
    void failure(RegisteredJobSource source, Instant started, Throwable error) {
        int failures = source.consecutiveFailures() + 1;
        long delayMinutes = Math.min(1440, 5L * (1L << Math.min(8, failures - 1)));
        Instant now = Instant.now();
        Duration retryAfter = error instanceof SourceHttpException exception ? exception.retryAfter() : null;
        Instant nextRetry = retryAfter == null ? now.plus(Duration.ofMinutes(delayMinutes)) : now.plus(retryAfter);
        String code = error instanceof SourceHttpException exception ? exception.code() : error.getClass().getSimpleName();
        boolean retryable = !(error instanceof SourceHttpException exception) || exception.retryable();
        Instant circuitUntil = retryable && failures >= 5 ? now.plus(Duration.ofHours(6)) : null;
        jdbc.sql("""
                UPDATE job_sources SET last_failure_at=:now, consecutive_failures=consecutive_failures+1,
                    next_retry_at=:next, last_error_code=:code, circuit_open_until=:circuit,
                    enabled=CASE WHEN NOT :retryable AND :failures >= 3 THEN false ELSE enabled END,
                    updated_at=now() WHERE id=:id
                """).param("now", at(now)).param("next", at(nextRetry))
                .param("code", limit(code, 80)).param("circuit",circuitUntil==null?null:at(circuitUntil),java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("retryable",retryable).param("failures",failures).param("id", source.id()).update();
        fetchLog(source.id(), started, now, error instanceof SourceHttpException e ? e.status() : null,
                0, 0, null, error instanceof SourceHttpException e && !e.retryable()
                        ? "PERMANENT_FAILURE" : "RETRYABLE_FAILURE", code);
    }

    private void fetchLog(UUID id, Instant started, Instant finished, Integer status, int count, long bytes,
            String checksum, String outcome, String code) {
        jdbc.sql("""
                INSERT INTO job_source_fetches(source_id,started_at,finished_at,http_status,result_count,
                    response_bytes,checksum,outcome,error_code)
                VALUES (:id,:started,:finished,:status,:count,:bytes,:checksum,:outcome,:code)
                """).param("id", id).param("started", at(started)).param("finished", at(finished))
                .param("status", status).param("count", count).param("bytes", bytes).param("checksum", checksum)
                .param("outcome", outcome).param("code", code).update();
    }

    private static OffsetDateTime at(Instant value) { return OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC); }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}

record SourceHttpResponse(int status, String body, long bytes, String etag, String lastModified, String checksum) {}

class SourceHttpException extends RuntimeException {
    private final int status;
    private final boolean retryable;
    private final String code;
    private final Duration retryAfter;
    SourceHttpException(int status, boolean retryable, String code) {
        this(status,retryable,code,null);
    }
    SourceHttpException(int status, boolean retryable, String code, Duration retryAfter) {
        super(code); this.status=status; this.retryable=retryable; this.code=code; this.retryAfter=retryAfter;
    }
    int status() { return status; }
    boolean retryable() { return retryable; }
    String code() { return code; }
    Duration retryAfter() { return retryAfter; }
}

@Component
class SafeSourceHttpClient {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final int maxBytes;
    private final int maxRedirects;

    SafeSourceHttpClient(@Value("${app.jobs.http-max-response-bytes:50000000}") int maxBytes,
                         @Value("${app.jobs.http-max-redirects:3}") int maxRedirects) {
        this.maxBytes = 100_000_000;
        this.maxRedirects = Math.max(0, Math.min(maxRedirects, 5));
    }

    SourceHttpResponse get(URI uri, SyncCursor cursor) {
        URI current = validate(uri);
        String urlStr = current.toString();
        boolean isAtsApi = urlStr.contains("boards-api.greenhouse") || urlStr.contains("api.lever.co") || 
                           urlStr.contains("smartrecruiters.com") || urlStr.contains("workday") || 
                           urlStr.contains("ashbyhq") || urlStr.contains("api.breezy") || 
                           urlStr.contains("recruitee.com") || urlStr.contains("workable.com");
                           
        if (!isAtsApi) {
            current = URI.create("https://r.jina.ai/" + urlStr);
        }

        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(current).GET().timeout(Duration.ofSeconds(20))
                        .header("User-Agent", USER_AGENT).header("Accept", "application/json, application/ld+json, application/xml, text/html;q=0.9, */*;q=0.5")
                        .header("Accept-Encoding", "gzip");
                if (cursor != null && cursor.etag() != null) request.header("If-None-Match", cursor.etag());
                if (cursor != null && cursor.lastModified() != null) request.header("If-Modified-Since", cursor.lastModified());
                HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 304) return new SourceHttpResponse(304, "", 0,
                        response.headers().firstValue("ETag").orElse(null),
                        response.headers().firstValue("Last-Modified").orElse(null), cursor == null ? null : cursor.checksum());
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElseThrow(
                            () -> new SourceHttpException(status, true, "REDIRECT_WITHOUT_LOCATION"));
                    current = validate(current.resolve(location));
                    continue;
                }
                if (status == 429) throw new SourceHttpException(status, true, "RATE_LIMITED",
                        parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null)));
                if (status == 403) throw new SourceHttpException(status, true, "HTTP_403");
                if (status >= 500) throw new SourceHttpException(status, true, "UPSTREAM_" + status);
                if (status < 200 || status >= 300) throw new SourceHttpException(status, false, "HTTP_" + status);
                InputStream responseStream = response.body();
                if (response.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT).contains("gzip"))
                    responseStream = new GZIPInputStream(responseStream);
                try (InputStream input = responseStream) {
                    byte[] bytes = input.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) throw new SourceHttpException(413, false, "RESPONSE_TOO_LARGE");
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    return new SourceHttpResponse(status, body, bytes.length,
                            response.headers().firstValue("ETag").orElse(null),
                            response.headers().firstValue("Last-Modified").orElse(null), AuthService.hash(body));
                }
            } catch (SourceHttpException exception) { throw exception; }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new SourceHttpException(0, true, "INTERRUPTED"); }
            catch (Exception exception) { throw new SourceHttpException(0, true, "NETWORK_ERROR"); }
        }
        throw new SourceHttpException(310, false, "TOO_MANY_REDIRECTS");
    }

    boolean robotsAllowed(URI uri) {
        try {
            URI robots = validate(URI.create(uri.getScheme() + "://" + uri.getHost() + "/robots.txt"));
            String body = get(robots, null).body();
            boolean applies = false;
            List<RobotsEntry> entries = new ArrayList<>();
            for (String raw : body.split("\\R")) {
                String line = raw.split("#",2)[0].trim();
                int colon = line.indexOf(':'); if (colon < 0) continue;
                String field=line.substring(0,colon).trim().toLowerCase(Locale.ROOT), value=line.substring(colon+1).trim();
                if (field.equals("user-agent")) applies=value.equals("*") || USER_AGENT.toLowerCase(Locale.ROOT).startsWith(value.toLowerCase(Locale.ROOT));
                else if (applies && (field.equals("allow") || field.equals("disallow")) && !value.isBlank())
                    entries.add(new RobotsEntry(value, field.equals("allow")));
            }
            String path = Objects.toString(uri.getPath(), "/");
            return entries.stream().filter(e -> path.startsWith(e.path())).max(Comparator.comparingInt(e -> e.path().length()))
                    .map(RobotsEntry::allow).orElse(true);
        } catch (RuntimeException exception) { return true; }
    }

    private URI validate(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new SourceHttpException(0, false, "UNSAFE_SCHEME");
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new SourceHttpException(0, false, "INVALID_HOST");
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress())
                    throw new SourceHttpException(0, false, "PRIVATE_ADDRESS_BLOCKED");
            }
        } catch (SourceHttpException exception) { throw exception; }
        catch (Exception exception) { throw new SourceHttpException(0, true, "DNS_FAILURE"); }
        return uri;
    }
    private static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Duration.ofSeconds(Math.max(1,Math.min(Long.parseLong(value.trim()),86400))); }
        catch (NumberFormatException ignored) {
            try { Duration duration=Duration.between(Instant.now(),java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());return duration.isNegative()?Duration.ofSeconds(1):duration.compareTo(Duration.ofDays(1))>0?Duration.ofDays(1):duration; }
            catch (DateTimeParseException invalid) { return null; }
        }
    }
    private record RobotsEntry(String path, boolean allow) {}
}

abstract class JsonSourceCollector {
    final SafeSourceHttpClient http;
    final ObjectMapper mapper;
    JsonSourceCollector(SafeSourceHttpClient http, ObjectMapper mapper) { this.http=http; this.mapper=mapper; }
    JsonNode read(String body) {
        try { return mapper.readTree(body); } catch (Exception e) { throw new SourceHttpException(200,false,"MALFORMED_JSON"); }
    }
    SourceCollectionResult result(List<CollectedJob> jobs, SourceHttpResponse response, boolean complete) {
        return new SourceCollectionResult(List.copyOf(jobs), complete, response.status(), response.bytes(),
                response.etag(), response.lastModified(), response.checksum(), response.status()==304);
    }
    static String text(JsonNode n,String f){String v=n.path(f).asText("").trim();return v.isBlank()?null:v;}
    static String required(JsonNode n,String f){String v=text(n,f);if(v==null)throw new IllegalArgumentException(f+" required");return v;}
    static String location(JsonNode n){return join(text(n,"location"),text(n,"city"),text(n,"region"),text(n,"country"));}
    static String join(String... values){List<String> v=new ArrayList<>();for(String s:values)if(s!=null&&!s.isBlank()&&!v.contains(s))v.add(s);return v.isEmpty()?null:String.join(", ",v);}
    static String plain(String html){return html==null?null:Jsoup.parse(html).text();}
    static Instant instant(String value){if(value==null)return null;try{return Instant.parse(value);}catch(DateTimeParseException e){try{return OffsetDateTime.parse(value).toInstant();}catch(Exception ignored){return null;}}}
    static boolean remote(String value){return value!=null&&value.toLowerCase(Locale.ROOT).matches(".*(remote|worldwide|anywhere|global|emea|mena).*" );}
    static String sourceName(RegisteredJobSource s){return "DIRECT_"+s.sourceType();}
    static String external(RegisteredJobSource s,String id){return limit(Objects.toString(s.boardIdentifier(),s.id().toString())+":"+id,180);}
    static String limit(String value,int max){return value!=null&&value.length()>max?value.substring(0,max):value;}
}

@Component
class GreenhouseCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    GreenhouseCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "GREENHOUSE".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://boards-api.greenhouse.io/v1/boards/"+s.boardIdentifier()+"/jobs?content=true");
        SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);List<CollectedJob> out=new ArrayList<>();
        for(JsonNode j:read(r.body()).path("jobs")){String url=text(j,"absolute_url"),loc=text(j.path("location"),"name");if(url==null)continue;
            out.add(new CollectedJob(sourceName(s),external(s,j.path("id").asText()),required(j,"title"),s.companyName(),loc,null,remote(loc),null,null,plain(text(j,"content")),null,url,url,instant(text(j,"updated_at")),null));}
        return result(out,r,true);
    }
}

@Component
class LeverCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    LeverCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "LEVER".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://api.lever.co/v0/postings/"+s.boardIdentifier()+"?mode=json");SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);
        List<CollectedJob> out=new ArrayList<>();for(JsonNode j:read(r.body())){String loc=text(j.path("categories"),"location"),url=text(j,"applyUrl");if(url==null)url=text(j,"hostedUrl");if(url==null)continue;
            out.add(new CollectedJob(sourceName(s),external(s,required(j,"id")),required(j,"text"),s.companyName(),loc,text(j.path("categories"),"commitment"),remote(loc)||"remote".equalsIgnoreCase(text(j,"workplaceType")),null,text(j.path("categories"),"team"),text(j,"descriptionPlain"),plain(text(j,"additionalPlain")),url,Objects.toString(text(j,"hostedUrl"),url),j.path("createdAt").asLong()>0?Instant.ofEpochMilli(j.path("createdAt").asLong()):null,null));}
        return result(out,r,true);
    }
}

@Component
class AshbyCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    AshbyCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "ASHBY".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://api.ashbyhq.com/posting-api/job-board/"+s.boardIdentifier()+"?includeCompensation=true");SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);
        List<CollectedJob> out=new ArrayList<>();for(JsonNode j:read(r.body()).path("jobs")){String url=text(j,"applyUrl"),loc=text(j,"location"),title=text(j,"title");if(url==null||title==null)continue;String compensation=j.path("compensation").isMissingNode()?null:j.path("compensation").toString();
            out.add(new CollectedJob(sourceName(s),external(s,Objects.toString(text(j,"id"),AuthService.hash(url))),title,s.companyName(),loc,text(j,"employmentType"),j.path("isRemote").asBoolean(false)||remote(loc),compensation,text(j,"team"),plain(text(j,"descriptionHtml")),null,url,Objects.toString(text(j,"jobUrl"),url),instant(text(j,"publishedAt")),null));}
        return result(out,r,true);
    }
}

@Component
class SmartRecruitersCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    SmartRecruitersCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "SMARTRECRUITERS".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://api.smartrecruiters.com/v1/companies/"+s.boardIdentifier()+"/postings?limit=100");SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);
        List<CollectedJob> out=new ArrayList<>();for(JsonNode j:read(r.body()).path("content")){String id=required(j,"id"),loc=location(j.path("location"));String url="https://jobs.smartrecruiters.com/"+s.boardIdentifier()+"/"+id;
            out.add(new CollectedJob(sourceName(s),external(s,id),required(j,"name"),s.companyName(),loc,text(j,"typeOfEmployment"),remote(loc),null,text(j.path("department"),"label"),null,null,url,url,instant(text(j,"releasedDate")),text(j.path("location"),"country")));}
        return result(out,r,true);
    }
}

@Component
class WorkdayCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    private final RestClient rest;
    WorkdayCollector(SafeSourceHttpClient h,ObjectMapper m,RestClient rest){super(h,m);this.rest=rest;}
    public boolean supports(RegisteredJobSource s){return "WORKDAY".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor ignored){
        String host=Objects.toString(s.careersUrl().getHost(),"").toLowerCase(Locale.ROOT);
        if(!host.endsWith(".myworkdayjobs.com"))throw new SourceHttpException(0,false,"UNTRUSTED_WORKDAY_HOST");
        String[] board=Objects.toString(s.boardIdentifier(),"").split("\\|",2);
        if(board.length!=2||!board[0].matches("[A-Za-z0-9_-]+")||!board[1].matches("[A-Za-z0-9_-]+"))
            throw new SourceHttpException(0,false,"INVALID_WORKDAY_BOARD");
        URI endpoint=URI.create("https://"+host+"/wday/cxs/"+board[0]+"/"+board[1]+"/jobs");
        Map<String,CollectedJob> out=new LinkedHashMap<>();long bytes=0;
        // Saudi roles remain the primary scope. Fully remote roles are also allowed by
        // JobIngestionPolicy, including roles outside KSA when their public posting is
        // in English or Arabic. Searching both common Workday labels substantially
        // improves coverage without downloading an employer's entire global board.
        for(String search:List.of("Saudi Arabia","Riyadh","Jeddah","Dammam","Khobar","NEOM","Remote","Virtual")){
        int offset=0,total=Integer.MAX_VALUE;
        while(offset<total){
            String body;
            try{body=rest.post().uri(endpoint).header("Content-Type","application/json")
                    .body(Map.of("appliedFacets",Map.of(),"limit",20,"offset",offset,"searchText",search))
                    .retrieve().body(String.class);}
            catch(RuntimeException e){throw new SourceHttpException(0,true,"WORKDAY_REQUEST_FAILED");}
            if(body==null)throw new SourceHttpException(200,false,"EMPTY_WORKDAY_RESPONSE");
            bytes+=body.getBytes(StandardCharsets.UTF_8).length;JsonNode root=read(body);total=root.path("total").asInt(0);
            JsonNode postings=root.path("jobPostings");if(!postings.isArray()||postings.isEmpty())break;
            for(JsonNode j:postings){String path=text(j,"externalPath"),title=text(j,"title"),loc=text(j,"locationsText");if(path==null||title==null)continue;
                String locale=workdayLocale(s.careersUrl());
                String url="https://"+host+"/"+locale+"/"+board[1]+path;
                String id=j.path("bulletFields").isArray()&&!j.path("bulletFields").isEmpty()?j.path("bulletFields").get(0).asText():AuthService.hash(path);
                CollectedJob job=new CollectedJob(sourceName(s),external(s,id),title,s.companyName(),loc,text(j,"timeType"),remote(loc),null,null,null,null,url,url,null,null);
                out.putIfAbsent(job.dedupKey(),job);}
            offset+=postings.size();
        }
        }
        String checksum=AuthService.hash(out.values().stream().map(CollectedJob::dedupKey).sorted().reduce("",String::concat));
        return new SourceCollectionResult(List.copyOf(out.values()),true,200,bytes,null,null,checksum,false);
    }

    private static String workdayLocale(URI careersUrl){
        String path=Objects.toString(careersUrl.getPath(),"");
        Matcher matcher=Pattern.compile("^/([a-z]{2}-[A-Z]{2})(?:/|$)").matcher(path);
        return matcher.find()?matcher.group(1):"en-US";
    }
}

@Component
class StructuredDataCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    StructuredDataCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "JSON_LD".equals(s.sourceType());}
    public int priority(){return 10;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        if(!http.robotsAllowed(s.careersUrl()))throw new SourceHttpException(403,false,"ROBOTS_DISALLOWED");SourceHttpResponse r=http.get(s.careersUrl(),c);if(r.status()==304)return result(List.of(),r,false);
        Document d=Jsoup.parse(r.body(),s.careersUrl().toString());List<CollectedJob> out=new ArrayList<>();
        for(Element script:d.select("script[type=application/ld+json]")){try{collectNodes(read(script.data()),s,out);}catch(RuntimeException ignored){}}
        return result(out,r,false);
    }
    private void collectNodes(JsonNode n,RegisteredJobSource s,List<CollectedJob> out){if(n==null)return;if(isJob(n)){String title=text(n,"title"),url=text(n,"url"),company=text(n.path("hiringOrganization"),"name");JsonNode a=first(n.path("jobLocation")).path("address");String loc=join(text(a,"addressLocality"),text(a,"addressRegion"),country(a.path("addressCountry")));boolean rem="TELECOMMUTE".equalsIgnoreCase(text(n,"jobLocationType"))||remote(loc);if(title!=null&&url!=null)out.add(new CollectedJob(sourceName(s),external(s,AuthService.hash(url)),title,Objects.toString(company,s.companyName()),loc,text(n,"employmentType"),rem,null,null,plain(text(n,"description")),plain(text(n,"qualifications")),url,s.careersUrl().toString(),instant(text(n,"datePosted")),country(a.path("addressCountry"))));return;}if(n.isContainerNode())n.forEach(child->collectNodes(child,s,out));}
    private static boolean isJob(JsonNode n){JsonNode t=n.path("@type");if(t.isTextual())return "JobPosting".equalsIgnoreCase(t.asText());if(t.isArray())for(JsonNode i:t)if("JobPosting".equalsIgnoreCase(i.asText()))return true;return false;}
    private static JsonNode first(JsonNode n){return n.isArray()&&!n.isEmpty()?n.get(0):n;}
    private static String country(JsonNode n){return n.isTextual()?n.asText():text(n,"name");}
}

@Component
class RssJobCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    RssJobCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "RSS".equals(s.sourceType());}
    public int priority(){return 20;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        if(!http.robotsAllowed(s.careersUrl()))throw new SourceHttpException(403,false,"ROBOTS_DISALLOWED");SourceHttpResponse r=http.get(s.careersUrl(),c);if(r.status()==304)return result(List.of(),r,true);Document xml=Jsoup.parse(r.body(),"",Parser.xmlParser());List<CollectedJob> out=new ArrayList<>();
        for(Element item:xml.select("item, entry")){String title=pick(item,"title"),url=pick(item,"link");if(url==null){Element link=item.selectFirst("link[href]");url=link==null?null:link.attr("href");}if(title==null||url==null)continue;String body=Objects.toString(pick(item,"description"),pick(item,"summary"));out.add(new CollectedJob(sourceName(s),external(s,Objects.toString(pick(item,"guid"),AuthService.hash(url))),title,s.companyName(),findSaudi(body),null,remote(body),null,null,plain(body),null,url,s.careersUrl().toString(),instant(Objects.toString(pick(item,"pubDate"),pick(item,"updated"))),null));}
        return result(out,r,true);
    }
    private static String pick(Element e,String tag){Element x=e.selectFirst(tag);return x==null||x.text().isBlank()?null:x.text().trim();}
    private static String findSaudi(String v){if(v==null)return null;Matcher m=Pattern.compile("(?i)Saudi Arabia|Riyadh|Jeddah|Dammam|Khobar|NEOM").matcher(v);return m.find()?m.group():null;}
}

@Component
class GenericHtmlCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    GenericHtmlCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "GENERIC_HTML".equals(s.sourceType())||"JSON_LD".equals(s.sourceType());}
    public int priority(){return 30;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        if(!http.robotsAllowed(s.careersUrl()))throw new SourceHttpException(403,false,"ROBOTS_DISALLOWED");SourceHttpResponse r=http.get(s.careersUrl(),c);if(r.status()==304)return result(List.of(),r,false);Document d=Jsoup.parse(r.body(),s.careersUrl().toString());Set<String> seen=new LinkedHashSet<>();List<CollectedJob> out=new ArrayList<>();
        if(lower(s.careersUrl().getHost()).endsWith("careers-page.com")){
            for(Element a:d.select("a[href~=(?i)^/?jobs/[0-9a-f-]{36}$]")){String title=a.text().trim(),detail=a.absUrl("href");if(title.length()<4||detail.isBlank()||!seen.add(detail))continue;String surrounding=a.parent()==null?title:a.parent().text();String apply=detail.replaceFirst("/+$","")+"/apply";out.add(new CollectedJob(sourceName(s),external(s,AuthService.hash(detail)),limit(title,240),s.companyName(),findLocation(surrounding),null,remote(surrounding),null,null,limit(surrounding,12000),null,apply,detail,null,"SA"));}
            return result(out,r,true);
        }
        for(Element a:d.select("a[href]")){if(out.size()>=100)break;String title=a.text().trim(),url=a.absUrl("href"),path=a.attr("href").toLowerCase(Locale.ROOT);if(title.length()<4||url.isBlank()||!(path.contains("/job")||path.contains("position")||path.contains("vacanc"))||!seen.add(url))continue;String surrounding=a.parent()==null?title:a.parent().text();out.add(new CollectedJob(sourceName(s),external(s,AuthService.hash(url)),limit(title,240),s.companyName(),findLocation(surrounding),null,remote(surrounding),null,null,limit(surrounding,12000),null,url,s.careersUrl().toString(),null,null));}
        return result(out,r,false);
    }
    private static String lower(String value){return value==null?"":value.toLowerCase(Locale.ROOT);}
    private static String findLocation(String v){Matcher m=Pattern.compile("(?i)Saudi Arabia|Riyadh|Jeddah|Dammam|Khobar|Makkah|Madinah|NEOM").matcher(v);return m.find()?m.group():null;}
}

@Component
class BrowserFallbackCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    private final RestClient rest;
    private final String rendererUrl;
    BrowserFallbackCollector(SafeSourceHttpClient h,ObjectMapper m,RestClient rest,
            @Value("${app.jobs.browser-renderer-url:}") String rendererUrl){super(h,m);this.rest=rest;this.rendererUrl=rendererUrl.trim();}
    public boolean supports(RegisteredJobSource s){return ("BROWSER_FALLBACK".equals(s.sourceType())||"GENERIC_HTML".equals(s.sourceType())||"JSON_LD".equals(s.sourceType()))&&!rendererUrl.isBlank();}
    public int priority(){return 100;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        if(!http.robotsAllowed(s.careersUrl()))throw new SourceHttpException(403,false,"ROBOTS_DISALLOWED");
        String endpoint=rendererUrl+"?url="+URLEncoder.encode(s.careersUrl().toString(),StandardCharsets.UTF_8);
        String html=rest.get().uri(endpoint).retrieve().onStatus(HttpStatusCode::isError,(req,res)->{throw new SourceHttpException(res.getStatusCode().value(),true,"RENDERER_FAILURE");}).body(String.class);
        if(html==null||html.length()>5_000_000)throw new SourceHttpException(413,false,"RENDERED_RESPONSE_TOO_LARGE");Document d=Jsoup.parse(html,s.careersUrl().toString());List<CollectedJob> out=new ArrayList<>();
        for(Element e:d.select("[data-job-id], article.job, li.job")){Element a=e.selectFirst("a[href]");if(a==null)continue;String url=a.absUrl("href"),title=a.text();if(url.isBlank()||title.isBlank())continue;out.add(new CollectedJob(sourceName(s),external(s,Objects.toString(e.attr("data-job-id"),AuthService.hash(url))),title,s.companyName(),e.text(),null,remote(e.text()),null,null,e.text(),null,url,s.careersUrl().toString(),null,null));}
        return new SourceCollectionResult(out,false,200,html.length(),null,null,AuthService.hash(html),false);
    }
}

class RegistryJobSource implements JobSourceClient {
    private final JobSourceRegistry registry;
    private final List<RegisteredSourceCollector> collectors;
    private final JdbcClient jdbc;
    private final int concurrency;
    private final int batchLimit;
    private final int perDomainConcurrency;
    private final Map<String,Semaphore> domainLimits = new ConcurrentHashMap<>();
    private volatile Map<UUID,CompletedSource> completed = Map.of();

    RegistryJobSource(JobSourceRegistry registry,List<RegisteredSourceCollector> collectors,JdbcClient jdbc,
            @Value("${app.jobs.registry-concurrency:8}") int concurrency,
            @Value("${app.jobs.registry-batch-limit:100}") int batchLimit,
            @Value("${app.jobs.registry-per-domain-concurrency:2}") int perDomainConcurrency){this.registry=registry;this.collectors=collectors.stream().sorted(Comparator.comparingInt(RegisteredSourceCollector::priority)).toList();this.jdbc=jdbc;this.concurrency=Math.max(1,Math.min(concurrency,32));this.batchLimit=Math.max(1,batchLimit);this.perDomainConcurrency=Math.max(1,Math.min(perDomainConcurrency,4));}
    public String sourceName(){return "SOURCE_REGISTRY";}
    public Duration refreshInterval(){return Duration.ofMinutes(5);}
    public void forceRefresh(){registry.forceAllDue();}
    public List<CollectedJob> fetch(){List<RegisteredJobSource> due=registry.due(batchLimit);if(due.isEmpty()){completed=Map.of();return List.of();}Map<UUID,CompletedSource> done=new java.util.concurrent.ConcurrentHashMap<>();List<CollectedJob> all=java.util.Collections.synchronizedList(new ArrayList<>());
        try(var executor=Executors.newFixedThreadPool(concurrency,Thread.ofVirtual().factory())){List<java.util.concurrent.Future<?>> futures=new ArrayList<>();for(RegisteredJobSource source:due)futures.add(executor.submit(()->fetchOne(source,done,all)));for(var f:futures)try{f.get();}catch(Exception ignored){}}completed=Map.copyOf(done);return List.copyOf(all);}
    private void fetchOne(RegisteredJobSource s,Map<UUID,CompletedSource> done,List<CollectedJob> all){Instant start=Instant.now();Semaphore limit=domainLimits.computeIfAbsent(s.careersUrl().getHost().toLowerCase(Locale.ROOT),ignored->new Semaphore(perDomainConcurrency));boolean acquired=false;try{limit.acquire();acquired=true;List<RegisteredSourceCollector> matching=collectors.stream().filter(c->c.supports(s)).toList();if(matching.isEmpty())throw new SourceHttpException(0,false,"NO_COLLECTOR");SourceCollectionResult result=null;for(int i=0;i<matching.size();i++){result=matching.get(i).collect(s,i==0?new SyncCursor(s.etag(),s.lastModified(),s.responseChecksum()):null);if(result.notModified()||!result.jobs().isEmpty()||result.completeSnapshot())break;}registry.success(s,result,start,Instant.now());all.addAll(result.jobs());done.put(s.id(),new CompletedSource(s,result.completeSnapshot(),result.jobs().stream().map(CollectedJob::dedupKey).collect(java.util.stream.Collectors.toSet())));}catch(InterruptedException e){Thread.currentThread().interrupt();registry.failure(s,start,new SourceHttpException(0,true,"INTERRUPTED"));}catch(RuntimeException e){registry.failure(s,start,e);}finally{if(acquired)limit.release();}}
    @Transactional
    public void afterSuccessfulCollection(Instant started,List<CollectedJob> accepted){for(CompletedSource item:completed.values()){RegisteredJobSource s=item.source();for(String key:item.keys())jdbc.sql("""
                UPDATE jobs SET source_id=:sourceId,
                    source_quality=CASE WHEN :type IN ('GREENHOUSE','LEVER','ASHBY','SMARTRECRUITERS','WORKABLE','BREEZY','RECRUITEE') THEN 1 ELSE 2 END,
                    is_direct_employer=true,
                    canonical_application_url=apply_url, original_source_url=source_url,
                    content_fingerprint=encode(digest(lower(coalesce(description,'')),'sha256'),'hex'),
                    last_verified_at=now(), missing_observations=0, collection_status='COMPLETE'
                WHERE dedup_key=:key
                """).param("sourceId",s.id()).param("type",s.sourceType()).param("key",key).update();if(item.complete()&&!item.keys().isEmpty()){jdbc.sql("""
                    UPDATE jobs SET missing_observations=missing_observations+1,
                        status=CASE WHEN missing_observations+1 >= 2 THEN 'EXPIRED' ELSE 'PENDING_RECHECK' END,
                        last_verified_at=now()
                    WHERE source_id=:sourceId AND status IN ('ACTIVE','PENDING_RECHECK')
                      AND NOT (dedup_key = ANY(:keys))
                    """).param("sourceId",s.id()).param("keys",item.keys().toArray(String[]::new)).update();}}
    }
    private record CompletedSource(RegisteredJobSource source,boolean complete,Set<String> keys){}
}
@Component
class WorkableCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    WorkableCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "WORKABLE".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://"+s.boardIdentifier()+".workable.com/spi/v3/jobs");SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);
        List<CollectedJob> out=new ArrayList<>();for(JsonNode j:read(r.body()).path("jobs")){String id=required(j,"shortcode"),loc=join(text(j.path("location"),"city"),text(j.path("location"),"region"),text(j.path("location"),"countryName")),url=required(j,"url");
            out.add(new CollectedJob(sourceName(s),external(s,id),required(j,"title"),s.companyName(),loc,text(j,"type"),j.path("location").path("telecommuting").asBoolean(false)||remote(loc),null,text(j,"department"),null,null,url,url,instant(text(j,"published_on")),text(j.path("location"),"countryName")));}
        return result(out,r,true);
    }
}

@Component
class BreezyCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    BreezyCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "BREEZY".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://"+s.boardIdentifier()+".breezy.hr/json");SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);
        List<CollectedJob> out=new ArrayList<>();for(JsonNode j:read(r.body())){String id=required(j,"id"),url=required(j,"url");JsonNode l=j.path("locations").isArray()&&!j.path("locations").isEmpty()?j.path("locations").get(0):j.path("location");String loc=join(text(l,"city"),text(l.path("country"),"name"));
            out.add(new CollectedJob(sourceName(s),external(s,id),required(j,"name"),s.companyName(),loc,text(j.path("type"),"name"),l.path("is_remote").asBoolean(false)||remote(loc),null,text(j,"department"),null,null,url,url,instant(text(j,"published_date")),text(l.path("country"),"name")));}
        return result(out,r,true);
    }
}

@Component
class RecruiteeCollector extends JsonSourceCollector implements RegisteredSourceCollector {
    RecruiteeCollector(SafeSourceHttpClient h,ObjectMapper m){super(h,m);}
    public boolean supports(RegisteredJobSource s){return "RECRUITEE".equals(s.sourceType());}
    public int priority(){return 1;}
    public SourceCollectionResult collect(RegisteredJobSource s,SyncCursor c){
        URI u=URI.create("https://"+s.boardIdentifier()+".recruitee.com/api/offers");SourceHttpResponse r=http.get(u,c);if(r.status()==304)return result(List.of(),r,true);
        List<CollectedJob> out=new ArrayList<>();for(JsonNode j:read(r.body()).path("offers")){String id=required(j,"id"),url=required(j,"careers_url"),loc=text(j,"location");
            out.add(new CollectedJob(sourceName(s),external(s,id),required(j,"title"),s.companyName(),loc,text(j,"employment_type_code"),j.path("remote").asBoolean(false)||remote(loc),null,text(j,"department"),null,null,url,url,instant(text(j,"created_at")),text(j,"country_code")));}
        return result(out,r,true);
    }
}
