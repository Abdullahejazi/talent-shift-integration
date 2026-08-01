package com.talentshift.hub.integration.review;

import com.talentshift.hub.integration.exception.*;
import com.talentshift.hub.integration.importer.CanonicalizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReviewService {
    private final JdbcTemplate db; private final CanonicalizationService canonicalization;
    public ReviewService(JdbcTemplate db,CanonicalizationService canonicalization){this.db=db;this.canonicalization=canonicalization;}
    @Transactional public void decide(UUID id,boolean approve,String reviewer,String note){
        Map<String,Object> c=db.query("select * from deduplication_candidate where id=? for update",(rs,n)->{
            Map<String,Object> m=new HashMap<>();m.put("status",rs.getString("status"));m.put("type",rs.getString("entity_type"));
            m.put("raw",rs.getObject("raw_record_id",UUID.class));m.put("canonical",rs.getObject("proposed_canonical_id",UUID.class));
            m.put("reason",rs.getString("reason"));m.put("confidence",rs.getDouble("confidence"));return m;},id).stream()
                .findFirst().orElseThrow(()->new NotFoundException("Candidate not found: "+id));
        if(!"PENDING".equals(c.get("status")))throw new ConflictException("Candidate has already been reviewed");
        if(approve)canonicalization.approveCandidate((String)c.get("type"),(UUID)c.get("raw"),(UUID)c.get("canonical"),"MANUAL_"+c.get("reason"),(double)c.get("confidence"));
        else canonicalization.rejectCandidate((String)c.get("type"),(UUID)c.get("raw"));
        db.update("update deduplication_candidate set status=?,reviewed_by=?,review_note=?,reviewed_at=now() where id=?",
                approve?"APPROVED":"REJECTED",reviewer,note,id);
    }
}
