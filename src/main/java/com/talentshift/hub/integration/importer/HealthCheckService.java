package com.talentshift.hub.integration.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);
    private static final int MAX_FAILURES = 3;

    private final JdbcTemplate db;
    private final HttpClient httpClient;

    public HealthCheckService(JdbcTemplate db) {
        this.db = db;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // Run every 3 hours (cron: at minute 0 past every 3rd hour)
    @Scheduled(cron = "0 0 */3 * * *")
    public void runHealthChecks() {
        log.info("Starting background health checks for active jobs and sources...");
        checkSources();
        checkJobs();
        log.info("Background health checks completed.");
    }

    public void checkSources() {
        List<Map<String, Object>> activeSources = db.queryForList("SELECT id, normalized_url, consecutive_check_failures FROM canonical_source WHERE active = true");
        log.info("Found {} active sources to check.", activeSources.size());

        for (Map<String, Object> source : activeSources) {
            UUID id = (UUID) source.get("id");
            String url = (String) source.get("normalized_url");
            int failures = (int) source.get("consecutive_check_failures");

            boolean isOnline = pingUrl(url);

            if (isOnline) {
                if (failures > 0) {
                    db.update("UPDATE canonical_source SET consecutive_check_failures = 0 WHERE id = ?", id);
                }
            } else {
                failures++;
                if (failures >= MAX_FAILURES) {
                    log.warn("Source {} has failed {} times. Deactivating.", url, failures);
                    db.update("UPDATE canonical_source SET active = false, consecutive_check_failures = ? WHERE id = ?", failures, id);
                } else {
                    log.warn("Source {} check failed. Failure count: {}", url, failures);
                    db.update("UPDATE canonical_source SET consecutive_check_failures = ? WHERE id = ?", failures, id);
                }
            }
        }
    }

    public void checkJobs() {
        List<Map<String, Object>> activeJobs = db.queryForList("SELECT id, normalized_job_url, consecutive_check_failures FROM canonical_job WHERE active = true");
        log.info("Found {} active jobs to check.", activeJobs.size());

        for (Map<String, Object> job : activeJobs) {
            UUID id = (UUID) job.get("id");
            String url = (String) job.get("normalized_job_url");
            int failures = (int) job.get("consecutive_check_failures");

            if (url == null || url.isEmpty()) {
                continue;
            }

            boolean isOnline = pingUrl(url);

            if (isOnline) {
                if (failures > 0) {
                    db.update("UPDATE canonical_job SET consecutive_check_failures = 0 WHERE id = ?", id);
                }
            } else {
                failures++;
                if (failures >= MAX_FAILURES) {
                    log.warn("Job {} has failed {} times. Deactivating.", url, failures);
                    db.update("UPDATE canonical_job SET active = false, consecutive_check_failures = ? WHERE id = ?", failures, id);
                } else {
                    log.warn("Job {} check failed. Failure count: {}", url, failures);
                    db.update("UPDATE canonical_job SET consecutive_check_failures = ? WHERE id = ?", failures, id);
                }
            }
        }
    }

    private boolean pingUrl(String urlString) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .timeout(Duration.ofSeconds(10))
                    // Provide a user agent to avoid basic blocks
                    .header("User-Agent", "Mozilla/5.0 (TalentShift HealthCheck/1.0)")
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            // Consider 2xx and 3xx as success. 404, 403, 500 etc as failures for our basic check.
            return status >= 200 && status < 400;
        } catch (Exception e) {
            return false;
        }
    }
}
