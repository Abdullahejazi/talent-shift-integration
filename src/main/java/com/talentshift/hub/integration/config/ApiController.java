package com.talentshift.hub.integration.config;

import com.talentshift.hub.integration.importer.ImportService;
import com.talentshift.hub.integration.review.ReviewService;
import com.talentshift.hub.integration.transfer.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final JdbcTemplate db;private final ImportService imports;private final ReviewService reviews;private final TransferService transfers;
    public ApiController(JdbcTemplate db,ImportService imports,ReviewService reviews,TransferService transfers){this.db=db;this.imports=imports;this.reviews=reviews;this.transfers=transfers;}

    @PostMapping("/imports/mock") @Operation(summary="Import all mock sources and jobs")
    public Map<String,Object> importMock(){UUID id=imports.start("mock-system");return Map.of("batchId",id,"systemKey","mock-system");}
    @PostMapping("/imports") @Operation(summary="Start a restartable cursor-based import batch")
    public Map<String,Object> start(@Valid @RequestBody StartImport request){return Map.of("batchId",imports.start(request.systemKey()));}
    @GetMapping("/raw/sources") public List<Map<String,Object>> rawSources(@RequestParam(defaultValue="20000") @Max(20000) int limit){return rows("select id,import_batch_id,connected_system_id,external_record_id,payload,checksum,processing_status,received_at from raw_source order by received_at desc limit ?",limit);}
    @GetMapping("/raw/jobs") public List<Map<String,Object>> rawJobs(@RequestParam(defaultValue="20000") @Max(20000) int limit){return rows("select id,import_batch_id,connected_system_id,external_record_id,payload,checksum,processing_status,received_at from raw_job order by received_at desc limit ?",limit);}
    @GetMapping("/canonical/sources") public List<Map<String,Object>> sources(@RequestParam(defaultValue="20000") @Max(20000) int limit){return rows("select * from canonical_source order by created_at desc limit ?",limit);}
    @GetMapping("/canonical/jobs") public List<Map<String,Object>> jobs(@RequestParam(defaultValue="20000") @Max(20000) int limit){return rows("select * from canonical_job order by created_at desc limit ?",limit);}
    @GetMapping("/deduplication/decisions") public List<Map<String,Object>> decisions(@RequestParam(defaultValue="100") @Max(500) int limit){
        return rows("""
            select 'SOURCE' entity_type,raw_source_id raw_record_id,canonical_source_id canonical_id,duplicate_reason reason,confidence,created_at from source_observation
            union all select 'JOB',raw_job_id,canonical_job_id,duplicate_reason,confidence,created_at from job_observation order by created_at desc limit ?""",limit);}
    @GetMapping("/reviews") public List<Map<String,Object>> candidates(@RequestParam(required=false) String status,@RequestParam(defaultValue="100") @Max(500) int limit){
        return status==null?rows("select * from deduplication_candidate order by created_at desc limit ?",limit):db.queryForList("select * from deduplication_candidate where status=? order by created_at desc limit ?",status.toUpperCase(),limit);}
    @PostMapping("/reviews/{id}/approve") public void approve(@PathVariable UUID id,@RequestBody(required=false) ReviewRequest request,Principal principal){reviews.decide(id,true,principal.getName(),request==null?null:request.note());}
    @PostMapping("/reviews/{id}/reject") public void reject(@PathVariable UUID id,@RequestBody(required=false) ReviewRequest request,Principal principal){reviews.decide(id,false,principal.getName(),request==null?null:request.note());}
    @GetMapping("/sync/status") public List<Map<String,Object>> sync(){return db.queryForList("select cs.system_key,cs.display_name,sc.record_type,sc.cursor_value,sc.last_success_at,sc.version from connected_system cs left join sync_checkpoint sc on sc.connected_system_id=cs.id order by cs.system_key,sc.record_type");}
    @GetMapping("/imports") public List<Map<String,Object>> batches(@RequestParam(defaultValue="100") @Max(500) int limit){return rows("select * from import_batch order by started_at desc limit ?",limit);}
    @PostMapping("/outbox/jobs/{jobId}") public Map<String,Object> enqueue(@PathVariable UUID jobId){return Map.of("outboxId",transfers.enqueueJob(jobId));}
    @GetMapping("/outbox") public List<Map<String,Object>> outbox(@RequestParam(defaultValue="100") @Max(500) int limit){return rows("select * from talent_shift_outbox order by created_at desc limit ?",limit);}
    @GetMapping("/outbox/{id}/attempts") public List<Map<String,Object>> attempts(@PathVariable UUID id){return db.queryForList("select * from transfer_attempt where outbox_id=? order by attempt_number",id);}
    @PostMapping("/outbox/{id}/retry") public void retry(@PathVariable UUID id){transfers.retry(id);}
    @PostMapping("/outbox/send-ready") public Map<String,Object> send(@RequestParam(defaultValue="100") @Max(500) int limit){return Map.of("processed",transfers.sendReady(limit));}
    private List<Map<String,Object>> rows(String sql,int limit){return db.queryForList(sql,limit);}
    public record StartImport(@NotBlank String systemKey){}
    public record ReviewRequest(@Size(max=2000) String note){}
}
