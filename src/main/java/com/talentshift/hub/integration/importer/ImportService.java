package com.talentshift.hub.integration.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentshift.hub.integration.client.*;
import com.talentshift.hub.integration.config.HubProperties;
import com.talentshift.hub.integration.exception.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class ImportService {
    private final JdbcTemplate db; private final ObjectMapper json; private final Map<String,JobSystemProvider> providers;
    private final int pageSize; private final CanonicalizationService canonicalization;
    public ImportService(JdbcTemplate db, ObjectMapper json, List<JobSystemProvider> providers, HubProperties props,
                         CanonicalizationService canonicalization) {
        this.db=db; this.json=json; this.providers=new HashMap<>(); providers.forEach(p -> this.providers.put(p.systemKey(),p));
        this.pageSize=props.imports().pageSize(); this.canonicalization=canonicalization;
    }
    @Transactional
    public UUID start(String systemKey) {
        JobSystemProvider provider = Optional.ofNullable(providers.get(systemKey))
                .orElseThrow(() -> new NotFoundException("No provider configured for "+systemKey));
        db.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtext(?))) locked", Integer.class, systemKey);
        UUID systemId = ensureSystem(systemKey), batchId=UUID.randomUUID();
        String sourceCursor=lockCheckpoint(systemId,"SOURCE"), jobCursor=lockCheckpoint(systemId,"JOB");
        db.update("insert into import_batch(id,connected_system_id,status,source_cursor_start,job_cursor_start) values (?,?,?,?,?)",
                batchId,systemId,"RUNNING",sourceCursor,jobCursor);
        int sources=0,jobs=0;
        try {
            CursorPage<SourceRecordDto> sourcePage;
            do {
                sourcePage=provider.fetchSources(sourceCursor,pageSize);
                for(var item:sourcePage.items()) if(insertRaw("raw_source",batchId,systemId,item.externalRecordId(),item)) sources++;
                sourceCursor=sourcePage.nextCursor(); saveCheckpoint(systemId,"SOURCE",sourceCursor);
            } while(sourcePage.hasMore());
            canonicalization.processSources(batchId);
            CursorPage<JobRecordDto> jobPage;
            do {
                jobPage=provider.fetchJobs(jobCursor,pageSize);
                for(var item:jobPage.items()) if(insertRaw("raw_job",batchId,systemId,item.externalRecordId(),item)) jobs++;
                jobCursor=jobPage.nextCursor(); saveCheckpoint(systemId,"JOB",jobCursor);
            } while(jobPage.hasMore());
            canonicalization.processJobs(batchId);
            db.update("update import_batch set status='COMPLETED',source_cursor_end=?,job_cursor_end=?,imported_sources=?,imported_jobs=?,completed_at=now() where id=?",
                    sourceCursor,jobCursor,sources,jobs,batchId);
            return batchId;
        } catch(RuntimeException e) {
            db.update("update import_batch set status='FAILED',error_message=?,completed_at=now() where id=?",e.getMessage(),batchId);
            throw e;
        }
    }
    private UUID ensureSystem(String key) {
        return db.query("select id from connected_system where system_key=?", (rs,n)->rs.getObject(1,UUID.class),key).stream().findFirst()
                .orElseGet(()->{UUID id=UUID.randomUUID(); db.update("insert into connected_system(id,system_key,display_name) values (?,?,?) on conflict(system_key) do nothing",id,key,key); return db.queryForObject("select id from connected_system where system_key=?",UUID.class,key);});
    }
    private String lockCheckpoint(UUID systemId,String type) {
        db.update("insert into sync_checkpoint(id,connected_system_id,record_type) values (?,?,?) on conflict do nothing",UUID.randomUUID(),systemId,type);
        return db.queryForObject("select cursor_value from sync_checkpoint where connected_system_id=? and record_type=? for update",
                (rs,n)->rs.getString(1),systemId,type);
    }
    private void saveCheckpoint(UUID systemId,String type,String cursor) {
        db.update("update sync_checkpoint set cursor_value=?,last_success_at=now(),version=version+1 where connected_system_id=? and record_type=?",cursor,systemId,type);
    }
    private boolean insertRaw(String table,UUID batch,UUID system,String external,Object payload) {
        try {
            String body=json.writeValueAsString(payload), checksum=sha256(body);
            return db.update("insert into "+table+"(id,import_batch_id,connected_system_id,external_record_id,payload,checksum) values (?,?,?,?,?::jsonb,?) on conflict do nothing",
                    UUID.randomUUID(),batch,system,external,body,checksum)>0;
        } catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private String sha256(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
