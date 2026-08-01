package com.talentshift;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

record WorkspacePreferences(boolean newMatchingJobs,boolean interviewReminders,boolean employerMessages) {}
record WorkspaceApplication(UUID id,UUID jobId,String company,String roleTitle,String status,OffsetDateTime appliedAt,OffsetDateTime updatedAt) {}
record WorkspaceMeeting(UUID id,String title,String company,OffsetDateTime startsAt,String meetingUrl,String notes) {}
record WorkspaceConversation(UUID id,String participant,String subject,OffsetDateTime updatedAt,long unreadCount,String lastMessage) {}
record WorkspaceMessage(UUID id,String sender,String body,OffsetDateTime sentAt) {}
record WorkspaceAnalytics(long savedJobs,long applications,long interviews,long offers,long meetings,long unreadMessages) {}
record ApplicationStatus(String status) {}
record MeetingRequest(String title,String company,OffsetDateTime startsAt,String meetingUrl,String notes) {}
record MessageRequest(String body) {}

@RestController
@RequestMapping("/api/workspace")
class WorkspaceController {
    private final JdbcClient jdbc;
    WorkspaceController(JdbcClient jdbc){this.jdbc=jdbc;}

    @GetMapping("/saved-jobs")
    List<JobView> saved(Authentication authentication){UUID user=user(authentication);return jdbc.sql("""
            SELECT j.id,j.source,j.title,j.company,j.location,j.country_code,j.employment_type,j.remote,j.salary,j.category,
              j.description,j.requirements,j.apply_url,j.source_url,j.posted_at,j.collected_at
            FROM user_saved_jobs s JOIN jobs j ON j.id=s.job_id
            WHERE s.user_id=:user AND j.status='ACTIVE' ORDER BY s.saved_at DESC
            """).param("user",user).query((rs,n)->new JobView(rs.getObject("id",UUID.class),rs.getString("source"),rs.getString("title"),rs.getString("company"),rs.getString("location"),rs.getString("country_code"),rs.getString("employment_type"),rs.getBoolean("remote"),rs.getString("salary"),rs.getString("category"),rs.getString("description"),rs.getString("requirements"),rs.getString("apply_url"),rs.getString("source_url"),instant(rs.getObject("posted_at",OffsetDateTime.class)),instant(rs.getObject("collected_at",OffsetDateTime.class)))).list();}

    @PostMapping("/saved-jobs/{jobId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void save(Authentication authentication,@PathVariable UUID jobId){jdbc.sql("INSERT INTO user_saved_jobs(user_id,job_id) VALUES (:user,:job) ON CONFLICT DO NOTHING").param("user",user(authentication)).param("job",jobId).update();}

    @DeleteMapping("/saved-jobs/{jobId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsave(Authentication authentication,@PathVariable UUID jobId){jdbc.sql("DELETE FROM user_saved_jobs WHERE user_id=:user AND job_id=:job").param("user",user(authentication)).param("job",jobId).update();}

    @GetMapping("/preferences")
    WorkspacePreferences preferences(Authentication authentication){UUID user=user(authentication);return jdbc.sql("""
            SELECT new_matching_jobs,interview_reminders,employer_messages FROM user_preferences WHERE user_id=:user
            """).param("user",user).query((rs,n)->new WorkspacePreferences(rs.getBoolean(1),rs.getBoolean(2),rs.getBoolean(3))).optional().orElse(new WorkspacePreferences(true,true,true));}

    @PutMapping("/preferences")
    WorkspacePreferences preferences(Authentication authentication,@RequestBody WorkspacePreferences request){UUID user=user(authentication);jdbc.sql("""
            INSERT INTO user_preferences(user_id,new_matching_jobs,interview_reminders,employer_messages)
            VALUES (:user,:jobs,:interviews,:messages)
            ON CONFLICT(user_id) DO UPDATE SET new_matching_jobs=EXCLUDED.new_matching_jobs,
              interview_reminders=EXCLUDED.interview_reminders,employer_messages=EXCLUDED.employer_messages,updated_at=now()
            """).param("user",user).param("jobs",request.newMatchingJobs()).param("interviews",request.interviewReminders()).param("messages",request.employerMessages()).update();return request;}

    @GetMapping("/applications")
    List<WorkspaceApplication> applications(Authentication authentication){return jdbc.sql("SELECT id,job_id,company,role_title,status,applied_at,updated_at FROM workspace_applications WHERE user_id=:user ORDER BY updated_at DESC").param("user",user(authentication)).query((rs,n)->new WorkspaceApplication(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class))).list();}

    @PostMapping("/applications/{jobId}")
    WorkspaceApplication apply(Authentication authentication,@PathVariable UUID jobId){UUID user=user(authentication);return jdbc.sql("""
            INSERT INTO workspace_applications(user_id,job_id,company,role_title)
            SELECT :user,id,company,title FROM jobs WHERE id=:job AND status='ACTIVE'
            RETURNING id,job_id,company,role_title,status,applied_at,updated_at
            """).param("user",user).param("job",jobId).query((rs,n)->new WorkspaceApplication(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class))).optional().orElseThrow(JobNotFoundException::new);}

    @PatchMapping("/applications/{id}")
    WorkspaceApplication applicationStatus(Authentication authentication,@PathVariable UUID id,@RequestBody ApplicationStatus request){String status=request.status()==null?"":request.status().trim().toUpperCase();if(!List.of("APPLIED","SCREENING","INTERVIEW","OFFER","REJECTED","WITHDRAWN").contains(status))throw new IllegalArgumentException("Invalid application status");return jdbc.sql("UPDATE workspace_applications SET status=:status,updated_at=now() WHERE id=:id AND user_id=:user RETURNING id,job_id,company,role_title,status,applied_at,updated_at").param("status",status).param("id",id).param("user",user(authentication)).query((rs,n)->new WorkspaceApplication(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class))).optional().orElseThrow(JobNotFoundException::new);}

    @GetMapping("/meetings")
    List<WorkspaceMeeting> meetings(Authentication authentication){return jdbc.sql("SELECT id,title,company,starts_at,meeting_url,notes FROM workspace_meetings WHERE user_id=:user ORDER BY starts_at").param("user",user(authentication)).query((rs,n)->new WorkspaceMeeting(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,OffsetDateTime.class),rs.getString(5),rs.getString(6))).list();}

    @PostMapping("/meetings")
    WorkspaceMeeting meeting(Authentication authentication,@RequestBody MeetingRequest request){if(request.title()==null||request.title().isBlank()||request.startsAt()==null)throw new IllegalArgumentException("Meeting title and start time are required");return jdbc.sql("INSERT INTO workspace_meetings(user_id,title,company,starts_at,meeting_url,notes) VALUES (:user,:title,:company,:starts,:url,:notes) RETURNING id,title,company,starts_at,meeting_url,notes").param("user",user(authentication)).param("title",request.title().trim()).param("company",request.company()).param("starts",request.startsAt()).param("url",request.meetingUrl()).param("notes",request.notes()).query((rs,n)->new WorkspaceMeeting(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,OffsetDateTime.class),rs.getString(5),rs.getString(6))).single();}

    @DeleteMapping("/meetings/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMeeting(Authentication authentication,@PathVariable UUID id){jdbc.sql("DELETE FROM workspace_meetings WHERE id=:id AND user_id=:user").param("id",id).param("user",user(authentication)).update();}

    @GetMapping("/conversations")
    List<WorkspaceConversation> conversations(Authentication authentication){return jdbc.sql("""
            SELECT c.id,c.participant,c.subject,c.updated_at,
              count(m.id) FILTER (WHERE m.sender <> 'USER' AND m.read_at IS NULL) unread,
              (SELECT body FROM workspace_messages x WHERE x.conversation_id=c.id ORDER BY sent_at DESC LIMIT 1) last_message
            FROM workspace_conversations c LEFT JOIN workspace_messages m ON m.conversation_id=c.id
            WHERE c.user_id=:user GROUP BY c.id ORDER BY c.updated_at DESC
            """).param("user",user(authentication)).query((rs,n)->new WorkspaceConversation(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,OffsetDateTime.class),rs.getLong(5),rs.getString(6))).list();}

    @GetMapping("/conversations/{id}/messages")
    List<WorkspaceMessage> messages(Authentication authentication,@PathVariable UUID id){UUID user=user(authentication);jdbc.sql("UPDATE workspace_messages SET read_at=now() WHERE conversation_id=:id AND sender <> 'USER' AND conversation_id IN (SELECT id FROM workspace_conversations WHERE user_id=:user)").param("id",id).param("user",user).update();return jdbc.sql("SELECT m.id,m.sender,m.body,m.sent_at FROM workspace_messages m JOIN workspace_conversations c ON c.id=m.conversation_id WHERE c.user_id=:user AND c.id=:id ORDER BY m.sent_at").param("user",user).param("id",id).query((rs,n)->new WorkspaceMessage(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,OffsetDateTime.class))).list();}

    @PostMapping("/conversations/{id}/messages")
    WorkspaceMessage message(Authentication authentication,@PathVariable UUID id,@RequestBody MessageRequest request){if(request.body()==null||request.body().isBlank())throw new IllegalArgumentException("Message cannot be empty");return jdbc.sql("INSERT INTO workspace_messages(conversation_id,sender,body) SELECT id,'USER',:body FROM workspace_conversations WHERE id=:id AND user_id=:user RETURNING id,sender,body,sent_at").param("body",request.body().trim()).param("id",id).param("user",user(authentication)).query((rs,n)->new WorkspaceMessage(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,OffsetDateTime.class))).optional().orElseThrow(JobNotFoundException::new);}

    @GetMapping("/analytics")
    WorkspaceAnalytics analytics(Authentication authentication){UUID user=user(authentication);return jdbc.sql("""
            SELECT (SELECT count(*) FROM user_saved_jobs WHERE user_id=:user),
              (SELECT count(*) FROM workspace_applications WHERE user_id=:user),
              (SELECT count(*) FROM workspace_applications WHERE user_id=:user AND status='INTERVIEW'),
              (SELECT count(*) FROM workspace_applications WHERE user_id=:user AND status='OFFER'),
              (SELECT count(*) FROM workspace_meetings WHERE user_id=:user AND starts_at>=now()),
              (SELECT count(*) FROM workspace_messages m JOIN workspace_conversations c ON c.id=m.conversation_id WHERE c.user_id=:user AND m.sender<>'USER' AND m.read_at IS NULL)
            """).param("user",user).query((rs,n)->new WorkspaceAnalytics(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6))).single();}

    private static UUID user(Authentication authentication){if(authentication==null||!(authentication.getPrincipal() instanceof AuthenticatedUser current))throw new AuthenticationRequiredException();return current.id();}
    private static java.time.Instant instant(OffsetDateTime value){return value==null?null:value.toInstant();}
}
