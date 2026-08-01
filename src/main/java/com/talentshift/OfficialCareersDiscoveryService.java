package com.talentshift;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

record CareersDiscoveryResult(int checked, int promoted, int blocked, int unresolved) {}

/** Discovers public careers pages from employer-owned homepages without using AI/API credits. */
@Service
class OfficialCareersDiscoveryService {
    private static final Set<String> ATS_HOSTS = Set.of("jobs.lever.co", "boards.greenhouse.io",
            "job-boards.greenhouse.io", "jobs.ashbyhq.com", "jobs.smartrecruiters.com");
    private final JdbcClient jdbc;
    private final SafeSourceHttpClient http;
    private final int batchSize;
    private final int refreshMinutes;
    private final AtomicBoolean running = new AtomicBoolean();

    OfficialCareersDiscoveryService(JdbcClient jdbc, SafeSourceHttpClient http,
            @Value("${app.jobs.careers-discovery-batch-size:25}") int batchSize,
            @Value("${app.jobs.seed-refresh-minutes:10}") int refreshMinutes) {
        this.jdbc=jdbc; this.http=http;
        this.batchSize=Math.max(1,batchSize);
        this.refreshMinutes=Math.max(5,Math.min(refreshMinutes,10080));
    }

    @Scheduled(fixedDelayString="${app.jobs.careers-discovery-poll-ms:600000}",
            initialDelayString="${app.jobs.careers-discovery-initial-delay-ms:30000}")
    void scheduledDiscovery() { discoverNextBatch(); }

    CareersDiscoveryResult discoverNextBatch() {
        if (!running.compareAndSet(false,true)) return new CareersDiscoveryResult(0,0,0,0);
        try {
            List<Candidate> candidates=jdbc.sql("""
                    SELECT id,organization_name,source_url,official_domain FROM seed_candidates
                    WHERE enabled=false AND coalesce(verification_status,'') NOT IN ('VERIFIED_ACTIVE','OFFICIAL_CAREERS_URL_VERIFIED')
                      AND permission_status <> 'BLOCKED'
                    ORDER BY CASE WHEN source_url ~* '(greenhouse|lever|ashby|smartrecruiters)' THEN 0 ELSE 1 END,
                      searched_at NULLS FIRST, imported_at DESC
                    LIMIT :limit
                    """).param("limit",batchSize).query((rs,n)->new Candidate(rs.getObject("id",UUID.class),
                    rs.getString("organization_name"),rs.getString("source_url"),rs.getString("official_domain"))).list();
            int promoted=0,blocked=0,unresolved=0;
            for(Candidate candidate:candidates) {
                Outcome outcome=inspect(candidate);
                if(outcome==Outcome.PROMOTED)promoted++; else if(outcome==Outcome.BLOCKED)blocked++; else unresolved++;
            }
            return new CareersDiscoveryResult(candidates.size(),promoted,blocked,unresolved);
        } finally { running.set(false); }
    }

    private Outcome inspect(Candidate candidate) {
        URI home;
        try { home=URI.create(candidate.sourceUrl()); }
        catch(RuntimeException invalid){ mark(candidate.id(),"INVALID_URL","BLOCKED","INVALID_URL",false);return Outcome.BLOCKED; }
        try {
            SourceKind directAts=classifyAts(home);
            if(directAts!=null){
                if(!activeAtsBoard(home,directAts)){mark(candidate.id(),"ATS_BOARD_INACTIVE_OR_INVALID","NOT_YET_APPROVED","PUBLIC_API_CHECKED",false);return Outcome.UNRESOLVED;}
                promote(candidate,canonicalBoard(home,directAts),directAts);
                return Outcome.PROMOTED;
            }
            if(!http.robotsAllowed(home)){mark(candidate.id(),"ROBOTS_BLOCKED","BLOCKED","ROBOTS_DISALLOWED",false);return Outcome.BLOCKED;}
            SourceHttpResponse response=http.get(home,null);
            Document page=Jsoup.parse(response.body(),home.toString());
            for(URI url:careerLinks(page,home,candidate.officialDomain())) {
                if(!http.robotsAllowed(url))continue;
                SourceHttpResponse careers=http.get(url,null);
                Document careersPage=Jsoup.parse(careers.body(),url.toString());
                SourceKind kind=classify(url,careersPage);
                if(kind==null)continue;
                promote(candidate,url,kind);
                return Outcome.PROMOTED;
            }
            mark(candidate.id(),"NO_PUBLIC_CAREERS_URL","NOT_YET_APPROVED","NO_CAREERS_LINK_FOUND",false);
            return Outcome.UNRESOLVED;
        } catch(SourceHttpException failure) {
            boolean permanent=!failure.retryable();
            mark(candidate.id(),failure.code(),permanent?"BLOCKED":"NOT_YET_APPROVED",failure.code(),false);
            return permanent?Outcome.BLOCKED:Outcome.UNRESOLVED;
        } catch(RuntimeException failure) {
            mark(candidate.id(),"DISCOVERY_PROCESSING_ERROR","NOT_YET_APPROVED","RETRY_REQUIRED",false);
            return Outcome.UNRESOLVED;
        }
    }

    private List<URI> careerLinks(Document page,URI home,String allowedDomain) {
        String official=allowedDomain==null||allowedDomain.isBlank()?home.getHost():allowedDomain;
        Set<URI> links=new LinkedHashSet<>();
        for(Element a:page.select("a[href]")) {
            String label=(a.text()+" "+a.attr("aria-label")+" "+a.attr("href")).toLowerCase(Locale.ROOT);
            if(!label.matches(".*(career|careers|job|jobs|vacanc|join[-_ ]?us|work[-_ ]?with[-_ ]?us|فرص|وظائ).*"))continue;
            try {
                URI url=home.resolve(a.attr("href"));
                if(isOfficial(url,official)||ATS_HOSTS.contains(lower(url.getHost())))links.add(clean(url));
            } catch(RuntimeException ignored) { }
        }
        return links.stream().sorted(Comparator.comparingInt(this::linkScore)).limit(12).toList();
    }

    private SourceKind classify(URI url,Document page) {
        SourceKind ats=classifyAts(url);if(ats!=null)return ats;
        boolean structured=!page.select("script[type=application/ld+json]").isEmpty();
        boolean jobLinks=page.select("a[href*=job],a[href*=position],a[href*=vacanc]").size()>0;
        return structured||jobLinks?new SourceKind(structured?"JSON_LD":"GENERIC_HTML",null):null;
    }

    private SourceKind classifyAts(URI url){
        String host=lower(url.getHost()),path=url.getPath()==null?"":url.getPath();String token=firstSegment(path);
        if((host.equals("boards.greenhouse.io")||host.equals("job-boards.greenhouse.io"))&&token!=null)return new SourceKind("GREENHOUSE",token);
        if(host.equals("boards-api.greenhouse.io")){String board=segmentAfter(path,"boards");if(board!=null)return new SourceKind("GREENHOUSE",board);}
        if(host.equals("jobs.lever.co")&&token!=null)return new SourceKind("LEVER",token);
        if(host.equals("api.lever.co")){String board=segmentAfter(path,"postings");if(board!=null)return new SourceKind("LEVER",board);}
        if(host.equals("jobs.ashbyhq.com")&&token!=null)return new SourceKind("ASHBY",token);
        if(host.equals("api.ashbyhq.com")){String board=segmentAfter(path,"job-board");if(board!=null)return new SourceKind("ASHBY",board);}
        if(host.equals("jobs.smartrecruiters.com")&&token!=null)return new SourceKind("SMARTRECRUITERS",token);
        return null;
    }

    private URI canonicalBoard(URI original,SourceKind kind){return switch(kind.type()){
        case "GREENHOUSE"->URI.create("https://job-boards.greenhouse.io/"+kind.board());
        case "LEVER"->URI.create("https://jobs.lever.co/"+kind.board());
        case "ASHBY"->URI.create("https://jobs.ashbyhq.com/"+kind.board());
        case "SMARTRECRUITERS"->URI.create("https://jobs.smartrecruiters.com/"+kind.board());
        default->original;};}

    private boolean activeAtsBoard(URI board,SourceKind kind){
        try {
        URI endpoint=switch(kind.type()){
            case "GREENHOUSE"->URI.create("https://boards-api.greenhouse.io/v1/boards/"+kind.board()+"/jobs");
            case "LEVER"->URI.create("https://api.lever.co/v0/postings/"+kind.board()+"?mode=json");
            case "ASHBY"->URI.create("https://api.ashbyhq.com/posting-api/job-board/"+kind.board());
            case "SMARTRECRUITERS"->URI.create("https://api.smartrecruiters.com/v1/companies/"+kind.board()+"/postings?limit=100");
            default->board;
        };
        String body=http.get(endpoint,null).body();String compact=body.replaceAll("\\s+","");
        return switch(kind.type()){
            case "GREENHOUSE"->compact.matches(".*\\\"jobs\\\":\\[\\{.*")&&compact.contains("\"absolute_url\"");
            case "LEVER"->compact.startsWith("[{")&&(compact.contains("\"applyUrl\"")||compact.contains("\"hostedUrl\""));
            case "ASHBY"->compact.matches(".*\\\"jobs\\\":\\[\\{.*")&&compact.contains("\"applyUrl\"");
            case "SMARTRECRUITERS"->compact.matches(".*\\\"content\\\":\\[\\{.*")&&compact.contains("\"id\"");
            default->false;
        };
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Transactional
    void promote(Candidate candidate,URI careers,SourceKind kind) {
        String slug=slug(candidate.organization());
        UUID companyId=jdbc.sql("""
                INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
                VALUES (:slug,:name,:website,:careers,:type,true,true)
                ON CONFLICT (slug) DO UPDATE SET website_url=EXCLUDED.website_url,careers_url=EXCLUDED.careers_url,
                  source_type=EXCLUDED.source_type,automated=true,active=true
                RETURNING id
                """).param("slug",slug).param("name",candidate.organization()).param("website",candidate.sourceUrl())
                .param("careers",careers.toString()).param("type",kind.board()==null?"OFFICIAL_CAREER_SITE":"PUBLIC_ATS_API")
                .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,
                  country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
                VALUES (:company,:name,:url,:type,:provider,:board,'SA',true,'PUBLIC_ALLOWED',:refresh,now())
                ON CONFLICT (lower(careers_url)) DO UPDATE SET enabled=true,permission_status='PUBLIC_ALLOWED',
                  refresh_interval_minutes=EXCLUDED.refresh_interval_minutes,next_retry_at=now(),updated_at=now()
                """).param("company",companyId).param("name",candidate.organization()).param("url",careers.toString())
                .param("type",kind.type()).param("provider",kind.board()==null?null:kind.type())
                .param("board",kind.board()).param("refresh",refreshMinutes).update();
        jdbc.sql("""
                UPDATE seed_candidates SET source_url=:url,source_type=:type,permission_status='PUBLIC_ALLOWED',
                  verification_status='VERIFIED_ACTIVE',terms_match_status='PUBLIC_API_OR_ROBOTS_ALLOWED',
                  enabled=true,searched_at=now() WHERE id=:id
                """).param("url",careers.toString()).param("type",kind.type()).param("id",candidate.id()).update();
    }

    private void mark(UUID id,String verification,String permission,String terms,boolean enabled){jdbc.sql("""
            UPDATE seed_candidates SET searched_at=now(),verification_status=:verification,
              permission_status=:permission,terms_match_status=:terms,enabled=:enabled WHERE id=:id
            """).param("verification",verification).param("permission",permission).param("terms",terms)
            .param("enabled",enabled).param("id",id).update();}
    private int linkScore(URI u){String p=lower(u.getPath());if(ATS_HOSTS.contains(lower(u.getHost())))return 0;if(p.matches(".*/(careers?|jobs?)/?$"))return 1;return 2;}
    private static boolean isOfficial(URI uri,String official){String h=lower(uri.getHost()),o=lower(official);return h.equals(o)||h.endsWith("."+o);}
    private static URI clean(URI u){return URI.create(u.getScheme()+"://"+u.getAuthority()+u.getPath()+(u.getQuery()==null?"":"?"+u.getQuery()));}
    private static String firstSegment(String path){for(String s:path.split("/"))if(!s.isBlank())return s;return null;}
    private static String segmentAfter(String path,String marker){String[] parts=path.split("/");for(int i=0;i<parts.length-1;i++)if(parts[i].equalsIgnoreCase(marker)&&!parts[i+1].isBlank())return parts[i+1];return null;}
    private static String slug(String value){String s=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");return (s.isBlank()?"company":s)+"-"+AuthService.hash(value).substring(0,8);}
    private static String lower(String value){return value==null?"":value.toLowerCase(Locale.ROOT);}
    private record Candidate(UUID id,String organization,String sourceUrl,String officialDomain) {}
    private record SourceKind(String type,String board) {}
    private enum Outcome { PROMOTED,BLOCKED,UNRESOLVED }
}
