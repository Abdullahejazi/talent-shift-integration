package com.talentshift;

import java.net.URI;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

record SourceHealth(UUID id, String company, String type, String url, boolean enabled, String permission,
        OffsetDateTime lastSuccess, OffsetDateTime lastFailure, OffsetDateTime nextRetry, int failures,
        String lastError, long fetches, long jobsFound, int refreshMinutes) {}
record OperationsStatus(long activeJobs,long totalSources,long enabledSources,long healthySources,long discoverySearches,
        long discoveryTargets,long promotedSeeds,OffsetDateTime lastCollection,int aiCreditLimit,int seedRefreshMinutes) {}
record DiscoveryHistory(String query,String kind,OffsetDateTime searchedAt,int results,int jobs,int seeds) {}
record DailyJobOperations(java.time.LocalDate day,long inserted,long activated,long expired,long inactivated,
        long outOfScope,long netChange) {}
record SourceToggle(boolean enabled) {}

@Service
class JobLinkVerificationService {
    private final JdbcClient jdbc;
    private final SafeSourceHttpClient http;
    private final int concurrency;

    JobLinkVerificationService(JdbcClient jdbc, SafeSourceHttpClient http,
            @Value("${app.jobs.verification-concurrency:8}") int concurrency) {
        this.jdbc = jdbc; this.http = http; this.concurrency = Math.max(1, Math.min(concurrency, 24));
    }

    @Scheduled(cron="${app.jobs.verification-cron:0 0 12 * * *}", zone="${app.jobs.verification-zone:Asia/Riyadh}")
    void verifyAtNoon() {
        List<Link> links = jdbc.sql("""
                SELECT id, canonical_application_url FROM jobs
                WHERE status IN ('ACTIVE','PENDING_RECHECK') AND canonical_application_url IS NOT NULL
                ORDER BY last_verified_at NULLS FIRST
                """).query((rs,n)->new Link(rs.getObject(1,UUID.class),rs.getString(2))).list();
        try (var executor=Executors.newFixedThreadPool(concurrency,Thread.ofVirtual().factory())) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(Link link:links) futures.add(executor.submit(()->verify(link)));
            for(var future:futures) try{future.get();}catch(Exception ignored){}
        }
    }

    private void verify(Link link) {
        try {
            http.get(URI.create(link.url()),null);
            jdbc.sql("""
                    UPDATE jobs SET status='ACTIVE',missing_observations=0,last_verified_at=now()
                    WHERE id=:id
                    """).param("id",link.id()).update();
        } catch (SourceHttpException exception) {
            if (exception.status()!=404 && exception.status()!=410) return;
            jdbc.sql("""
                    UPDATE jobs SET missing_observations=missing_observations+1,
                      status=CASE WHEN missing_observations+1>=2 THEN 'EXPIRED' ELSE 'PENDING_RECHECK' END,
                      last_verified_at=now() WHERE id=:id
                    """).param("id",link.id()).update();
        }
    }
    private record Link(UUID id,String url) {}
}

@Service
class JobDeduplicationService {
    private final JdbcClient jdbc;

    JobDeduplicationService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString="${app.jobs.deduplication-poll-ms:600000}",
            initialDelayString="${app.jobs.deduplication-initial-delay-ms:120000}")
    @org.springframework.transaction.annotation.Transactional
    int removeDuplicates() {
        List<DuplicateJob> duplicates = jdbc.sql("""
                WITH ranked AS (
                    SELECT id,
                           first_value(id) OVER (
                               PARTITION BY lower(regexp_replace(
                                   split_part(trim(coalesce(canonical_application_url,apply_url)), '#', 1),
                                   '/+$', ''))
                               ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END,
                                        source_quality ASC, last_verified_at DESC NULLS LAST, collected_at DESC
                           ) keeper_id,
                           row_number() OVER (
                               PARTITION BY lower(regexp_replace(
                                   split_part(trim(coalesce(canonical_application_url,apply_url)), '#', 1),
                                   '/+$', ''))
                               ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END,
                                        source_quality ASC, last_verified_at DESC NULLS LAST, collected_at DESC
                           ) position
                    FROM jobs
                    WHERE coalesce(canonical_application_url,apply_url) IS NOT NULL
                      AND trim(coalesce(canonical_application_url,apply_url)) <> ''
                )
                SELECT id,keeper_id FROM ranked WHERE position>1
                """).query((rs,n)->new DuplicateJob(
                        rs.getObject("id",UUID.class),rs.getObject("keeper_id",UUID.class))).list();

        for (DuplicateJob duplicate : duplicates) {
            jdbc.sql("""
                    INSERT INTO user_saved_jobs(user_id,job_id,saved_at)
                    SELECT user_id,:keeper,saved_at FROM user_saved_jobs WHERE job_id=:duplicate
                    ON CONFLICT (user_id,job_id) DO UPDATE
                    SET saved_at=LEAST(user_saved_jobs.saved_at,EXCLUDED.saved_at)
                    """).param("keeper",duplicate.keeper()).param("duplicate",duplicate.id()).update();
            jdbc.sql("DELETE FROM user_saved_jobs WHERE job_id=:duplicate")
                    .param("duplicate",duplicate.id()).update();
            jdbc.sql("UPDATE workspace_applications SET job_id=:keeper WHERE job_id=:duplicate")
                    .param("keeper",duplicate.keeper()).param("duplicate",duplicate.id()).update();
            jdbc.sql("DELETE FROM jobs WHERE id=:duplicate")
                    .param("duplicate",duplicate.id()).update();
        }
        return duplicates.size();
    }

    private record DuplicateJob(UUID id,UUID keeper) {}
}

@Service
class JobEnrichmentService {
    private final JdbcClient jdbc;
    private final int batchSize;
    JobEnrichmentService(JdbcClient jdbc,@Value("${app.jobs.enrichment-batch-size:100}") int batchSize){
        this.jdbc=jdbc;this.batchSize=Math.max(1,Math.min(batchSize,500));
    }

    @Scheduled(fixedDelayString="${app.jobs.enrichment-poll-ms:60000}",initialDelayString="${app.jobs.enrichment-initial-delay-ms:90000}")
    void enrichPending(){
        List<Pending> jobs=jdbc.sql("""
                UPDATE jobs SET enrichment_status='PROCESSING',enrichment_attempts=enrichment_attempts+1
                WHERE id IN (SELECT id FROM jobs WHERE enrichment_status='PENDING' ORDER BY collected_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING id,title,description,apply_url
                """).param("limit",batchSize).query((rs,n)->new Pending(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("description"),rs.getString("apply_url"))).list();
        for(Pending job:jobs)try{
            String currentDesc = job.description();
            if (currentDesc == null && job.applyUrl() != null && job.applyUrl().startsWith("http")) {
                try { currentDesc = org.jsoup.Jsoup.connect(job.applyUrl()).userAgent("Mozilla/5.0").timeout(5000).get().body().text(); } catch(Exception ignored) {}
            }
            String category=category(job.title());String level=level(job.title());String summary=summarize(currentDesc);
            jdbc.sql("""
                    UPDATE jobs SET normalized_category=:category,experience_level=:level,summary_en=:summary,
                      description=COALESCE(description, :newDesc),
                      enrichment_status='COMPLETE' WHERE id=:id
                    """).param("category",category).param("level",level).param("summary",summary).param("newDesc",currentDesc).param("id",job.id()).update();
        }catch(RuntimeException e){jdbc.sql("UPDATE jobs SET enrichment_status=CASE WHEN enrichment_attempts>=3 THEN 'FAILED' ELSE 'PENDING' END WHERE id=:id").param("id",job.id()).update();}
    }
    private static String category(String title){String t=title.toLowerCase(Locale.ROOT);if(t.matches(".*(software|developer|engineer|data|cloud|security|it).*"))return "Technology";if(t.matches(".*(finance|account|audit|bank).*"))return "Finance";if(t.matches(".*(sales|marketing|business development).*"))return "Sales & Marketing";if(t.matches(".*(health|nurse|doctor|medical).*"))return "Healthcare";return "Other";}
    private static String level(String title){String t=title.toLowerCase(Locale.ROOT);if(t.matches(".*(intern|trainee|graduate).*"))return "Entry";if(t.matches(".*(senior|lead|principal|manager|director|head|chief).*"))return "Senior";return "Mid-level";}
    private static String summarize(String value){if(value==null||value.isBlank())return null;String clean=value.replaceAll("\\s+"," ").trim();return clean.length()<=500?clean:clean.substring(0,497)+"...";}
    private record Pending(UUID id,String title,String description,String applyUrl){}
}

@RestController
@RequestMapping("/api/admin/job-sources")
class JobSourceOperationsController {
    private final JdbcClient jdbc; private final AdminKeyVerifier admin; private final JobSourceRegistry registry;
    private final JobCollectorService collector; private final int aiCredits; private final int seedRefreshMinutes;
    JobSourceOperationsController(JdbcClient jdbc,AdminKeyVerifier admin,JobSourceRegistry registry,JobCollectorService collector,

            @Value("${app.jobs.tavily-new-job-search-credits:200}")int aiCredits,
            @Value("${app.jobs.seed-refresh-minutes:60}")int seedRefreshMinutes){this.jdbc=jdbc;this.admin=admin;this.registry=registry;this.collector=collector;this.aiCredits=Math.max(1,Math.min(aiCredits,10_000));this.seedRefreshMinutes=Math.max(5,seedRefreshMinutes);}

    @GetMapping("/status")
    OperationsStatus status(@RequestHeader(name="X-Admin-Key",required=false)String key,Authentication authentication){admin.verify(key,authentication);return jdbc.sql("""
            SELECT (SELECT count(*) FROM jobs WHERE status='ACTIVE') active_jobs,
              (SELECT count(*) FROM job_sources) total_sources,
              (SELECT count(*) FROM job_sources WHERE enabled=true) enabled_sources,
              (SELECT count(*) FROM job_sources WHERE enabled=true AND consecutive_failures=0) healthy_sources,
              (SELECT count(*) FROM ai_discovery_searches) searches,
              (SELECT count(*) FROM ai_discovery_targets) targets,
              (SELECT count(*) FROM ai_discovery_targets WHERE status='PROMOTED') promoted,
              (SELECT max(finished_at) FROM job_collection_runs) last_collection
            """).query((rs,n)->new OperationsStatus(rs.getLong("active_jobs"),rs.getLong("total_sources"),rs.getLong("enabled_sources"),rs.getLong("healthy_sources"),rs.getLong("searches"),rs.getLong("targets"),rs.getLong("promoted"),rs.getObject("last_collection",OffsetDateTime.class),aiCredits,seedRefreshMinutes)).single();}

    @PostMapping("/recheck")
    CollectionRequest recheck(@RequestHeader(name="X-Admin-Key",required=false)String key,Authentication authentication){admin.verify(key,authentication);registry.forceAllDue();return collector.requestManualCollection();}




    @GetMapping("/daily-metrics")
    List<DailyJobOperations> dailyMetrics(@RequestHeader(name="X-Admin-Key",required=false)String key,Authentication authentication){admin.verify(key,authentication);return jdbc.sql("""
            SELECT day,inserted,activated,expired,inactivated,out_of_scope,net_change
            FROM daily_job_operations ORDER BY day DESC LIMIT 31
            """).query((rs,n)->new DailyJobOperations(rs.getObject(1,java.time.LocalDate.class),rs.getLong(2),
            rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7))).list();}

    @GetMapping("/performance")
    List<SourcePerformance> performance(@RequestHeader(name="X-Admin-Key",required=false)String key,Authentication authentication){admin.verify(key,authentication);return jdbc.sql("""
            SELECT company, count(*) as jobs FROM jobs GROUP BY company ORDER BY jobs DESC, company ASC
            """).query((rs,n)->new SourcePerformance(rs.getString(1),rs.getLong(2))).list();}
    @GetMapping("/categories")
    List<SourceCategoryCount> categories(@RequestHeader(name="X-Admin-Key",required=false)String key,Authentication authentication){admin.verify(key,authentication);return jdbc.sql("""
            SELECT c.id, c.name, c.description, count(s.id) as registered_sources
            FROM source_categories c
            LEFT JOIN job_sources s ON c.id = s.category_id AND s.enabled=true
            GROUP BY c.id ORDER BY c.created_at ASC
            """).query((rs,n)->new SourceCategoryCount(rs.getObject("id", java.util.UUID.class),rs.getString("name"),rs.getString("description"),rs.getLong("registered_sources"))).list();}
}

record SourcePerformance(String company, long jobs) {}
record SourceCategoryCount(java.util.UUID id, String category, String description, long registeredSources) {}
