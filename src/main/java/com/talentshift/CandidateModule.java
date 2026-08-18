package com.talentshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

// ─── API Records ─────────────────────────────────────────────────────────────

record CandidateView(
    UUID   id,
    String profileUrl,
    String profileHandle,
    String profilePlatform,
    String fullName,
    String headline,
    String title,
    String company,
    String location,
    String discipline,
    Integer experienceYears,
    List<String> skills,
    String email,
    String phone,
    String websiteUrl, // Maps to the detailed text/summary in DB
    String twitterHandle,
    String profilePhotoUrl,
    boolean isOpenToWork,
    Instant collectedAt
) {}

record CandidatePage(List<CandidateView> items, long total, int page, int size) {}
record CandidateCollectionStatus(long totalCandidates, Instant lastCollectedAt,
                                  boolean collecting, String message) {}

record CollectedCandidate(
    String source,
    String profileUrl,
    String handle,
    String fullName,
    String headline,
    String title,
    String company,
    String location,
    String discipline,
    Integer experienceYears,
    List<String> skills,
    String email,
    String phone,
    String detailedHistory, // Mapped to the summary TEXT column
    String twitterHandle,
    String photoUrl,
    boolean openToWork
) {}

// ─── Source Interface ─────────────────────────────────────────────────────────

interface CandidateSourceClient {
    String sourceName();
    List<CollectedCandidate> fetch();
    default Duration refreshInterval() { return Duration.ofHours(24); }
}

// ─── Repository ──────────────────────────────────────────────────────────────

@Repository
class CandidateRepository {

    private final JdbcClient db;
    CandidateRepository(JdbcClient db) { this.db = db; }

    @Transactional
    void upsert(CollectedCandidate p) {
        db.sql("""
            INSERT INTO linkedin_candidates
                (linkedin_url, linkedin_handle, full_name, headline,
                 title, company, location, discipline, experience_years, skills,
                 email, phone, profile_photo_url, is_open_to_work,
                 summary, collected_at, last_verified_at, is_active)
            VALUES
                (:url, :handle, :name, :headline,
                 :title, :company, :location, :discipline, :years, :skills::text[],
                 :email, :phone, :photo, :open,
                 :history, now(), now(), true)
            ON CONFLICT (linkedin_url) DO UPDATE SET
                full_name         = EXCLUDED.full_name,
                headline          = EXCLUDED.headline,
                title             = EXCLUDED.title,
                company           = EXCLUDED.company,
                location          = EXCLUDED.location,
                discipline        = EXCLUDED.discipline,
                experience_years  = EXCLUDED.experience_years,
                skills            = EXCLUDED.skills,
                email             = coalesce(EXCLUDED.email, linkedin_candidates.email),
                phone             = coalesce(EXCLUDED.phone, linkedin_candidates.phone),
                profile_photo_url = coalesce(EXCLUDED.profile_photo_url, linkedin_candidates.profile_photo_url),
                is_open_to_work   = EXCLUDED.is_open_to_work,
                summary           = coalesce(EXCLUDED.summary, linkedin_candidates.summary),
                last_verified_at  = now(),
                is_active         = true
            """)
            .param("url",      p.profileUrl())
            .param("handle",   p.handle())
            .param("name",     p.fullName())
            .param("headline", p.headline())
            .param("title",    p.title())
            .param("company",  p.company())
            .param("location", p.location())
            .param("discipline", p.discipline())
            .param("years",    p.experienceYears())
            .param("skills",   skillsParam(p.skills()))
            .param("email",    p.email())
            .param("phone",    p.phone())
            .param("photo",    p.photoUrl())
            .param("open",     p.openToWork())
            .param("history",  p.detailedHistory())
            .update();
    }

    long count() {
        return db.sql("SELECT COUNT(*) FROM linkedin_candidates WHERE is_active = true")
                 .query(Long.class).single();
    }

    Optional<Instant> lastCollectedAt() {
        return db.sql("SELECT MAX(collected_at) FROM linkedin_candidates")
                 .query(Instant.class).optional();
    }

    List<CandidateView> findAll(int page, int size, String discipline, String search) {
        var sql = new StringBuilder("""
            SELECT id, linkedin_url, linkedin_handle, full_name, headline,
                   title, company, location, discipline, experience_years,
                   skills, email, phone, summary, profile_photo_url,
                   is_open_to_work, collected_at
            FROM linkedin_candidates WHERE is_active = true
            """);
        if (nb(discipline) && !discipline.equals("All Disciplines"))
            sql.append(" AND discipline = :discipline ");
        if (nb(search))
            sql.append("""
                AND (full_name ILIKE :search OR headline ILIKE :search
                  OR title ILIKE :search OR company ILIKE :search
                  OR location ILIKE :search)
                """);
        sql.append(" ORDER BY (email IS NOT NULL) DESC, collected_at DESC LIMIT :size OFFSET :offset");

        var stmt = db.sql(sql.toString()).param("size", size).param("offset", page * size);
        if (nb(discipline) && !discipline.equals("All Disciplines")) stmt = stmt.param("discipline", discipline);
        if (nb(search)) stmt = stmt.param("search", "%" + search + "%");

        return stmt.query((rs, n) -> {
            String url = rs.getString("linkedin_url");
            String platform = url == null ? "Unknown"
                : url.contains("github.com") ? "GitHub"
                : url.contains("linkedin.com") ? "LinkedIn"
                : url.contains("stackoverflow.com") ? "Stack Overflow"
                : url.contains("torre.co") || url.contains("torre.ai") ? "Torre"
                : "Web";
            return new CandidateView(
                (UUID) rs.getObject("id"), url,
                rs.getString("linkedin_handle"), platform,
                rs.getString("full_name"), rs.getString("headline"),
                rs.getString("title"), rs.getString("company"),
                rs.getString("location"), rs.getString("discipline"),
                rs.getObject("experience_years") != null ? rs.getInt("experience_years") : null,
                parseArray(rs.getString("skills")),
                rs.getString("email"), rs.getString("phone"),
                rs.getString("summary"), null,
                rs.getString("profile_photo_url"),
                rs.getBoolean("is_open_to_work"),
                rs.getTimestamp("collected_at") != null
                    ? rs.getTimestamp("collected_at").toInstant() : null);
        }).list();
    }

    long countFiltered(String discipline, String search) {
        var sql = new StringBuilder(
            "SELECT COUNT(*) FROM linkedin_candidates WHERE is_active = true ");
        if (nb(discipline) && !discipline.equals("All Disciplines")) sql.append(" AND discipline = :discipline ");
        if (nb(search)) sql.append(" AND (full_name ILIKE :search OR headline ILIKE :search OR title ILIKE :search OR company ILIKE :search)");
        var stmt = db.sql(sql.toString());
        if (nb(discipline) && !discipline.equals("All Disciplines")) stmt = stmt.param("discipline", discipline);
        if (nb(search)) stmt = stmt.param("search", "%" + search + "%");
        return stmt.query(Long.class).single();
    }

    private static boolean nb(String s) { return s != null && !s.isBlank(); }
    private static String skillsParam(List<String> s) {
        return (s == null || s.isEmpty()) ? "{}" : "{" + String.join(",", s) + "}";
    }
    private static List<String> parseArray(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("{}")) return List.of();
        String inner = raw.replaceAll("^\\{|\\}$", "");
        return inner.isBlank() ? List.of() : List.of(inner.split(","));
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SOURCE 1: Torre.co — Rich Professional History, Resumes & Timeline
// Free public search & Bios API. Best for: ALL disciplines, deep CVs.
// ═══════════════════════════════════════════════════════════════════════════════

@Component
class TorreCandidateSource implements CandidateSourceClient {

    private static final Logger log = LoggerFactory.getLogger(TorreCandidateSource.class);

    private final RestClient searchHttp;
    private final RestClient bioHttp;
    private final ObjectMapper mapper = new ObjectMapper();

    TorreCandidateSource() {
        this.searchHttp = RestClient.builder()
            .baseUrl("https://search.torre.co")
            .defaultHeader("Accept", "application/json")
            .build();
        this.bioHttp = RestClient.builder()
            .baseUrl("https://bio.torre.co/api")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    @Override public String sourceName() { return "TORRE.CO"; }

    @Override
    public List<CollectedCandidate> fetch() {
        List<CollectedCandidate> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Target locations across major Saudi hubs
        for (String city : List.of("Saudi Arabia", "Riyadh", "Jeddah", "Dammam", "Khobar")) {
            try {
                String payload = String.format("{\"and\": [{\"location\": {\"term\": \"%s\"}}]}", city);
                String body = searchHttp.post().uri("/people/_search")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().body(String.class);

                JsonNode root = mapper.readTree(body);
                JsonNode list = root.path("results");
                if (!list.isArray() || list.isEmpty()) continue;

                log.info("[Torre.co] Search '{}' found {} matches", city, list.size());

                for (JsonNode u : list) {
                    String username = u.path("username").asText(null);
                    if (username == null || seen.contains(username)) continue;
                    seen.add(username);

                    try {
                        Thread.sleep(250); // Be respectful
                        Optional<CollectedCandidate> cc = fetchFullBio(username);
                        cc.ifPresent(results::add);
                    } catch (Exception e) {
                        log.warn("[Torre.co] Error parsing bio for user {}: {}", username, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[Torre.co] Search failed for location '{}': {}", city, e.getMessage());
            }
        }
        return results;
    }

    private Optional<CollectedCandidate> fetchFullBio(String username) throws Exception {
        String body = bioHttp.get().uri("/bios/" + username).retrieve().body(String.class);
        JsonNode root = mapper.readTree(body);

        JsonNode person = root.path("person");
        String fullName = person.path("name").asText(null);
        if (fullName == null || fullName.isBlank()) return Optional.empty();

        String headline = person.path("professionalHeadline").asText("Professional");
        String location = person.path("locationName").asText("Saudi Arabia");
        String photo    = person.path("picture").asText(null);

        // Find linked accounts (LinkedIn, GitHub)
        String linkedin = null;
        String github   = null;
        JsonNode links  = person.path("links");
        if (links.isArray()) {
            for (JsonNode l : links) {
                String name = l.path("name").asText("").toLowerCase();
                String addr = l.path("address").asText("");
                if (name.contains("linkedin") || addr.contains("linkedin.com")) linkedin = addr;
                if (name.contains("github") || addr.contains("github.com")) github = addr;
            }
        }

        String profileUrl = linkedin != null ? linkedin : (github != null ? github : "https://torre.ai/" + username);
        String handle     = linkedin != null
            ? linkedin.replaceAll("https?://(www\\.)?linkedin\\.com/in/", "").replaceAll("/$", "")
            : "torre:" + username;

        // Parse skills
        List<String> skills = new ArrayList<>();
        JsonNode strengths  = root.path("strengths");
        if (strengths.isArray()) {
            for (JsonNode s : strengths) {
                String sName = s.path("name").asText(null);
                if (sName != null && !sName.isBlank()) skills.add(sName);
                if (skills.size() >= 10) break;
            }
        }

        // Parse comprehensive Experiences (jobs, education, projects)
        List<String> jobsList = new ArrayList<>();
        List<String> eduList  = new ArrayList<>();
        List<String> projList = new ArrayList<>();
        JsonNode exps = root.path("experiences");
        if (exps.isArray()) {
            for (JsonNode e : exps) {
                String category = e.path("category").asText("");
                String title    = e.path("name").asText("Role");
                String orgName  = "Independent";
                JsonNode orgs   = e.path("organizations");
                if (orgs.isArray() && !orgs.isEmpty()) {
                    orgName = orgs.get(0).path("name").asText("Independent");
                }
                String from = e.path("fromMonth").asText("") + " " + e.path("fromYear").asText("");
                String to   = e.path("toMonth").asText("") + " " + e.path("toYear").asText("");
                if (to.trim().isBlank()) to = "Present";

                String entry = String.format("- **%s** @ %s (%s - %s)", title, orgName, from.trim(), to.trim());

                if (category.equals("jobs")) jobsList.add(entry);
                else if (category.equals("education")) eduList.add(entry);
                else projList.add(entry);
            }
        }

        // Format detailed professional timeline history
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### Bio Summary\n%s\n\n", person.path("summaryOfBio").asText("")));
        if (!jobsList.isEmpty()) {
            sb.append("### Professional Work Experience\n");
            jobsList.forEach(j -> sb.append(j).append("\n"));
            sb.append("\n");
        }
        if (!eduList.isEmpty()) {
            sb.append("### Education Details\n");
            eduList.forEach(ed -> sb.append(ed).append("\n"));
            sb.append("\n");
        }
        if (!projList.isEmpty()) {
            sb.append("### Key Projects & Publications\n");
            projList.forEach(p -> sb.append(p).append("\n"));
        }

        String discipline = GitHubCandidateSource.inferDiscipline(headline, null, skills);
        String title       = GitHubCandidateSource.inferTitle(headline, skills, discipline);
        int    expYears    = jobsList.size() * 2 > 0 ? Math.min(jobsList.size() * 2, 15) : 3;

        log.info("[Torre.co] ✓ Collected rich CV: {} ({}) @ {}", fullName, title, location);

        return Optional.of(new CollectedCandidate(
            "TORRE", profileUrl, handle, fullName, headline, title,
            !jobsList.isEmpty() ? jobsList.get(0).replaceAll("^- \\*\\*.*?\\*\\* @ ", "").replaceAll(" \\(.*\\)$", "") : "Independent",
            location, discipline, expYears, skills, null, null, sb.toString(), null, photo, false));
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SOURCE 2: GitHub — Free. Pulls Saudi developers, repos & languages
// ═══════════════════════════════════════════════════════════════════════════════

@Component
class GitHubCandidateSource implements CandidateSourceClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubCandidateSource.class);

    private static final List<String> QUERIES = List.of(
        "location:\"Saudi Arabia\" type:user",
        "location:Riyadh type:user",
        "location:Jeddah type:user",
        "location:Khobar type:user",
        "location:Dammam type:user",
        "location:\"Saudi Arabia\" language:Java",
        "location:\"Saudi Arabia\" language:Python",
        "location:\"Saudi Arabia\" language:JavaScript",
        "location:\"Saudi Arabia\" language:TypeScript",
        "location:\"Saudi Arabia\" language:Go",
        "location:\"Saudi Arabia\" language:Rust",
        "location:\"Saudi Arabia\" language:Kotlin",
        "location:\"Saudi Arabia\" language:Swift"
    );

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    GitHubCandidateSource(@Value("${app.candidates.github-token:}") String token) {
        var b = RestClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank())
            b.defaultHeader("Authorization", "Bearer " + token);
        this.http = b.build();
    }

    @Override public String sourceName() { return "GITHUB"; }

    @Override
    public List<CollectedCandidate> fetch() {
        List<CollectedCandidate> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String query : QUERIES) {
            for (int page = 1; page <= 2; page++) {
                try {
                    String uri = UriComponentsBuilder.fromPath("/search/users")
                        .queryParam("q", query)
                        .queryParam("per_page", 20)
                        .queryParam("page", page)
                        .build().toUriString();
                    String body = http.get().uri(uri).retrieve().body(String.class);
                    JsonNode root = mapper.readTree(body);
                    JsonNode items = root.path("items");
                    if (!items.isArray() || items.isEmpty()) break;

                    for (JsonNode u : items) {
                        String login = u.path("login").asText(null);
                        if (login == null || seen.contains(login)) continue;
                        seen.add(login);
                        try {
                            Thread.sleep(150);
                            buildProfile(login).ifPresent(results::add);
                        } catch (Exception e) {
                            log.warn("[GitHub] User {} error: {}", login, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[GitHub] Search failed '{}' p{}: {}", query, page, e.getMessage());
                    break;
                }
            }
        }
        return results;
    }

    private Optional<CollectedCandidate> buildProfile(String login) throws Exception {
        String body = http.get().uri("/users/" + login).retrieve().body(String.class);
        JsonNode u = mapper.readTree(body);

        String name = u.path("name").asText(login);
        String githubUrl  = u.path("html_url").asText("");
        String email      = u.path("email").asText(null);
        String bio        = u.path("bio").asText("");
        String company    = cleanAt(u.path("company").asText(null));
        String location   = u.path("location").asText("Saudi Arabia");
        String blog       = u.path("blog").asText(null);
        String twitter    = u.path("twitter_username").asText(null);
        String avatar     = u.path("avatar_url").asText(null);
        int    repos      = u.path("public_repos").asInt(0);

        String linkedin   = extractLinkedIn(bio, blog);
        String profileUrl = linkedin != null ? linkedin : githubUrl;
        String handle     = linkedin != null
            ? linkedin.replaceAll("https?://(www\\.)?linkedin\\.com/in/", "").replaceAll("/$", "")
            : "github:" + login;

        List<String> langs = getLanguages(login, 8);
        String discipline  = inferDiscipline(bio, company, langs);
        String title       = inferTitle(bio, langs, discipline);
        int    expYears    = estimateExp(repos, u.path("created_at").asText(""));
        String[] skills    = buildSkills(langs, bio);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### Bio & Summary\n%s\n\n", bio));
        sb.append("### GitHub Platform Statistics\n");
        sb.append(String.format("- **Public Repositories**: %d\n", repos));
        sb.append(String.format("- **Primary Coding Languages**: %s\n", String.join(", ", langs)));
        if (blog != null && !blog.isBlank()) sb.append(String.format("- **Personal Website**: %s\n", blog));

        log.info("[GitHub] ✓ Collected developer CV: {} ({}) @ {}", name, title, location);

        return Optional.of(new CollectedCandidate(
            "GITHUB", profileUrl, handle, name, bio, title,
            company, location, discipline, expYears, List.of(skills),
            email, null, sb.toString(), twitter, avatar, false));
    }

    private List<String> getLanguages(String login, int max) {
        try {
            String uri = "/users/" + login + "/repos?per_page=" + max + "&sort=pushed&type=owner";
            String body = http.get().uri(uri).retrieve().body(String.class);
            JsonNode repos = mapper.readTree(body);
            Set<String> seen = new LinkedHashSet<>();
            if (repos.isArray()) for (JsonNode r : repos) {
                String lang = r.path("language").asText(null);
                if (lang != null && !lang.isBlank() && !lang.equals("null")) seen.add(lang);
            }
            return new ArrayList<>(seen);
        } catch (Exception e) { return List.of(); }
    }

    private static String cleanAt(String s) {
        return (s == null || s.isBlank()) ? null : s.replaceAll("^@", "").trim();
    }

    static String extractLinkedIn(String bio, String blog) {
        Pattern p = Pattern.compile("(?:https?://)?(?:www\\.)?linkedin\\.com/in/([\\w-]+)");
        for (String src : new String[]{bio, blog}) {
            if (src == null) continue;
            Matcher m = p.matcher(src);
            if (m.find()) return "https://www.linkedin.com/in/" + m.group(1);
        }
        return null;
    }

    static String inferDiscipline(String bio, String company, List<String> langs) {
        String c = ((bio != null ? bio : "") + " " + (company != null ? company : "")
                    + " " + String.join(" ", langs)).toLowerCase();
        if (c.matches(".*(devops|sre|cloud|platform|kubernetes|terraform|infra|docker|aws|azure|gcp).*"))
            return "Cloud & DevOps";
        if (c.matches(".*(secur|cyber|pentest|soc|threat|cryptograph).*"))
            return "Cybersecurity";
        if (c.matches(".*(product manager|ux|ui|designer|design|figma|sketch).*"))
            return "Product & Design";
        if (c.matches(".*(data|ml|ai|machine learning|nlp|llm|deep learning|analytics|tensorflow).*"))
            return "AI & Data";
        if (c.matches(".*(android|kotlin|swift|ios|flutter|mobile).*"))
            return "Mobile Development";
        return "Software Engineering";
    }

    static String inferTitle(String bio, List<String> langs, String discipline) {
        if (bio != null) {
            Matcher m = Pattern.compile(
                "(?i)(senior|lead|principal|staff|junior|mid)?\\s*" +
                "(software|backend|frontend|full.?stack|mobile|data|ml|ai|devops|security)\\s*" +
                "(engineer|developer|scientist|analyst|architect|manager|designer)?")
                .matcher(bio);
            if (m.find() && m.group().trim().length() > 3) return m.group().trim();
        }
        return switch (discipline) {
            case "AI & Data"        -> "Data Engineer";
            case "Cloud & DevOps"   -> "DevOps Engineer";
            case "Cybersecurity"    -> "Security Engineer";
            case "Product & Design" -> "Product Designer";
            case "Mobile Development" -> "Mobile Developer";
            default                 -> langs.isEmpty() ? "Software Engineer" : langs.get(0) + " Engineer";
        };
    }

    static int estimateExp(int repos, String createdAt) {
        int age = 0;
        try {
            if (!createdAt.isBlank()) {
                long years = Duration.between(Instant.parse(createdAt), Instant.now()).toDays() / 365;
                age = (int) Math.min(years, 12);
            }
        } catch (Exception ignored) {}
        return Math.max(1, (Math.min(repos / 10, 5) + age) / 2);
    }

    static String[] buildSkills(List<String> langs, String bio) {
        Set<String> skills = new LinkedHashSet<>(langs);
        if (bio != null) for (String kw : List.of(
            "React","Vue","Angular","Node.js","Django","Spring","Docker","Kubernetes","AWS","GCP","Azure","Git")) {
            if (bio.contains(kw)) skills.add(kw);
        }
        return skills.toArray(new String[0]);
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SOURCE 3: Stack Overflow — Saudi Developers & Reputation
// ═══════════════════════════════════════════════════════════════════════════════

@Component
class StackOverflowCandidateSource implements CandidateSourceClient {

    private static final Logger log = LoggerFactory.getLogger(StackOverflowCandidateSource.class);

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    StackOverflowCandidateSource() {
        this.http = RestClient.builder()
            .baseUrl("https://api.stackexchange.com/2.3")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    @Override public String sourceName() { return "STACKOVERFLOW"; }

    @Override
    public List<CollectedCandidate> fetch() {
        List<CollectedCandidate> results = new ArrayList<>();
        try {
            String uri = UriComponentsBuilder.fromPath("/users")
                .queryParam("site", "stackoverflow")
                .queryParam("location", "Saudi Arabia")
                .queryParam("order", "desc")
                .queryParam("sort", "reputation")
                .queryParam("pagesize", 30)
                .build().toUriString();

            String body = http.get().uri(uri).retrieve().body(String.class);
            JsonNode root = mapper.readTree(body);
            JsonNode items = root.path("items");
            if (!items.isArray()) return results;

            for (JsonNode u : items) {
                String name = u.path("display_name").asText(null);
                if (name == null) continue;

                int rep          = u.path("reputation").asInt(0);
                String soUrl     = u.path("link").asText("");
                String website   = u.path("website_url").asText(null);
                String about     = u.path("about_me").asText("");
                String photo     = u.path("profile_image").asText(null);
                String location  = u.path("location").asText("Saudi Arabia");

                String linkedin  = GitHubCandidateSource.extractLinkedIn(about, website);
                if (linkedin == null && website != null && website.contains("linkedin.com")) linkedin = website;

                String profileUrl = linkedin != null ? linkedin : soUrl;
                String handle     = linkedin != null
                    ? linkedin.replaceAll("https?://(www\\.)?linkedin\\.com/in/", "").replaceAll("/$", "")
                    : "so:" + u.path("user_id").asText();

                String email = extractEmail(about);
                String discipline = GitHubCandidateSource.inferDiscipline(about, null, List.of());
                String title = GitHubCandidateSource.inferTitle(about, List.of(), discipline);
                int expYears = Math.min(rep / 2000 + 1, 15);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("### Professional Bio\n%s\n\n", about.replaceAll("<[^>]+>", " ")));
                sb.append("### Stack Overflow Activity\n");
                sb.append(String.format("- **Total Reputation Points**: %d\n", rep));
                sb.append(String.format("- **Stack Overflow Profile**: %s\n", soUrl));
                if (website != null) sb.append(String.format("- **Portfolio Link**: %s\n", website));

                log.info("[StackOverflow] ✓ Collected profile: {} ({}) @ {}", name, title, location);

                results.add(new CollectedCandidate(
                    "STACKOVERFLOW", profileUrl, handle, name, title, title,
                    null, location, discipline, expYears, List.of(), email, null,
                    sb.toString(), null, photo, false));
            }
        } catch (Exception e) {
            log.warn("[StackOverflow] Collection failed: {}", e.getMessage());
        }
        return results;
    }

    private static String extractEmail(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}").matcher(text);
        return m.find() ? m.group() : null;
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SOURCE 4: HackerNews — Saudi Professional CVs from monthly hiring threads
// ═══════════════════════════════════════════════════════════════════════════════

@Component
class HackerNewsCandidateSource implements CandidateSourceClient {

    private static final Logger log = LoggerFactory.getLogger(HackerNewsCandidateSource.class);

    private static final List<Integer> THREAD_IDS = List.of(42894977, 41428023, 40419856);
    private static final Pattern SAUDI_PATTERN = Pattern.compile("(?i)(saudi|riyadh|jeddah|khobar|dammam|ksa)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern LINKEDIN_PATTERN = Pattern.compile("(?:https?://)?(?:www\\.)?linkedin\\.com/in/([\\w-]+)");

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    HackerNewsCandidateSource() {
        this.http = RestClient.builder().baseUrl("https://hacker-news.firebaseio.com/v0")
            .defaultHeader("Accept", "application/json").build();
    }

    @Override public String sourceName() { return "HACKERNEWS"; }

    @Override
    public List<CollectedCandidate> fetch() {
        List<CollectedCandidate> results = new ArrayList<>();

        for (int threadId : THREAD_IDS) {
            try {
                String body = http.get().uri("/item/" + threadId + ".json").retrieve().body(String.class);
                JsonNode thread = mapper.readTree(body);
                JsonNode kids = thread.path("kids");
                if (!kids.isArray()) continue;

                log.info("[HackerNews] Processing thread {} ({} comments)", threadId, kids.size());

                for (JsonNode kid : kids) {
                    int commentId = kid.asInt(0);
                    if (commentId == 0) continue;
                    try {
                        String cBody = http.get().uri("/item/" + commentId + ".json").retrieve().body(String.class);
                        JsonNode comment = mapper.readTree(cBody);
                        String text = comment.path("text").asText(null);
                        if (text == null || text.isBlank()) continue;

                        if (!SAUDI_PATTERN.matcher(text).find()) continue;

                        String cleanText = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                        String author = comment.path("by").asText("hn_user");

                        Matcher emailM = EMAIL_PATTERN.matcher(cleanText);
                        String email = emailM.find() ? emailM.group() : null;

                        Matcher liM = LINKEDIN_PATTERN.matcher(cleanText);
                        String linkedin = liM.find() ? "https://www.linkedin.com/in/" + liM.group(1) : null;

                        String[] parts = cleanText.split("\\|");
                        String name = parts.length > 0 && parts[0].trim().length() < 60 ? parts[0].trim() : author;
                        String role = parts.length > 1 ? parts[1].trim() : "Professional";
                        String location = "Saudi Arabia";

                        for (String p : parts) {
                            if (SAUDI_PATTERN.matcher(p).find()) { location = p.trim(); break; }
                        }

                        String profileUrl = linkedin != null ? linkedin : "https://news.ycombinator.com/user?id=" + author;
                        String handle     = linkedin != null
                            ? linkedin.replaceAll("https?://(www\\.)?linkedin\\.com/in/", "").replaceAll("/$", "")
                            : "hn:" + author;

                        String discipline = GitHubCandidateSource.inferDiscipline(cleanText, role, List.of());
                        String title = role.length() < 100 ? role : GitHubCandidateSource.inferTitle(cleanText, List.of(), discipline);

                        StringBuilder sb = new StringBuilder();
                        sb.append("### HackerNews Candidate Posting\n");
                        sb.append(cleanText).append("\n\n");
                        sb.append(String.format("- **Author Profile**: https://news.ycombinator.com/user?id=%s\n", author));

                        log.info("[HackerNews] ✓ Collected candidate: {} ({})", name, title);

                        results.add(new CollectedCandidate(
                            "HACKERNEWS", profileUrl, handle, name, title, title,
                            null, location, discipline, 5, List.of(), email, null,
                            sb.toString(), null, null, true));

                    } catch (Exception e) {
                        log.warn("[HackerNews] Comment {} parsing error: {}", commentId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[HackerNews] Thread {} error: {}", threadId, e.getMessage());
            }
        }
        return results;
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Candidate Collector Service — Coordinates and runs all 4 Sources
// ═══════════════════════════════════════════════════════════════════════════════

@Service
class CandidateCollectorService {

    private static final Logger log = LoggerFactory.getLogger(CandidateCollectorService.class);

    private final List<CandidateSourceClient> sources;
    private final CandidateRepository         repo;
    private volatile boolean collecting = false;

    @Value("${app.candidates.collect-on-startup:true}")
    private boolean collectOnStartup;

    CandidateCollectorService(List<CandidateSourceClient> sources, CandidateRepository repo) {
        this.sources = sources;
        this.repo    = repo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (collectOnStartup) {
            log.info("[CandidateCollector] Starting multi-source Saudi CV collection ({} sources)...",
                sources.size());
            new Thread(this::collect, "candidate-collector-startup").start();
        }
    }

    @Scheduled(fixedDelayString  = "${app.candidates.refresh-delay-ms:86400000}",
               initialDelayString = "${app.candidates.refresh-delay-ms:86400000}")
    void scheduledCollect() { collect(); }

    synchronized void collect() {
        if (collecting) { log.info("[CandidateCollector] Collection already running."); return; }
        collecting = true;
        int total = 0;
        try {
            for (CandidateSourceClient source : sources) {
                log.info("[CandidateCollector] → Triggering collection from source: {}", source.sourceName());
                try {
                    List<CollectedCandidate> candidates = source.fetch();
                    for (CollectedCandidate p : candidates) {
                        try { repo.upsert(p); total++; }
                        catch (Exception e) {
                            log.warn("[CandidateCollector] DB insert failed for {}: {}", p.fullName(), e.getMessage());
                        }
                    }
                    log.info("[CandidateCollector] ✓ Source {} completed. Stored {} profiles. Running total: {}",
                        source.sourceName(), candidates.size(), total);
                } catch (Exception e) {
                    log.warn("[CandidateCollector] Source {} crashed: {}", source.sourceName(), e.getMessage());
                }
            }
            log.info("[CandidateCollector] COLLECTION CYCLE COMPLETED. Stored {} candidate profiles.", total);
        } finally {
            collecting = false;
        }
    }

    boolean isCollecting() { return collecting; }
}

// ─── REST Controller ──────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/candidates")
class CandidateController {

    private final CandidateRepository       repo;
    private final CandidateCollectorService collector;

    CandidateController(CandidateRepository repo, CandidateCollectorService collector) {
        this.repo = repo; this.collector = collector;
    }

    @GetMapping
    CandidatePage list(
        @RequestParam(defaultValue = "0")   int    page,
        @RequestParam(defaultValue = "200") int    size,
        @RequestParam(required = false)     String discipline,
        @RequestParam(required = false)     String search
    ) {
        size = Math.min(size, 500);
        return new CandidatePage(
            repo.findAll(page, size, discipline, search),
            repo.countFiltered(discipline, search),
            page, size);
    }

    @GetMapping("/count")  long count() { return repo.count(); }

    @GetMapping("/collection-status")
    CandidateCollectionStatus status() {
        return new CandidateCollectionStatus(
            repo.count(), repo.lastCollectedAt().orElse(null), collector.isCollecting(),
            collector.isCollecting()
                ? "Collecting rich CV history from Torre.co, GitHub, Stack Overflow, and HackerNews..."
                : repo.count() + " CVs collected and mapped from 4 platforms (Torre.co · GitHub · Stack Overflow · HackerNews)");
    }

    @PostMapping("/collect")
    CandidateCollectionStatus triggerCollect(Authentication auth) {
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            return new CandidateCollectionStatus(repo.count(), null, false, "Admin required.");
        new Thread(collector::collect, "candidate-collector-manual").start();
        return new CandidateCollectionStatus(repo.count(), null, true,
            "Manual collection started across all 4 sources.");
    }
}
