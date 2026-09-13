package com.talentshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

record JobView(UUID id, String source, String title, String company, String location, String countryCode,
        String employmentType, boolean remote, String salary, String category,
        String description, String requirements, String applyUrl, String sourceUrl,
        Instant postedAt, Instant collectedAt) {}
record JobPage(List<JobView> items, long total, int page, int size) {}
record JobCount(long count, int targetMinimum, boolean targetMet, Instant lastCollectedAt) {}
record CollectionSummary(int inserted, int updated, int rejected, long activeJobs, int targetMinimum,
        boolean targetMet, List<String> failedSources, Instant startedAt, Instant finishedAt) {}
record CollectionRequest(boolean accepted, boolean queued, String message) {}
record AgentIngestionSummary(int inserted, int updated, int rejected) {}
record AgentJobSubmission(@NotBlank @Size(max=60) String source,
        @NotBlank @Size(max=180) String externalId,
        @NotBlank @Size(max=240) String title,
        @NotBlank @Size(max=200) String company,
        @NotBlank @Size(max=200) String location,
        @Size(max=80) String employmentType, boolean remote,
        @Size(max=160) String salary, @Size(max=120) String category,
        @Size(max=12_000) String description, @Size(max=12_000) String requirements,
        @NotBlank String applyUrl, String sourceUrl, Instant postedAt) {}
record AgentIngestionRequest(@NotNull @Size(min=1, max=500) List<@Valid AgentJobSubmission> jobs) {}

record CollectedJob(String source, String externalId, String title, String company, String location,
        String employmentType, boolean remote, String salary, String category, String description,
        String requirements, String applyUrl, String sourceUrl, Instant postedAt, String countryCode) {
    CollectedJob {
        source = limited(required(source, "source"), 60);
        externalId = limited(required(externalId, "externalId"), 180);
        title = limited(required(title, "title"), 240);
        company = limited(required(company, "company"), 200);
        applyUrl = safeUrl(applyUrl);
        sourceUrl = sourceUrl == null || sourceUrl.isBlank() ? applyUrl : safeUrl(sourceUrl);
        location = limited(clean(location), 200);
        employmentType = limited(clean(employmentType), 80);
        salary = limited(clean(salary), 160);
        category = limited(clean(category), 120);
        description = cleanLong(description);
        requirements = cleanLong(requirements);
        countryCode = limited(clean(countryCode), 2);
        if (countryCode != null) countryCode = countryCode.toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(field + " is required");
        return cleaned;
    }

    private static String safeUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("applyUrl is required");
        URI uri = URI.create(value.trim());
        if (!Set.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTP(S) apply URLs are allowed");
        }
        return uri.toString();
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String cleanLong(String value) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        return cleaned.length() > 12_000 ? cleaned.substring(0, 12_000) : cleaned;
    }

    private static String limited(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    String dedupKey() {
        return AuthService.hash(String.join("|", title.toLowerCase(Locale.ROOT), company.toLowerCase(Locale.ROOT),
                Objects.toString(location, "").toLowerCase(Locale.ROOT)));
    }
}

interface JobSourceClient {
    String sourceName();
    List<CollectedJob> fetch();
    default boolean completeSnapshot() { return false; }
    default Duration refreshInterval() { return Duration.ofMinutes(30); }
    default void forceRefresh() { }
    default void afterSuccessfulCollection(Instant collectionStarted, List<CollectedJob> acceptedJobs) { }
}

@Component
class ArbeitnowJobSource implements JobSourceClient {
    private static final String ENDPOINT = "https://www.arbeitnow.com/api/job-board-api";
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final int maxPages;
    private final Duration refreshInterval;

    ArbeitnowJobSource(RestClient restClient, ObjectMapper mapper,
            @Value("${app.jobs.max-pages-per-source:3}") int maxPages,
            @Value("${app.jobs.arbeitnow-refresh-minutes:15}") long refreshMinutes) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.maxPages = Math.max(1, Math.min(maxPages, 10));
        this.refreshInterval = Duration.ofMinutes(Math.max(5, refreshMinutes));
    }

    public String sourceName() { return "ARBEITNOW"; }
    public Duration refreshInterval() { return refreshInterval; }

    public List<CollectedJob> fetch() {
        List<CollectedJob> jobs = new ArrayList<>();
        String next = ENDPOINT;
        for (int page = 0; page < maxPages && next != null; page++) {
            String body = restClient.get().uri(next).retrieve().body(String.class);
            try {
                JsonNode root = mapper.readTree(body);
                for (JsonNode item : root.path("data")) {
                    jobs.add(new CollectedJob(sourceName(),
                            text(item, "slug", text(item, "url", UUID.randomUUID().toString())),
                            text(item, "title", "Untitled role"),
                            text(item, "company_name", "Unknown company"),
                            text(item, "location", "Not specified"),
                            firstArrayValue(item.path("job_types")), item.path("remote").asBoolean(false),
                            null, firstArrayValue(item.path("tags")), text(item, "description", null),
                            joinArray(item.path("tags")), text(item, "url", null), text(item, "url", null),
                            parseInstant(text(item, "created_at", null)), null));
                }
                String candidate = root.path("links").path("next").asText("");
                next = candidate.isBlank() ? null : candidate;
            } catch (Exception exception) {
                throw new JobSourceException(sourceName(), exception);
            }
        }
        return jobs;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }
    private static String firstArrayValue(JsonNode node) {
        return node.isArray() && !node.isEmpty() ? node.get(0).asText(null) : null;
    }
    private static String joinArray(JsonNode node) {
        if (!node.isArray()) return null;
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return String.join(", ", values);
    }
    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }
}

@Component
class RemotiveJobSource implements JobSourceClient {
    private static final String ENDPOINT = "https://remotive.com/api/remote-jobs";
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final Duration refreshInterval;

    RemotiveJobSource(RestClient restClient, ObjectMapper mapper,
            @Value("${app.jobs.remotive-refresh-minutes:30}") long refreshMinutes) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.refreshInterval = Duration.ofMinutes(Math.max(10, refreshMinutes));
    }

    public String sourceName() { return "REMOTIVE"; }
    public boolean completeSnapshot() { return true; }
    public Duration refreshInterval() { return refreshInterval; }

    public List<CollectedJob> fetch() {
        try {
            String body = restClient.get().uri(ENDPOINT).retrieve().body(String.class);
            JsonNode root = mapper.readTree(body);
            List<CollectedJob> jobs = new ArrayList<>();
            for (JsonNode item : root.path("jobs")) {
                String url = text(item, "url", null);
                jobs.add(new CollectedJob(sourceName(), text(item, "id", url),
                        text(item, "title", "Untitled role"), text(item, "company_name", "Unknown company"),
                        text(item, "candidate_required_location", "Remote"), text(item, "job_type", null),
                        true, text(item, "salary", null), text(item, "category", null),
                        text(item, "description", null), text(item, "tags", null), url, url,
                        parseInstant(text(item, "publication_date", null)), null));
            }
            return jobs;
        } catch (Exception exception) {
            throw new JobSourceException(sourceName(), exception);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(entry -> values.add(entry.asText()));
            return values.isEmpty() ? fallback : String.join(", ", values);
        }
        String text = value.asText("").trim();
        return text.isBlank() ? fallback : text;
    }
    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }
}

record SourceRequestState(boolean initialBackfillCompleted, int requestsUsed, int requestBudget) {}

@Repository
class SourceRequestBudget {
    private final JdbcClient jdbc;

    SourceRequestBudget(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public synchronized SourceRequestState prepare(String source, String keyFingerprint, int requestBudget,
            int resetDays) {
        record StoredState(boolean backfillCompleted, int requestsUsed, String fingerprint, Instant resetAt) {}
        Optional<StoredState> existing = jdbc.sql("""
                SELECT initial_backfill_completed, requests_used, api_key_fingerprint, budget_reset_at
                FROM job_source_state WHERE source=:source
                """).param("source", source)
                .query((rs, rowNum) -> new StoredState(rs.getBoolean("initial_backfill_completed"),
                        rs.getInt("requests_used"), rs.getString("api_key_fingerprint"),
                        rs.getObject("budget_reset_at", OffsetDateTime.class).toInstant())).optional();
        if (existing.isPresent() && Objects.equals(existing.get().fingerprint(), keyFingerprint)
                && existing.get().resetAt().isAfter(Instant.now())) {
            jdbc.sql("UPDATE job_source_state SET request_budget=:budget, updated_at=now() WHERE source=:source")
                    .param("budget", requestBudget).param("source", source).update();
            return new SourceRequestState(existing.get().backfillCompleted(), existing.get().requestsUsed(),
                    requestBudget);
        }
        jdbc.sql("""
                INSERT INTO job_source_state(source, api_key_fingerprint, initial_backfill_completed,
                    requests_used, request_budget, budget_reset_at, updated_at)
                VALUES (:source, :fingerprint, false, 0, :budget, now() + make_interval(days => :resetDays), now())
                ON CONFLICT (source) DO UPDATE SET api_key_fingerprint=EXCLUDED.api_key_fingerprint,
                    initial_backfill_completed=false, requests_used=0, request_budget=EXCLUDED.request_budget,
                    budget_reset_at=EXCLUDED.budget_reset_at, updated_at=now()
                """).param("source", source).param("fingerprint", keyFingerprint)
                .param("budget", requestBudget).param("resetDays", resetDays).update();
        return new SourceRequestState(false, 0, requestBudget);
    }

    @Transactional
    public synchronized boolean tryAcquire(String source) {
        return jdbc.sql("""
                UPDATE job_source_state SET requests_used=requests_used+1, last_request_at=now(), updated_at=now()
                WHERE source=:source AND requests_used < request_budget
                """).param("source", source).update() == 1;
    }

    void markInitialBackfillCompleted(String source) {
        jdbc.sql("UPDATE job_source_state SET initial_backfill_completed=true, updated_at=now() WHERE source=:source")
                .param("source", source).update();
    }
}

@Component
class JoobleJobSource implements JobSourceClient {
    private static final String ENDPOINT = "https://jooble.org/api/{apiKey}";
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final SourceRequestBudget requestBudget;
    private final String apiKey;
    private final int backfillPages;
    private final int refreshPages;
    private final int budgetLimit;
    private final int budgetResetDays;
    private final Duration refreshInterval;
    private final boolean enabled;

    JoobleJobSource(RestClient restClient, ObjectMapper mapper, SourceRequestBudget requestBudget,
            @Value("${app.jobs.jooble-api-key:}") String apiKey,
            @Value("${app.jobs.jooble-backfill-pages:100}") int backfillPages,
            @Value("${app.jobs.jooble-refresh-pages:1}") int refreshPages,
            @Value("${app.jobs.jooble-request-budget:500}") int budgetLimit,
            @Value("${app.jobs.jooble-budget-reset-days:36500}") int budgetResetDays,
            @Value("${app.jobs.jooble-refresh-minutes:180}") long refreshMinutes,
            @Value("${app.jobs.credential-collectors-enabled:false}") boolean enabled) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.requestBudget = requestBudget;
        this.apiKey = apiKey.trim();
        this.backfillPages = Math.max(1, Math.min(backfillPages, 200));
        this.refreshPages = Math.max(1, Math.min(refreshPages, 10));
        this.budgetLimit = Math.max(1, budgetLimit);
        this.budgetResetDays = Math.max(1, budgetResetDays);
        this.refreshInterval = Duration.ofMinutes(Math.max(30, refreshMinutes));
        this.enabled = enabled;
    }

    public String sourceName() { return "JOOBLE"; }
    public Duration refreshInterval() { return refreshInterval; }

    public List<CollectedJob> fetch() {
        if (!enabled || apiKey.isBlank()) return List.of();
        SourceRequestState state = requestBudget.prepare(sourceName(), AuthService.hash(apiKey), budgetLimit,
                budgetResetDays);
        boolean initialBackfill = !state.initialBackfillCompleted();
        int maxPages = initialBackfill ? backfillPages : refreshPages;
        List<CollectedJob> jobs = new ArrayList<>();
        boolean requested = false;
        for (int page = 1; page <= maxPages; page++) {
            try {
                if (!requestBudget.tryAcquire(sourceName())) break;
                requested = true;
                String body = restClient.post().uri(ENDPOINT, apiKey)
                        .body(Map.of("keywords", "", "location", "Saudi Arabia", "page", page,
                                "ResultOnPage", 100, "SearchMode", 0))
                        .retrieve().body(String.class);
                JsonNode root = mapper.readTree(body);
                JsonNode entries = root.path("jobs");
                if (!entries.isArray() || entries.isEmpty()) break;
                for (JsonNode item : entries) {
                    String url = text(item, "link", null);
                    jobs.add(new CollectedJob(sourceName(), text(item, "id", url),
                            text(item, "title", "Untitled role"), text(item, "company", "Unknown company"),
                            text(item, "location", "Saudi Arabia"), text(item, "type", null), false,
                            text(item, "salary", null), null, text(item, "snippet", null), null,
                            url, url, parseInstant(text(item, "updated", null)), "SA"));
                }
                if (jobs.size() >= root.path("totalCount").asLong(Long.MAX_VALUE)) break;
            } catch (Exception exception) {
                if (jobs.isEmpty()) throw new JobSourceException(sourceName(), exception);
                break;
            }
        }
        if (initialBackfill && requested) requestBudget.markInitialBackfillCompleted(sourceName());
        return jobs;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) { return null; }
    }
}

@Component
class SaudiJobPolicy {
    private static final List<String> SAUDI_MARKERS = List.of(
            "saudi arabia", "saudi", "riyadh", "jeddah", "makkah", "mecca", "madinah", "medina",
            "dammam", "khobar", "dhahran", "tabuk", "jubail", "yanbu", "abha", "taif", "qassim",
            "al ula", "neom", "\u0627\u0644\u0633\u0639\u0648\u062f\u064a\u0629",
            "\u0627\u0644\u0631\u064a\u0627\u0636", "\u062c\u062f\u0629", "\u0645\u0643\u0629",
            "\u0627\u0644\u0645\u062f\u064a\u0646\u0629", "\u0627\u0644\u062f\u0645\u0627\u0645",
            "\u0627\u0644\u062e\u0628\u0631", "\u0627\u0644\u0638\u0647\u0631\u0627\u0646",
            "\u062a\u0628\u0648\u0643", "\u0627\u0644\u062c\u0628\u064a\u0644",
            "\u064a\u0646\u0628\u0639", "\u0623\u0628\u0647\u0627", "\u0627\u0644\u0637\u0627\u0626\u0641",
            "\u0627\u0644\u0642\u0635\u064a\u0645", "\u0627\u0644\u0639\u0644\u0627");
            
    private static final List<String> FOREIGN_MARKERS = List.of(
            "cairo", "egypt", "dubai", "uae", "united arab emirates", "abu dhabi", 
            "amman", "jordan", "lebanon", "beirut", "kuwait", "qatar", "doha", "bahrain", "manama",
            "oman", "muscat", "india", "pakistan", "london", "uk", "united kingdom", "us", "usa", "united states"
    );

    boolean accepts(CollectedJob job) {
        String location = Objects.toString(job.location(), "").toLowerCase(Locale.ROOT);
        String title = Objects.toString(job.title(), "").toLowerCase(Locale.ROOT);
        
        if (FOREIGN_MARKERS.stream().anyMatch(marker -> location.contains(marker) || title.contains(marker))) return false;

        if ("SA".equalsIgnoreCase(job.countryCode())) return true;
        if (SAUDI_MARKERS.stream().anyMatch(location::contains)) return true;
        return false;
    }

    private static boolean englishOrArabic(CollectedJob job) {
        String value = String.join(" ", Objects.toString(job.title(), ""), Objects.toString(job.description(), ""),
                Objects.toString(job.requirements(), "")).toLowerCase(Locale.ROOT);
        long arabic = value.chars().filter(character -> character >= 0x0600 && character <= 0x06ff).count();
        if (arabic >= 3) return true;
        int englishSignals = 0;
        for (String word : List.of(" the ", " and ", " you ", " our ", " work ", " role ", " team ",
                " experience ", " skills ", " requirements ", " responsibilities ", " engineer "))
            if ((" " + value + " ").contains(word)) englishSignals++;
        if (englishSignals >= 3) return true;
        // Public ATS list APIs (notably Workday) often omit the description.
        // A normal Latin-script role title is still a reliable English signal.
        String title = Objects.toString(job.title(), "").trim();
        long latinLetters = title.chars().filter(character ->
                (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')).count();
        return latinLetters >= 4 && title.matches(".*[A-Za-z]{2,}.*");
    }
}

class JobSourceException extends RuntimeException {
    JobSourceException(String source, Throwable cause) { super("Job source failed: " + source, cause); }
}

@Repository
class JobRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final int targetMinimum;
    private final int maxAgeDays;
    JobRepository(JdbcClient jdbc, ObjectMapper mapper, @Value("${app.jobs.target-minimum:4000}") int targetMinimum,
            @Value("${app.jobs.max-age-days:45}") int maxAgeDays) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.targetMinimum = Math.max(1, targetMinimum);
        this.maxAgeDays = Math.max(1, maxAgeDays);
    }

    @Transactional
    BatchUpsertResult upsertBatch(List<CollectedJob> input) {
        Map<String,CollectedJob> unique = new LinkedHashMap<>();
        for (CollectedJob job : input) {
            unique.putIfAbsent(job.source() + "\u0000" + job.externalId(), job);
        }
        int inserted = 0, updated = 0;
        for (CollectedJob job : unique.values()) {
            if (upsert(job) == UpsertResult.INSERTED) inserted++; else updated++;
        }
        return new BatchUpsertResult(inserted, updated);
    }

    BatchUpsertResult upsertBatchBulkLegacy(List<CollectedJob> input) {
        Map<String,CollectedJob> deduplicated = new LinkedHashMap<>();
        for (CollectedJob job : input) deduplicated.putIfAbsent(job.dedupKey(), job);
        List<CollectedJob> uniqueInput = List.copyOf(deduplicated.values());
        int inserted = 0, updated = 0;
        for (int start = 0; start < uniqueInput.size(); start += 200) {
            List<CollectedJob> batch = uniqueInput.subList(start, Math.min(uniqueInput.size(), start + 200));
            List<Map<String,Object>> rows = new ArrayList<>();
            for (CollectedJob job : batch) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("source", job.source()); row.put("externalId", job.externalId());
                row.put("title", job.title()); row.put("company", job.company()); row.put("location", job.location());
                row.put("countryCode", job.countryCode()); row.put("employmentType", job.employmentType());
                row.put("remote", job.remote()); row.put("salary", job.salary()); row.put("category", job.category());
                row.put("description", job.description()); row.put("requirements", job.requirements());
                row.put("applyUrl", job.applyUrl()); row.put("sourceUrl", job.sourceUrl());
                row.put("postedAt", job.postedAt() == null ? null : job.postedAt().toString());
                row.put("dedupKey", job.dedupKey());
                rows.add(row);
            }
            String payload;
            try { payload = mapper.writeValueAsString(rows); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Could not serialize job batch", exception);
            }
            List<Boolean> created = jdbc.sql("""
                    WITH incoming AS (
                        SELECT * FROM jsonb_to_recordset(CAST(:payload AS jsonb)) AS x(
                            source text, "externalId" text, title text, company text, location text,
                            "countryCode" text, "employmentType" text, remote boolean, salary text,
                            category text, description text, requirements text, "applyUrl" text,
                            "sourceUrl" text, "postedAt" text, "dedupKey" text)
                    )
                    INSERT INTO jobs(source,external_id,title,company,location,country_code,employment_type,remote,
                        salary,category,description,requirements,apply_url,source_url,posted_at,collected_at,
                        last_seen_at,expires_at,dedup_key,saudi_relevant,status,canonical_application_url,
                        content_fingerprint,source_quality,is_direct_employer,original_source_url,
                        last_verified_at,collection_status,enrichment_status,missing_observations)
                    SELECT source,"externalId",title,company,location,"countryCode","employmentType",remote,
                        salary,category,description,requirements,"applyUrl","sourceUrl",
                        CASE WHEN "postedAt" IS NULL THEN NULL ELSE CAST("postedAt" AS timestamptz) END,
                        now(),now(),now()+make_interval(days => :maxAgeDays),"dedupKey",true,'ACTIVE',"applyUrl",
                        encode(digest(lower(coalesce(description,'')),'sha256'),'hex'),
                        CASE WHEN source IN ('DIRECT_GREENHOUSE','DIRECT_LEVER','DIRECT_ASHBY','DIRECT_SMARTRECRUITERS') THEN 1
                             WHEN source LIKE 'DIRECT_%' THEN 2 WHEN source IN ('JOOBLE','ARBEITNOW','REMOTIVE') THEN 3 ELSE 5 END,
                        source LIKE 'DIRECT_%',"sourceUrl",now(),'COMPLETE','PENDING',0
                    FROM incoming
                    ON CONFLICT (dedup_key) DO UPDATE SET
                        title=EXCLUDED.title, company=EXCLUDED.company, location=EXCLUDED.location,
                        country_code=EXCLUDED.country_code, employment_type=EXCLUDED.employment_type,
                        remote=EXCLUDED.remote, salary=EXCLUDED.salary, category=EXCLUDED.category,
                        description=EXCLUDED.description, requirements=EXCLUDED.requirements,
                        apply_url=CASE WHEN EXCLUDED.source_quality <= jobs.source_quality THEN EXCLUDED.apply_url ELSE jobs.apply_url END,
                        source_url=CASE WHEN EXCLUDED.source_quality <= jobs.source_quality THEN EXCLUDED.source_url ELSE jobs.source_url END,
                        source=CASE WHEN EXCLUDED.source_quality <= jobs.source_quality THEN EXCLUDED.source ELSE jobs.source END,
                        external_id=CASE WHEN EXCLUDED.source_quality <= jobs.source_quality THEN EXCLUDED.external_id ELSE jobs.external_id END,
                        source_quality=LEAST(jobs.source_quality,EXCLUDED.source_quality),
                        is_direct_employer=jobs.is_direct_employer OR EXCLUDED.is_direct_employer,
                        posted_at=COALESCE(EXCLUDED.posted_at,jobs.posted_at), collected_at=now(),last_seen_at=now(),
                        last_verified_at=now(),expires_at=EXCLUDED.expires_at,status='ACTIVE',saudi_relevant=true,
                        canonical_application_url=CASE WHEN EXCLUDED.source_quality <= jobs.source_quality THEN EXCLUDED.canonical_application_url ELSE jobs.canonical_application_url END,
                        content_fingerprint=EXCLUDED.content_fingerprint,missing_observations=0,
                        enrichment_status=CASE WHEN jobs.content_fingerprint<>EXCLUDED.content_fingerprint THEN 'PENDING' ELSE jobs.enrichment_status END
                    RETURNING (xmax=0)
                    """).param("payload", payload).param("maxAgeDays", maxAgeDays).query(Boolean.class).list();
            inserted += (int) created.stream().filter(Boolean::booleanValue).count();
            updated += created.size() - (int) created.stream().filter(Boolean::booleanValue).count();
        }
        return new BatchUpsertResult(inserted, updated);
    }

    UpsertResult upsert(CollectedJob job) {
        String dedupKey = job.dedupKey();
        int sourceQuality = List.of("DIRECT_GREENHOUSE","DIRECT_LEVER","DIRECT_ASHBY","DIRECT_SMARTRECRUITERS").contains(job.source()) ? 1
                : job.source().startsWith("DIRECT_") ? 2
                : List.of("JOOBLE", "ARBEITNOW", "REMOTIVE").contains(job.source()) ? 3 : 5;
        Optional<UUID> existing = jdbc.sql("""
                SELECT id FROM jobs
                WHERE (source=:source AND external_id=:externalId) OR dedup_key=:dedupKey OR lower(apply_url)=lower(:applyUrl)
                ORDER BY CASE WHEN source=:source AND external_id=:externalId THEN 0
                              WHEN lower(apply_url)=lower(:applyUrl) THEN 1 ELSE 2 END
                LIMIT 1
                """).param("source", job.source()).param("externalId", job.externalId())
                .param("dedupKey", dedupKey).param("applyUrl",job.applyUrl()).query(UUID.class).optional();
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.now().plus(java.time.Duration.ofDays(maxAgeDays)), java.time.ZoneOffset.UTC);
        if (existing.isPresent()) {
            jdbc.sql("""
                    UPDATE jobs SET title=:title, company=:company, location=:location, country_code=:countryCode,
                        employment_type=:employmentType, remote=:remote, salary=:salary, category=:category,
                        description=:description, requirements=:requirements,
                        apply_url=CASE WHEN :sourceQuality <= source_quality THEN :applyUrl ELSE apply_url END,
                        source_url=CASE WHEN :sourceQuality <= source_quality THEN :sourceUrl ELSE source_url END,
                        canonical_application_url=CASE WHEN :sourceQuality <= source_quality THEN :applyUrl ELSE canonical_application_url END,
                        original_source_url=CASE WHEN :sourceQuality <= source_quality THEN :sourceUrl ELSE original_source_url END,
                        source_quality=LEAST(source_quality,:sourceQuality),
                        is_direct_employer=is_direct_employer OR :isDirect,
                        content_fingerprint=encode(digest(lower(coalesce(:description,'')),'sha256'),'hex'),
                        enrichment_status=CASE WHEN content_fingerprint<>encode(digest(lower(coalesce(:description,'')),'sha256'),'hex') THEN 'PENDING' ELSE enrichment_status END,
                        last_verified_at=now(), missing_observations=0,
                        posted_at=COALESCE(:postedAt, posted_at), collected_at=now(),
                        last_seen_at=now(), expires_at=:expiresAt,
                        dedup_key=CASE WHEN EXISTS(SELECT 1 FROM jobs duplicate WHERE duplicate.dedup_key=:dedupKey AND duplicate.id<>:id) THEN dedup_key ELSE :dedupKey END,
                        saudi_relevant=true, status='ACTIVE'
                    WHERE id=:id
                    """).param("id", existing.get()).param("title", job.title()).param("company", job.company())
                    .param("location", job.location()).param("countryCode", job.countryCode(), Types.CHAR)
                    .param("employmentType", job.employmentType()).param("remote", job.remote())
                    .param("salary", job.salary()).param("category", job.category())
                    .param("description", job.description()).param("requirements", job.requirements())
                    .param("applyUrl", job.applyUrl()).param("sourceUrl", job.sourceUrl())
                    .param("sourceQuality", sourceQuality).param("isDirect", sourceQuality == 1)
                    .param("postedAt", sqlInstant(job.postedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("expiresAt", expiresAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("dedupKey", dedupKey).update();
            return UpsertResult.UPDATED;
        }
        jdbc.sql("""
                INSERT INTO jobs(source, external_id, title, company, location, country_code, employment_type, remote,
                    salary, category, description, requirements, apply_url, source_url, posted_at, collected_at,
                    last_seen_at, expires_at, dedup_key, saudi_relevant, status, canonical_application_url,
                    content_fingerprint, source_quality, is_direct_employer, original_source_url,
                    last_verified_at, collection_status, enrichment_status, missing_observations)
                VALUES (:source, :externalId, :title, :company, :location, :countryCode, :employmentType, :remote,
                    :salary, :category, :description, :requirements, :applyUrl, :sourceUrl,
                    :postedAt, now(), now(), :expiresAt, :dedupKey, true, 'ACTIVE', :applyUrl,
                    encode(digest(lower(coalesce(:description,'')),'sha256'),'hex'), :sourceQuality, :isDirect,
                    :sourceUrl, now(), 'COMPLETE', 'PENDING', 0)
                """)
                .param("source", job.source()).param("externalId", job.externalId()).param("title", job.title())
                .param("company", job.company()).param("location", job.location())
                .param("countryCode", job.countryCode(), Types.CHAR)
                .param("employmentType", job.employmentType())
                .param("remote", job.remote()).param("salary", job.salary()).param("category", job.category())
                .param("description", job.description()).param("requirements", job.requirements())
                .param("applyUrl", job.applyUrl()).param("sourceUrl", job.sourceUrl())
                .param("sourceQuality", sourceQuality).param("isDirect", sourceQuality == 1)
                .param("postedAt", sqlInstant(job.postedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", expiresAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("dedupKey", dedupKey).update();
        return UpsertResult.INSERTED;
    }

    private static OffsetDateTime sqlInstant(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

    JobPage search(String keyword, String location, String type, String source, Boolean remote, int page, int size) {
        String k = normalize(keyword), l = normalize(location), t = normalize(type), s = normalize(source);
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        String where = """
                FROM jobs WHERE status='ACTIVE'
                  AND (:keyword='' OR 
                       word_similarity(lower(title), lower(:keyword)) > 0.3 OR 
                       word_similarity(lower(company), lower(:keyword)) > 0.3 OR 
                       COALESCE(description,'') ILIKE :keywordLike)
                  AND (:location='' OR COALESCE(location,'') ILIKE :locationLike)
                  AND (:type='' OR COALESCE(employment_type,'') ILIKE :typeLike)
                  AND (:source='' OR source=upper(:source))
                  AND (:remote IS NULL OR remote=:remote)
                """;
        long total = bind(jdbc.sql("SELECT count(*) " + where), k, l, t, s, remote).query(Long.class).single();
        
        String orderBy = k.isEmpty() 
            ? "ORDER BY posted_at DESC NULLS LAST, collected_at DESC" 
            : "ORDER BY GREATEST(word_similarity(lower(title), lower(:keyword)) * 2.0, word_similarity(lower(company), lower(:keyword)) * 1.5) DESC, posted_at DESC NULLS LAST";
            
        List<JobView> items = bind(jdbc.sql("""
                SELECT id, source, title, company, location, country_code, employment_type, remote, salary, category,
                       description, requirements, apply_url, source_url, posted_at, collected_at
                """ + where + " " + orderBy + " LIMIT :limit OFFSET :offset"),
                k, l, t, s, remote).param("limit", safeSize).param("offset", safePage * safeSize)
                .query(JobRepository::mapJob).list();
        return new JobPage(items, total, safePage, safeSize);
    }

    Optional<JobView> find(UUID id) {
        return jdbc.sql("""
                SELECT id, source, title, company, location, country_code, employment_type, remote, salary, category,
                       description, requirements, apply_url, source_url, posted_at, collected_at
                FROM jobs WHERE id=:id AND status='ACTIVE' AND saudi_relevant=true
                """).param("id", id).query(JobRepository::mapJob).optional();
    }

    JobCount count() {
        return jdbc.sql("SELECT count(*) AS total, max(collected_at) AS last_collected_at FROM jobs WHERE status='ACTIVE' AND saudi_relevant=true")
                .query((rs, rowNum) -> {
                    long total = rs.getLong("total");
                    return new JobCount(total, targetMinimum, total >= targetMinimum,
                            toInstant(rs.getObject("last_collected_at", OffsetDateTime.class)));
                }).single();
    }

    List<JobView> latest(int limit) {
        return jdbc.sql("""
                SELECT id, source, title, company, location, country_code, employment_type, remote, salary, category,
                       description, requirements, apply_url, source_url, posted_at, collected_at
                FROM jobs WHERE status='ACTIVE' AND saudi_relevant=true
                ORDER BY posted_at DESC NULLS LAST, collected_at DESC LIMIT :limit
                """).param("limit", limit).query(JobRepository::mapJob).list();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, String keyword,
            String location, String type, String source, Boolean remote) {
        return statement.param("keyword", keyword).param("keywordLike", "%" + keyword + "%")
                .param("location", location).param("locationLike", "%" + location + "%")
                .param("type", type).param("typeLike", "%" + type + "%")
                .param("source", source).param("remote", remote, Types.BOOLEAN);
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }

    void deactivateNotSeenSince(String source, Instant collectionStarted) {
        jdbc.sql("""
                UPDATE jobs SET missing_observations=missing_observations+1,
                    status=CASE WHEN missing_observations+1 >= 2 THEN 'EXPIRED' ELSE 'PENDING_RECHECK' END,
                    last_verified_at=now()
                WHERE source=:source AND status IN ('ACTIVE','PENDING_RECHECK') AND last_seen_at < :collectionStarted
                """).param("source", source)
                .param("collectionStarted", OffsetDateTime.ofInstant(collectionStarted, java.time.ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    int expireOldJobs() {
        return jdbc.sql("""
                UPDATE jobs SET status='EXPIRED'
                WHERE status='ACTIVE' AND (expires_at <= now()
                    OR COALESCE(posted_at, collected_at) < now() - make_interval(days => :maxAgeDays))
                """).param("maxAgeDays", maxAgeDays).update();
    }

    private static JobView mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobView(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("title"),
                rs.getString("company"), rs.getString("location"), rs.getString("country_code"),
                rs.getString("employment_type"),
                rs.getBoolean("remote"), rs.getString("salary"), rs.getString("category"),
                rs.getString("description"), rs.getString("requirements"), rs.getString("apply_url"),
                rs.getString("source_url"), toInstant(rs.getObject("posted_at", OffsetDateTime.class)),
                toInstant(rs.getObject("collected_at", OffsetDateTime.class)));
    }
    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    enum UpsertResult { INSERTED, UPDATED }
    record BatchUpsertResult(int inserted, int updated) {}
}

@Component
class RuntimeLeaseService {
    private final JdbcClient jdbc;
    RuntimeLeaseService(JdbcClient jdbc) { this.jdbc = jdbc; }
    UUID acquire(String name, Duration duration) {
        UUID owner = UUID.randomUUID();
        int claimed = jdbc.sql("""
                UPDATE job_runtime_locks SET owner_id=:owner,lease_until=now()+(:seconds * interval '1 second')
                WHERE lock_name=:name AND lease_until<now()
                """).param("owner",owner).param("seconds",duration.toSeconds()).param("name",name).update();
        return claimed == 1 ? owner : null;
    }
    void release(String name, UUID owner) {
        if (owner == null) return;
        jdbc.sql("UPDATE job_runtime_locks SET owner_id=NULL,lease_until='-infinity' WHERE lock_name=:name AND owner_id=:owner")
                .param("name",name).param("owner",owner).update();
    }
    boolean renew(String name, UUID owner, Duration duration) {
        return jdbc.sql("""
                UPDATE job_runtime_locks SET lease_until=now()+(:seconds * interval '1 second')
                WHERE lock_name=:name AND owner_id=:owner
                """).param("seconds",duration.toSeconds()).param("name",name).param("owner",owner).update()==1;
    }
}

@Service
class JobCollectorService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JobCollectorService.class);
    private final List<JobSourceClient> sources;
    private final JobRepository jobs;
    private final SaudiJobPolicy saudiJobPolicy;
    private final JobDeduplicationService deduplication;
    private final JdbcClient jdbc;
    private final boolean collectOnStartup;
    private final RuntimeLeaseService leases;
    private final IntegrationHubService integrationHub;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    private final Map<String, Instant> nextRunAt = new ConcurrentHashMap<>();

    JobCollectorService(List<JobSourceClient> sources, JobRepository jobs, SaudiJobPolicy saudiJobPolicy,
            JobDeduplicationService deduplication, JdbcClient jdbc, RuntimeLeaseService leases,
            IntegrationHubService integrationHub,
            @Value("${app.jobs.collection-on-startup:true}") boolean collectOnStartup) {
        this.sources = List.copyOf(sources);
        this.jobs = jobs;
        this.saudiJobPolicy = saudiJobPolicy;
        this.deduplication = deduplication;
        this.jdbc = jdbc;
        this.leases = leases;
        this.integrationHub = integrationHub;
        this.collectOnStartup = collectOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        jdbc.sql("""
                UPDATE job_collection_runs SET status='ABANDONED',finished_at=now(),
                  failed_sources=concat_ws(',',nullif(failed_sources,''),'PROCESS_TERMINATED')
                WHERE status='RUNNING' AND started_at<now()-interval '15 minutes'
                """).update();
        if (collectOnStartup) Thread.startVirtualThread(this::collectSafely);
    }

    @Scheduled(fixedDelayString = "${app.jobs.continuous-poll-ms:60000}",
            initialDelayString = "${app.jobs.continuous-initial-delay-ms:60000}")
    void continuousCollection() {
        Instant now = Instant.now();
        List<JobSourceClient> due = sources.stream()
                .filter(source -> !nextRunAt.getOrDefault(source.sourceName(), Instant.EPOCH).isAfter(now))
                .toList();
        if (!due.isEmpty()) collectSafely(due);
    }

    CollectionSummary collectAll() {
        return collect(sources);
    }

    CollectionRequest requestManualCollection() {
        sources.forEach(JobSourceClient::forceRefresh);
        if (running.get()) {
            rerunRequested.set(true);
            return new CollectionRequest(true, true,
                    "A collection is already running. Another complete run has been queued.");
        }
        Thread.startVirtualThread(() -> {
            try { collectAll(); }
            catch (IllegalStateException overlap) { rerunRequested.set(true); }
        });
        return new CollectionRequest(true, false,
                "Backend collection started. Automatic scheduling remains active.");
    }

    private CollectionSummary collect(List<JobSourceClient> selectedSources) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A job collection is already running");
        UUID lease = leases.acquire("job-collection", Duration.ofMinutes(30));
        if (lease == null) {
            running.set(false);
            throw new IllegalStateException("A job collection is already running in another application instance");
        }
        Instant started = Instant.now();
        var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        heartbeat.scheduleAtFixedRate(() -> leases.renew("job-collection",lease,Duration.ofMinutes(30)),
                2,2,java.util.concurrent.TimeUnit.MINUTES);
        UUID runId = jdbc.sql("INSERT INTO job_collection_runs(started_at,status) VALUES (:startedAt,'RUNNING') RETURNING id")
                .param("startedAt", OffsetDateTime.ofInstant(started, java.time.ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(UUID.class).single();
        int inserted = 0, updated = 0, rejected = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (JobSourceClient source : selectedSources) {
                nextRunAt.put(source.sourceName(), started.plus(source.refreshInterval()));
                try {
                    Map<String, CollectedJob> unique = new LinkedHashMap<>();
                    List<CollectedJob> fetched = source.fetch();
                    for (CollectedJob job : fetched) {
                        if (saudiJobPolicy.accepts(job)) unique.putIfAbsent(job.dedupKey(), job);
                        else rejected++;
                    }
                    JobRepository.BatchUpsertResult batch = jobs.upsertBatch(List.copyOf(unique.values()));
                    inserted += batch.inserted();
                    updated += batch.updated();
                    integrationHub.recordCollection(source.sourceName(), fetched, unique.keySet());
                    source.afterSuccessfulCollection(started, List.copyOf(unique.values()));
                    if (!fetched.isEmpty() && source.completeSnapshot()) {
                        jobs.deactivateNotSeenSince(source.sourceName(), started);
                    }
                } catch (RuntimeException exception) {
                    failed.add(source.sourceName());
                    log.warn("Job source {} failed during collection: {}", source.sourceName(), exception.toString(), exception);
                }
            }
            deduplication.removeDuplicates();
            Instant finished = Instant.now();
            String status = failed.size() == selectedSources.size() ? "FAILED" : (failed.isEmpty() ? "SUCCESS" : "PARTIAL");
            jdbc.sql("""
                    UPDATE job_collection_runs SET finished_at=:finishedAt, inserted_count=:inserted,
                    updated_count=:updated, rejected_count=:rejected, failed_sources=:failed,
                    status=:status WHERE id=:id
                    """).param("finishedAt", OffsetDateTime.ofInstant(finished, java.time.ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("inserted", inserted).param("updated", updated)
                    .param("rejected", rejected)
                    .param("failed", String.join(",", failed)).param("status", status).param("id", runId).update();
            JobCount count = jobs.count();
            return new CollectionSummary(inserted, updated, rejected, count.count(), count.targetMinimum(),
                    count.targetMet(), List.copyOf(failed), started, finished);
        } finally {
            heartbeat.shutdownNow();
            leases.release("job-collection", lease);
            running.set(false);
            if (rerunRequested.getAndSet(false)) Thread.startVirtualThread(this::collectSafely);
        }
    }

    private void collectSafely() { try { collectAll(); } catch (RuntimeException ignored) { } }
    private void collectSafely(List<JobSourceClient> selectedSources) {
        try { collect(selectedSources); } catch (RuntimeException ignored) { }
    }

    @Scheduled(cron = "${app.jobs.expiry-cron:0 15 */3 * * *}")
    void expireScheduled() { jobs.expireOldJobs(); }
}

@Service
class AgentJobIngestionService {
    private final JobRepository jobs;
    private final SaudiJobPolicy saudiJobPolicy;

    AgentJobIngestionService(JobRepository jobs, SaudiJobPolicy saudiJobPolicy) {
        this.jobs = jobs;
        this.saudiJobPolicy = saudiJobPolicy;
    }

    AgentIngestionSummary ingest(List<AgentJobSubmission> submissions) {
        int inserted = 0, updated = 0, rejected = 0;
        for (AgentJobSubmission submission : submissions) {
            String source = submission.source().trim().toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9_-]", "_");
            CollectedJob job = new CollectedJob(source, submission.externalId(), submission.title(),
                    submission.company(), submission.location(), submission.employmentType(), submission.remote(),
                    submission.salary(), submission.category(), submission.description(), submission.requirements(),
                    submission.applyUrl(), submission.sourceUrl(), submission.postedAt(), null);
            if (!saudiJobPolicy.accepts(job)) {
                rejected++;
                continue;
            }
            if (jobs.upsert(job) == JobRepository.UpsertResult.INSERTED) inserted++; else updated++;
        }
        return new AgentIngestionSummary(inserted, updated, rejected);
    }
}

@Service
class JobService {
    private final JobRepository jobs;
    JobService(JobRepository jobs) { this.jobs = jobs; }

    List<JobView> recommended(List<String> skills) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String skill : skills) if (skill != null && !skill.isBlank()) normalized.add(skill.trim().toLowerCase(Locale.ROOT));
        if (normalized.isEmpty()) return jobs.latest(50);
        return jobs.latest(250).stream()
                .map(job -> Map.entry(job, score(job, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((left, right) -> {
                    int scoreComparison = Integer.compare(right.getValue(), left.getValue());
                    if (scoreComparison != 0) return scoreComparison;
                    Instant leftDate = Optional.ofNullable(left.getKey().postedAt()).orElse(Instant.EPOCH);
                    Instant rightDate = Optional.ofNullable(right.getKey().postedAt()).orElse(Instant.EPOCH);
                    return rightDate.compareTo(leftDate);
                })
                .limit(50).map(Map.Entry::getKey).toList();
    }

    private static int score(JobView job, Set<String> skills) {
        String haystack = String.join(" ", Objects.toString(job.title(), ""), Objects.toString(job.category(), ""),
                Objects.toString(job.description(), ""), Objects.toString(job.requirements(), "")).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String skill : skills) if (haystack.contains(skill)) score += job.title().toLowerCase(Locale.ROOT).contains(skill) ? 5 : 2;
        return score;
    }
}

@Service
class AdminKeyVerifier {
    AdminKeyVerifier() {}
    void verify(String provided, Authentication authentication) {
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) return;
        throw new AdminKeyException();
    }
}
class AdminKeyException extends RuntimeException {}

@RestController
@RequestMapping("/api")
class JobController {
    private final JobRepository jobs;
    private final JobCollectorService collector;
    private final JobService jobService;
    private final ProfileRepository profiles;
    private final AdminKeyVerifier adminKeyVerifier;
    private final AgentJobIngestionService agentIngestion;

    JobController(JobRepository jobs, JobCollectorService collector, JobService jobService,
            ProfileRepository profiles, AdminKeyVerifier adminKeyVerifier,
            AgentJobIngestionService agentIngestion) {
        this.jobs = jobs; this.collector = collector; this.jobService = jobService;
        this.profiles = profiles; this.adminKeyVerifier = adminKeyVerifier;
        this.agentIngestion = agentIngestion;
    }

    @GetMapping("/jobs")
    JobPage jobs(@RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="") String location,
            @RequestParam(defaultValue="") String type, @RequestParam(defaultValue="") String source,
            @RequestParam(required=false) Boolean remote, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return jobs.search(keyword, location, type, source, remote, page, size);
    }

    @GetMapping("/jobs/{id}")
    JobView job(@PathVariable UUID id) { return jobs.find(id).orElseThrow(JobNotFoundException::new); }
    @GetMapping("/jobs/count")
    JobCount count() { return jobs.count(); }

    @GetMapping("/jobs/recommended")
    List<JobView> recommended(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationRequiredException();
        }
        CandidateProfile profile = profiles.find(user.id())
                .orElseGet(() -> CandidateProfile.empty(user.id(), user.email(), user.displayName()));
        return jobService.recommended(profile.skills());
    }

    @PostMapping("/admin/jobs/collect")
    CollectionRequest collect(@RequestHeader(name="X-Admin-Key", required=false) String adminKey,
            Authentication authentication) {
        adminKeyVerifier.verify(adminKey, authentication);
        return collector.requestManualCollection();
    }

    @PostMapping("/admin/jobs/ingest")
    AgentIngestionSummary ingest(@RequestHeader(name="X-Admin-Key", required=false) String adminKey,
            @Valid @RequestBody AgentIngestionRequest request, Authentication authentication) {
        adminKeyVerifier.verify(adminKey, authentication);
        return agentIngestion.ingest(request.jobs());
    }

}

class JobNotFoundException extends RuntimeException {}
class AuthenticationRequiredException extends RuntimeException {}

@org.springframework.web.bind.annotation.RestControllerAdvice
class JobApiExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String,String> notFound() { return Map.of("code","JOB_NOT_FOUND","message","The requested job does not exist"); }
    @org.springframework.web.bind.annotation.ExceptionHandler(AdminKeyException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String,String> invalidAdminKey() { return Map.of("code","INVALID_ADMIN_KEY","message","The collection admin key is invalid"); }
    @org.springframework.web.bind.annotation.ExceptionHandler(AuthenticationRequiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String,String> authenticationRequired() { return Map.of("code","AUTHENTICATION_REQUIRED","message","Sign in to use profile-based search"); }
}
