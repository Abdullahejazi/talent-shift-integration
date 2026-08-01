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

@RestController
@RequestMapping("/api/integration")
class IntegrationHubController {
    private final IntegrationHubService hub;
    private final AdminKeyVerifier admin;
    private final AiDiscoveryProvider ai;
    private final boolean aiEnabled;
    private final int aiGlobal;
    private final int aiPerDomain;
    private final int aiBatch;
    private final int aiMaximum;

    IntegrationHubController(IntegrationHubService hub, AdminKeyVerifier admin, AiDiscoveryProvider ai,
            @Value("${app.ai.enabled:false}") boolean aiEnabled,
            @Value("${app.ai.global-concurrency:24}") int aiGlobal,
            @Value("${app.ai.per-domain-concurrency:2}") int aiPerDomain,
            @Value("${app.ai.batch-limit:2500}") int aiBatch,
            @Value("${app.ai.maximum-jobs:0}") int aiMaximum) {
        this.hub=hub; this.admin=admin; this.ai=ai; this.aiEnabled=aiEnabled;
        this.aiGlobal=Math.max(1,aiGlobal); this.aiPerDomain=Math.max(1,aiPerDomain);
        this.aiBatch=Math.max(1,aiBatch); this.aiMaximum=Math.max(0,aiMaximum);
    }

    private void authorize(String key, Authentication auth) { admin.verify(key, auth); }
    private static String actor(Principal principal) { return principal == null ? "admin-key" : principal.getName(); }

    @GetMapping("/connected-systems") List<Map<String,Object>> systems(@RequestHeader(name="X-Admin-Key",required=false) String key, Authentication auth) { authorize(key,auth); return hub.rows("SELECT * FROM connected_systems ORDER BY display_name LIMIT ?", 10_000); }
    @GetMapping("/imports") List<Map<String,Object>> imports(@RequestHeader(name="X-Admin-Key",required=false) String key, Authentication auth,@RequestParam(defaultValue="100") int limit) { authorize(key,auth); return hub.rows("SELECT * FROM import_batches ORDER BY started_at DESC LIMIT ?",limit); }
    @GetMapping("/raw/jobs") List<Map<String,Object>> rawJobs(@RequestHeader(name="X-Admin-Key",required=false) String key, Authentication auth,@RequestParam(defaultValue="200") int limit) { authorize(key,auth); return hub.rows("SELECT * FROM raw_job_records ORDER BY received_at DESC LIMIT ?",limit); }
    @GetMapping("/lineage") List<Map<String,Object>> lineage(@RequestHeader(name="X-Admin-Key",required=false) String key, Authentication auth,@RequestParam(defaultValue="200") int limit) { authorize(key,auth); return hub.rows("SELECT * FROM job_lineage_observations ORDER BY observed_at DESC LIMIT ?",limit); }
    @GetMapping("/reviews") List<Map<String,Object>> reviews(@RequestHeader(name="X-Admin-Key",required=false) String key, Authentication auth,@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="100") int limit) { authorize(key,auth); return hub.rows("SELECT * FROM deduplication_reviews WHERE status='" + safeStatus(status) + "' ORDER BY created_at DESC LIMIT ?",limit); }
    @PostMapping("/reviews/{id}/approve") void approve(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,Principal principal,@PathVariable UUID id,@RequestBody(required=false) ReviewDecision request){authorize(key,auth);hub.decide(id,true,actor(principal),request==null?null:request.note());}
    @PostMapping("/reviews/{id}/reject") void reject(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,Principal principal,@PathVariable UUID id,@RequestBody(required=false) ReviewDecision request){authorize(key,auth);hub.decide(id,false,actor(principal),request==null?null:request.note());}
    @GetMapping("/checkpoints") List<Map<String,Object>> checkpoints(@RequestHeader(name="X-Admin-Key",required=false) String key, Authentication auth){authorize(key,auth);return hub.rows("SELECT sc.*,cs.system_key,cs.display_name FROM sync_checkpoints sc JOIN connected_systems cs ON cs.id=sc.connected_system_id ORDER BY cs.system_key,sc.record_type LIMIT ?",10_000);}
    @GetMapping("/outbox") List<Map<String,Object>> outbox(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@RequestParam(defaultValue="100") int limit){authorize(key,auth);return hub.rows("SELECT * FROM integration_outbox ORDER BY created_at DESC LIMIT ?",limit);}
    @GetMapping("/outbox/{id}/attempts") List<Map<String,Object>> attempts(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@PathVariable UUID id){authorize(key,auth);return hub.rows("SELECT * FROM integration_transfer_attempts WHERE outbox_id='"+id+"' ORDER BY attempt_number LIMIT ?",10_000);}
    @PostMapping("/outbox/jobs/{jobId}") Map<String,Object> enqueue(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@PathVariable UUID jobId){authorize(key,auth);return Map.of("outboxId",hub.enqueueJob(jobId));}
    @PostMapping("/outbox/{id}/retry") void retry(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@PathVariable UUID id){authorize(key,auth);hub.retry(id);}
    @PostMapping("/outbox/send-ready") Map<String,Object> send(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@RequestParam(defaultValue="100") int limit){authorize(key,auth);return Map.of("processed",hub.sendReady(limit));}
    @GetMapping("/audit") List<Map<String,Object>> audit(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@RequestParam(defaultValue="200") int limit){authorize(key,auth);return hub.rows("SELECT * FROM integration_audit_log ORDER BY created_at DESC LIMIT ?",limit);}
    @GetMapping("/merges") List<Map<String,Object>> merges(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@RequestParam(defaultValue="200") int limit){authorize(key,auth);return hub.rows("SELECT * FROM job_merge_history ORDER BY merged_at DESC LIMIT ?",limit);}
    @GetMapping("/ai/status") AiExtensionStatus aiStatus(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth){authorize(key,auth);return new AiExtensionStatus(aiEnabled,ai.providerName(),aiGlobal,aiPerDomain,aiBatch,aiMaximum,ai.configured());}
    @PostMapping("/ai/discover") AiDiscoveryResult aiDiscover(@RequestHeader(name="X-Admin-Key",required=false) String key,Authentication auth,@RequestBody(required=false) AiDiscoveryRequest request){authorize(key,auth);if(!aiEnabled||!ai.configured())throw new ResponseStatusException(HttpStatus.CONFLICT,"AI provider is not configured");return ai.discover(request==null?"NEW_JOBS":request.mode());}

    private static String safeStatus(String value) {
        String normalized=value==null?"PENDING":value.toUpperCase();
        return switch(normalized){case "PENDING","APPROVED","REJECTED"->normalized;default->"PENDING";};
    }
}
