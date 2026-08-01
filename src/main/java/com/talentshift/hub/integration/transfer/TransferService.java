package com.talentshift.hub.integration.transfer;

import com.fasterxml.jackson.databind.*;
import com.talentshift.hub.integration.client.TalentShiftClient;
import com.talentshift.hub.integration.exception.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TransferService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TalentShiftClient client;
    public TransferService(JdbcTemplate db,ObjectMapper json,TalentShiftClient client){this.db=db;this.json=json;this.client=client;}
    @Transactional public UUID enqueueJob(UUID jobId){
        Map<String,Object> payload=db.query("select * from canonical_job where id=?",(rs,n)->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getObject("id"));m.put("sourceId",rs.getObject("canonical_source_id"));
            m.put("title",rs.getString("title"));m.put("organizationName",rs.getString("organization_name"));m.put("location",rs.getString("location"));
            m.put("employmentType",rs.getString("employment_type"));m.put("requisitionId",rs.getString("requisition_id"));
            m.put("jobUrl",rs.getString("normalized_job_url"));m.put("applyUrl",rs.getString("normalized_apply_url"));return m;},jobId).stream()
                .findFirst().orElseThrow(()->new NotFoundException("Canonical job not found: "+jobId));
        String key="canonical-job:"+jobId+":v1";UUID id=UUID.randomUUID();
        try{db.update("insert into talent_shift_outbox(id,aggregate_type,aggregate_id,payload,idempotency_key) values (?,?,?,?::jsonb,?) on conflict(idempotency_key) do nothing",id,"JOB",jobId,json.writeValueAsString(payload),key);}
        catch(Exception e){throw new IllegalStateException(e);}
        return db.queryForObject("select id from talent_shift_outbox where idempotency_key=?",UUID.class,key);
    }
    @Transactional public void retry(UUID id){
        int changed=db.update("update talent_shift_outbox set status='PENDING',next_attempt_at=now(),last_error=null where id=? and status in ('FAILED','RETRY')",id);
        if(changed==0 && db.queryForObject("select count(*) from talent_shift_outbox where id=?",Integer.class,id)==0)throw new NotFoundException("Outbox record not found: "+id);
    }
    @Transactional public int sendReady(int limit){
        List<Map<String,Object>> ready=db.queryForList("""
            select id,payload::text,idempotency_key,attempt_count from talent_shift_outbox
            where status in ('PENDING','RETRY') and next_attempt_at<=now() order by created_at
            for update skip locked limit ?
            """,limit);
        for(var row:ready)send(row);return ready.size();
    }
    private void send(Map<String,Object> row){
        UUID id=(UUID)row.get("id");int number=(int)row.get("attempt_count")+1;UUID attempt=UUID.randomUUID();
        db.update("update talent_shift_outbox set status='SENDING',attempt_count=? where id=?",number,id);
        db.update("insert into transfer_attempt(id,outbox_id,attempt_number,idempotency_key,status) values (?,?,?,?,?)",attempt,id,number,row.get("idempotency_key"),"STARTED");
        try{
            JsonNode payload=json.readTree((String)row.get("payload"));var response=client.transfer((String)row.get("idempotency_key"),payload);
            String status=response.success()?"SENT":number>=5?"FAILED":"RETRY";
            db.update("update transfer_attempt set status=?,response_code=?,response_body=?,completed_at=now() where id=?",status,response.statusCode(),response.body(),attempt);
            if(response.success())db.update("update talent_shift_outbox set status='SENT',sent_at=now(),last_error=null where id=?",id);
            else db.update("update talent_shift_outbox set status=?,next_attempt_at=?,last_error=? where id=?",status,Timestamp.from(Instant.now().plusSeconds((long)Math.pow(2,number)*30)),response.body(),id);
        }catch(Exception e){
            String status=number>=5?"FAILED":"RETRY";db.update("update transfer_attempt set status=?,response_body=?,completed_at=now() where id=?",status,e.getMessage(),attempt);
            db.update("update talent_shift_outbox set status=?,next_attempt_at=?,last_error=? where id=?",status,Timestamp.from(Instant.now().plusSeconds((long)Math.pow(2,number)*30)),e.getMessage(),id);
        }
    }
}
