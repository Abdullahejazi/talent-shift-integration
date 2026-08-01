package com.talentshift.hub.integration.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentshift.hub.integration.client.*;
import com.talentshift.hub.integration.deduplication.job.*;
import com.talentshift.hub.integration.deduplication.source.SourceMatchPolicy;
import com.talentshift.hub.integration.normalization.NormalizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CanonicalizationService {
    private final JdbcTemplate db; private final ObjectMapper json; private final NormalizationService norm;
    private final SourceMatchPolicy sourcePolicy; private final JobMatchPolicy jobPolicy; private final JobFingerprint fingerprints;
    public CanonicalizationService(JdbcTemplate db,ObjectMapper json,NormalizationService norm,SourceMatchPolicy sourcePolicy,
            JobMatchPolicy jobPolicy,JobFingerprint fingerprints){this.db=db;this.json=json;this.norm=norm;this.sourcePolicy=sourcePolicy;this.jobPolicy=jobPolicy;this.fingerprints=fingerprints;}

    @Transactional public void processSources(UUID batch) {
        for(Map<String,Object> row: db.queryForList("select id,connected_system_id,external_record_id,payload::text payload from raw_source where import_batch_id=? and processing_status='PENDING' order by received_at for update skip locked",batch))
            processSource(row);
    }
    private void processSource(Map<String,Object> row) {
        UUID raw=(UUID)row.get("id"), system=(UUID)row.get("connected_system_id"); String ext=(String)row.get("external_record_id");
        try {
            SourceRecordDto s=json.readValue((String)row.get("payload"),SourceRecordDto.class);
            String url=norm.url(s.url()),domain=norm.domain(s.officialDomain()),org=norm.text(s.organizationName());
            List<Map<String,Object>> candidates=db.queryForList("""
                select id,normalized_url,official_domain,organization_name_normalized,ats_provider,ats_tenant_id
                from canonical_source where normalized_url is not distinct from ? or lower(official_domain)=lower(?)
                or organization_name_normalized=? or (lower(ats_provider)=lower(?) and ats_tenant_id=?) for update
                """,url,domain,org,s.atsProvider(),s.atsTenantId());
            Map<String,Object> best=null; SourceMatchPolicy.Decision decision=null;
            for(var c:candidates){var d=sourcePolicy.decide(url,(String)c.get("normalized_url"),domain,(String)c.get("official_domain"),org,(String)c.get("organization_name_normalized"),s.atsProvider(),(String)c.get("ats_provider"),s.atsTenantId(),(String)c.get("ats_tenant_id")); if(decision==null||d.confidence()>decision.confidence()){best=c;decision=d;}}
            if(best!=null && decision.automatic()) linkSource(raw,system,ext,(UUID)best.get("id"),decision.reason(),decision.confidence());
            else if(best!=null && decision.confidence()>=.75) candidate("SOURCE",raw,(UUID)best.get("id"),decision.reason(),decision.confidence());
            else linkSource(raw,system,ext,createSource(s,url,domain,org),"NEW_CANONICAL",1);
        } catch(Exception e){throw new IllegalStateException("Cannot process raw source "+raw,e);}
    }
    private UUID createSource(SourceRecordDto s,String url,String domain,String org){
        UUID orgId=UUID.randomUUID(),sourceId=UUID.randomUUID();
        db.update("insert into canonical_organization(id,normalized_name,display_name,official_domain) values (?,?,?,?) on conflict do nothing",orgId,org==null?norm.text(s.name()):org,s.organizationName()==null?s.name():s.organizationName(),domain);
        orgId=db.queryForObject("select id from canonical_organization where normalized_name=? and coalesce(official_domain,'')=coalesce(?,'')",UUID.class,org==null?norm.text(s.name()):org,domain);
        db.update("insert into canonical_source(id,canonical_organization_id,display_name,normalized_url,official_domain,organization_name_normalized,ats_provider,ats_tenant_id,source_type) values (?,?,?,?,?,?,?,?,?)",
                sourceId,orgId,s.name(),url,domain,org,s.atsProvider(),s.atsTenantId(),s.sourceType()); return sourceId;
    }
    private void linkSource(UUID raw,UUID system,String ext,UUID canonical,String reason,double confidence){
        db.update("insert into source_observation(id,canonical_source_id,raw_source_id,connected_system_id,external_record_id,duplicate_reason,confidence) values (?,?,?,?,?,?,?) on conflict(raw_source_id) do nothing",UUID.randomUUID(),canonical,raw,system,ext,reason,confidence);
        db.update("update raw_source set processing_status='PROCESSED' where id=?",raw);
    }
    @Transactional public void processJobs(UUID batch){
        for(Map<String,Object> row:db.queryForList("select id,connected_system_id,external_record_id,payload::text payload from raw_job where import_batch_id=? and processing_status='PENDING' order by received_at for update skip locked",batch)) processJob(row);
    }
    private void processJob(Map<String,Object> row){
        UUID raw=(UUID)row.get("id"),system=(UUID)row.get("connected_system_id");String ext=(String)row.get("external_record_id");
        try{
            JobRecordDto j=json.readValue((String)row.get("payload"),JobRecordDto.class);
            UUID source=db.query("select canonical_source_id from source_observation where connected_system_id=? and external_record_id=?",(rs,n)->rs.getObject(1,UUID.class),system,j.sourceExternalRecordId()).stream().findFirst().orElse(null);
            if(source==null){db.update("update raw_job set processing_status='WAITING_FOR_SOURCE' where id=?",raw);return;}
            String jobUrl=norm.url(j.jobUrl()),applyUrl=norm.url(j.applyUrl()),fp=fingerprints.of(j);
            List<Map<String,Object>> candidates=db.queryForList("""
                select cj.id,cj.normalized_job_url,cj.normalized_apply_url,cj.fingerprint,cj.title,cj.location,
                exists(select 1 from job_observation jo where jo.canonical_job_id=cj.id and jo.external_record_id=?) same_external
                from canonical_job cj where cj.canonical_source_id=? or cj.normalized_job_url is not distinct from ?
                or cj.normalized_apply_url is not distinct from ? or cj.fingerprint=? or similarity(lower(cj.title),lower(?)) > .98 for update
                """,ext,source,jobUrl,applyUrl,fp,j.title());
            Map<String,Object> best=null;JobMatchPolicy.Decision decision=null;
            for(var c:candidates){var d=jobPolicy.decide(Boolean.TRUE.equals(c.get("same_external")),jobUrl,(String)c.get("normalized_job_url"),applyUrl,(String)c.get("normalized_apply_url"),fp,(String)c.get("fingerprint"),j.title(),(String)c.get("title"),j.location(),(String)c.get("location"));if(decision==null||d.confidence()>decision.confidence()){best=c;decision=d;}}
            if(best!=null&&decision.automatic())linkJob(raw,system,ext,(UUID)best.get("id"),decision.reason(),decision.confidence());
            else if(best!=null&&decision.review())candidate("JOB",raw,(UUID)best.get("id"),decision.reason(),decision.confidence());
            else linkJob(raw,system,ext,createJob(source,j,jobUrl,applyUrl,fp),"NEW_CANONICAL",1);
        }catch(Exception e){throw new IllegalStateException("Cannot process raw job "+raw,e);}
    }
    private UUID createJob(UUID source,JobRecordDto j,String url,String apply,String fp){
        UUID id=UUID.randomUUID();db.update("insert into canonical_job(id,canonical_source_id,title,organization_name,location,employment_type,requisition_id,normalized_job_url,normalized_apply_url,fingerprint) values (?,?,?,?,?,?,?,?,?,?)",id,source,j.title(),j.organizationName(),j.location(),norm.text(j.employmentType()),j.requisitionId(),url,apply,fp);return id;
    }
    private void linkJob(UUID raw,UUID system,String ext,UUID canonical,String reason,double confidence){
        db.update("insert into job_observation(id,canonical_job_id,raw_job_id,connected_system_id,external_record_id,duplicate_reason,confidence) values (?,?,?,?,?,?,?) on conflict(raw_job_id) do nothing",UUID.randomUUID(),canonical,raw,system,ext,reason,confidence);
        db.update("update raw_job set processing_status='PROCESSED' where id=?",raw);
    }
    private void candidate(String type,UUID raw,UUID canonical,String reason,double confidence){
        db.update("insert into deduplication_candidate(id,entity_type,raw_record_id,proposed_canonical_id,reason,confidence) values (?,?,?,?,?,?) on conflict do nothing",UUID.randomUUID(),type,raw,canonical,reason,confidence);
        db.update("update raw_"+type.toLowerCase()+" set processing_status='REVIEW' where id=?",raw);
    }
    public void approveCandidate(String type,UUID raw,UUID canonical,String reason,double confidence){
        Map<String,Object> r=db.queryForMap("select connected_system_id,external_record_id from raw_"+type.toLowerCase()+" where id=? for update",raw);
        if(type.equals("SOURCE"))linkSource(raw,(UUID)r.get("connected_system_id"),(String)r.get("external_record_id"),canonical,reason,confidence);
        else linkJob(raw,(UUID)r.get("connected_system_id"),(String)r.get("external_record_id"),canonical,reason,confidence);
    }
    public void rejectCandidate(String type,UUID raw){
        Map<String,Object> r=db.queryForMap("select connected_system_id,external_record_id,payload::text payload from raw_"+type.toLowerCase()+" where id=? for update",raw);
        try{if(type.equals("SOURCE")){var s=json.readValue((String)r.get("payload"),SourceRecordDto.class);linkSource(raw,(UUID)r.get("connected_system_id"),(String)r.get("external_record_id"),createSource(s,norm.url(s.url()),norm.domain(s.officialDomain()),norm.text(s.organizationName())),"REVIEW_REJECTED_NEW",1);}
        else{var j=json.readValue((String)r.get("payload"),JobRecordDto.class);UUID source=db.queryForObject("select canonical_source_id from source_observation where connected_system_id=? and external_record_id=?",UUID.class,r.get("connected_system_id"),j.sourceExternalRecordId());linkJob(raw,(UUID)r.get("connected_system_id"),(String)r.get("external_record_id"),createJob(source,j,norm.url(j.jobUrl()),norm.url(j.applyUrl()),fingerprints.of(j)),"REVIEW_REJECTED_NEW",1);}}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
