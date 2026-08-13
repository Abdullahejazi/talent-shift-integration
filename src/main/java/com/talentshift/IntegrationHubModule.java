package com.talentshift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

record ReviewDecision(String note) {}
record AiExtensionStatus(boolean enabled, String provider, int globalConcurrency,
        int perDomainConcurrency, int batchLimit, int maximumJobs, boolean configured) {}
record AiDiscoveryRequest(String mode) {}
record AiDiscoveryResult(boolean accepted, String message) {}

interface AiDiscoveryProvider {
    String providerName();
    boolean configured();
    AiDiscoveryResult discover(String mode);
}

@Service
class DisabledAiDiscoveryProvider implements AiDiscoveryProvider {
    private final String provider;
    DisabledAiDiscoveryProvider(@Value("${app.ai.provider:none}") String provider) { this.provider = provider; }
    public String providerName() { return provider; }
    public boolean configured() { return false; }
    public AiDiscoveryResult discover(String mode) {
        return new AiDiscoveryResult(false,
                "AI discovery is intentionally disabled. Add a provider implementation and set app.ai.enabled=true when one is approved.");
    }
}

@Service
class IntegrationHubService {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    IntegrationHubService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    @Transactional
    void recordCollection(String sourceName, List<CollectedJob> records, Set<String> acceptedKeys) {
        if (records.isEmpty()) return;
        UUID systemId = db.queryForObject("SELECT id FROM connected_systems WHERE system_key='talentshift-public-collection'", UUID.class);
        UUID batchId = db.queryForObject("""
                INSERT INTO import_batches(connected_system_id,status,records_received,records_accepted,records_rejected,finished_at)
                VALUES (?,'SUCCESS',?,?,?,now()) RETURNING id
                """, UUID.class, systemId, records.size(), acceptedKeys.size(), records.size()-acceptedKeys.size());
        for (CollectedJob record : records) {
            try {
                String payload=json.writeValueAsString(record);
                String checksum=hex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
                String status=acceptedKeys.contains(record.dedupKey())?"ACCEPTED":"REJECTED";
                UUID rawId=db.queryForObject("""
                        INSERT INTO raw_job_records(import_batch_id,connected_system_id,external_record_id,payload,checksum,processing_status,rejection_reason)
                        VALUES (?,?,?,?::jsonb,?,?,?) RETURNING id
                        """,UUID.class,batchId,systemId,record.externalId(),payload,checksum,status,
                        "REJECTED".equals(status)?"Did not pass the Saudi/direct-link collection policy":null);
                if ("ACCEPTED".equals(status)) {
                    List<UUID> jobIds=db.queryForList("SELECT id FROM jobs WHERE source=? AND external_id=? LIMIT 1",UUID.class,record.source(),record.externalId());
                    if(!jobIds.isEmpty())db.update("""
                            INSERT INTO job_lineage_observations(job_id,raw_record_id,observation_type,observed_url,details)
                            VALUES (?,?, 'COLLECTED',?,jsonb_build_object('sourceName',?))
                            """,jobIds.getFirst(),rawId,record.applyUrl(),sourceName);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to preserve raw collection record", exception);
            }
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result=new StringBuilder(value.length*2);
        for(byte item:value)result.append(String.format("%02x",item));
        return result.toString();
    }

    List<Map<String,Object>> rows(String sql, int limit) {
        return db.queryForList(sql, Math.max(1, Math.min(limit, 20_000)));
    }

    @Transactional
    void decide(UUID id, boolean approved, String actor, String note) {
        int updated = db.update("""
                UPDATE deduplication_reviews SET status=?,decision_note=?,decided_by=?,decided_at=now()
                WHERE id=? AND status='PENDING'
                """, approved ? "APPROVED" : "REJECTED", note, actor, id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pending review not found");
        audit(actor, approved ? "REVIEW_APPROVED" : "REVIEW_REJECTED", "DEDUPLICATION_REVIEW", id.toString(), Map.of());
    }

    @Transactional
    UUID enqueueJob(UUID jobId) {
        List<Map<String,Object>> jobs = db.queryForList("SELECT * FROM jobs WHERE id=?", jobId);
        if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        String payload;
        try { payload = json.writeValueAsString(jobs.getFirst()); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize job", e); }
        return db.queryForObject("""
                INSERT INTO integration_outbox(aggregate_type,aggregate_id,destination_key,payload,idempotency_key)
                VALUES ('JOB',?,'talentshift-public-collection',?::jsonb,?)
                ON CONFLICT (idempotency_key) DO UPDATE SET payload=EXCLUDED.payload
                RETURNING id
                """, UUID.class, jobId, payload, "JOB:" + jobId);
    }

    @Transactional
    int sendReady(int limit) {
        List<UUID> ids = db.queryForList("""
                SELECT id FROM integration_outbox WHERE status IN ('READY','RETRY') AND next_attempt_at<=now()
                ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED
                """, UUID.class, Math.max(1, Math.min(limit, 500)));
        for (UUID id : ids) {
            Integer attempt = db.queryForObject("SELECT attempt_count+1 FROM integration_outbox WHERE id=?", Integer.class, id);
            db.update("INSERT INTO integration_transfer_attempts(outbox_id,attempt_number,status,response_code,response_body) VALUES (?,?, 'SENT',200,'Internal destination accepted')", id, attempt);
            db.update("UPDATE integration_outbox SET status='SENT',attempt_count=?,sent_at=now(),last_error=NULL WHERE id=?", attempt, id);
        }
        return ids.size();
    }

    void retry(UUID id) {
        int updated = db.update("UPDATE integration_outbox SET status='RETRY',next_attempt_at=now(),last_error=NULL WHERE id=?", id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox item not found");
    }

    void audit(String actor, String action, String type, String id, Map<String,Object> details) {
        try {
            db.update("INSERT INTO integration_audit_log(actor,action,entity_type,entity_id,details) VALUES (?,?,?,?,?::jsonb)",
                    actor == null ? "system" : actor, action, type, id, json.writeValueAsString(details));
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}

