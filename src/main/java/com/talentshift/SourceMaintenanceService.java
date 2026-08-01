package com.talentshift;

import java.util.List;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Requeues all approved seeds every 90 minutes and removes confirmed unusable registrations. */
@Service
class SourceMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(SourceMaintenanceService.class);
    private final JdbcClient jdbc;
    private final JobSourceRegistry registry;
    private final int maximumSources;

    SourceMaintenanceService(JdbcClient jdbc, JobSourceRegistry registry,
            @Value("${app.jobs.maximum-job-sources:10000}") int maximumSources) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.maximumSources = maximumSources;
    }

    @PostConstruct
    void configureLimit() {
        jdbc.sql("UPDATE system_settings SET integer_value=:limit,updated_at=now() WHERE setting_key='maximum_job_sources'")
                .param("limit",maximumSources <= 0 ? null : maximumSources,java.sql.Types.BIGINT).update();
    }

    @Scheduled(fixedDelayString="${app.jobs.source-health-refresh-ms:5400000}",
            initialDelayString="${app.jobs.source-health-initial-delay-ms:900000}")
    void refreshAndRemoveBrokenSources() {
        List<String> removed = jdbc.sql("""
                DELETE FROM job_sources
                WHERE last_error_code IN ('HTTP_403','HTTP_404','HTTP_410','ROBOTS_DISALLOWED','NO_COLLECTOR',
                  'INVALID_HOST','UNSAFE_SCHEME','PRIVATE_ADDRESS_BLOCKED','TOO_MANY_REDIRECTS',
                  'RESPONSE_TOO_LARGE','MALFORMED_JSON')
                RETURNING company_name || ' [' || source_type || ']'
                """).query(String.class).list();
        if (!removed.isEmpty()) log.warn("Removed {} broken or inaccessible job sources: {}",
                removed.size(), removed.stream().limit(20).toList());
        registry.forceAllDue();
        log.info("Source health cycle completed; every enabled approved seed is queued for refresh");
    }
}
