package com.talentshift;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSearchSeedDiscoverer
 *
 * A scheduled Spring component that automatically discovers new job-source seeds.
 *
 * <h3>Priority strategy</h3>
 * <ol>
 *   <li><b>Phase 1 – LinkedIn focus:</b> While fewer than
 *       {@code app.jobs.linkedin-job-target} (default 300) jobs exist in the DB
 *       with {@code source_type = 'LINKEDIN'}, only LinkedIn search queries are run.
 *       This aggressively seeds the pipeline with LinkedIn company pages.</li>
 *   <li><b>Phase 2 – Broad discovery:</b> Once the LinkedIn target is met the
 *       discoverer switches to the full set of DuckDuckGo queries covering
 *       Greenhouse, Lever, Ashby, Workable, Breezy, Recruitee, Workday,
 *       SmartRecruiters, and LinkedIn.</li>
 * </ol>
 *
 * Runs 60 s after startup and then every 4 hours.
 */
// Replaced by AutoDiscoveryEngine — kept here for reference only.
// @Component   ← intentionally disabled
@Deprecated
public class WebSearchSeedDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(WebSearchSeedDiscoverer.class);

    // ── ATS URL patterns ──────────────────────────────────────────────────────
    /** Each entry: [urlPattern-regex, sourceType, slugCaptureGroup (1-based)] */
    private static final List<AtsPattern> ATS_PATTERNS = List.of(
        new AtsPattern("boards(?:-api)?\\.greenhouse\\.io/(?:v1/boards/)?([A-Za-z0-9_-]+)",   "GREENHOUSE"),
        new AtsPattern("jobs\\.lever\\.co/([A-Za-z0-9_-]+)",                                   "LEVER"),
        new AtsPattern("jobs\\.ashbyhq\\.com/([A-Za-z0-9_-]+)",                               "ASHBY"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.workable\\.com",                                    "WORKABLE"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.breezy\\.hr",                                      "BREEZY"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.recruitee\\.com",                                  "RECRUITEE"),
        new AtsPattern("([A-Za-z0-9_-]+)\\.myworkdayjobs\\.com",                              "WORKDAY"),
        new AtsPattern("app\\.smartrecruiters\\.com/(?:jobs/)?([A-Za-z0-9_-]+)",              "SMARTRECRUITERS"),
        new AtsPattern("linkedin\\.com/company/([A-Za-z0-9_-]+)/jobs",                        "LINKEDIN")
    );

    // ── LinkedIn-only queries (Phase 1) ───────────────────────────────────────
    /** Runs exclusively until {@code LINKEDIN_JOB_TARGET} jobs are collected. */
    private static final List<String> LINKEDIN_QUERIES = List.of(
        // Direct site searches
        "site:linkedin.com/company Saudi Arabia jobs",
        "site:linkedin.com/company Riyadh jobs",
        "site:linkedin.com/company Jeddah jobs",
        "site:linkedin.com/company NEOM jobs",
        "site:linkedin.com/company Saudi Arabia engineer",
        "site:linkedin.com/company Saudi Arabia manager",
        "site:linkedin.com/company Saudi Arabia finance",
        "site:linkedin.com/company Saudi Arabia operations",
        "site:linkedin.com/company Saudi Arabia technology",
        "site:linkedin.com/company Saudi Arabia healthcare",
        "site:linkedin.com/company Saudi Arabia construction",
        "site:linkedin.com/company Saudi Arabia logistics",
        "site:linkedin.com/company Saudi Arabia banking",
        "site:linkedin.com/company Saudi Arabia consulting",
        "site:linkedin.com/company Saudi Arabia sales",
        "site:linkedin.com/company Saudi Arabia marketing",
        "site:linkedin.com/company Saudi Arabia human resources",
        "site:linkedin.com/company Saudi Arabia data analyst",
        "site:linkedin.com/company Saudi Arabia software",
        "site:linkedin.com/company Saudi Arabia accountant",
        // Named companies on LinkedIn
        "linkedin.com/company saudi-aramco jobs",
        "linkedin.com/company sabic jobs",
        "linkedin.com/company stc jobs",
        "linkedin.com/company neom jobs",
        "linkedin.com/company almarai jobs",
        "linkedin.com/company acwa-power jobs",
        "linkedin.com/company pif jobs",
        "linkedin.com/company samba-financial-group jobs",
        "linkedin.com/company al-rajhi-bank jobs",
        "linkedin.com/company mobily jobs",
        "linkedin.com/company maa-aden jobs",
        "linkedin.com/company nhc jobs",
        "linkedin.com/company thiqah jobs",
        "linkedin.com/company lucid-motors jobs",
        "linkedin.com/company mckinsey jobs saudi",
        "linkedin.com/company deloitte jobs saudi",
        "linkedin.com/company pwc jobs saudi",
        "linkedin.com/company kpmg jobs saudi",
        "linkedin.com/company ey jobs saudi",
        "linkedin.com/company bcg jobs saudi",
        "linkedin.com/company google jobs riyadh",
        "linkedin.com/company microsoft jobs riyadh",
        "linkedin.com/company amazon jobs saudi",
        "linkedin.com/company oracle jobs saudi",
        "linkedin.com/company sap jobs saudi",
        "linkedin.com/company siemens jobs saudi",
        "linkedin.com/company ibm jobs saudi",
        "linkedin.com/company cisco jobs saudi",
        "linkedin.com/company hp jobs saudi",
        // Arabic queries
        "وظائف السعودية linkedin.com/company",
        "وظائف الرياض site:linkedin.com/company",
        "وظائف جدة site:linkedin.com/company",
        "شركات السعودية وظائف linkedin"
    );

    // ── Broad DuckDuckGo queries (Phase 2) ────────────────────────────────────
    /** Runs once LinkedIn target is met — covers all ATS providers. */
    private static final List<String> BROAD_QUERIES = List.of(
        // Greenhouse
        "site:boards.greenhouse.io Saudi Arabia jobs",
        "site:boards.greenhouse.io Riyadh jobs",
        "site:boards.greenhouse.io Jeddah jobs",
        "site:boards.greenhouse.io NEOM jobs",
        "site:boards.greenhouse.io Saudi Arabia engineer",
        "site:boards.greenhouse.io Saudi Arabia manager",
        "site:boards.greenhouse.io Saudi Arabia finance",
        "site:boards.greenhouse.io Saudi Arabia operations",
        // Lever
        "site:jobs.lever.co Saudi Arabia jobs",
        "site:jobs.lever.co Riyadh",
        "site:jobs.lever.co Jeddah",
        "site:jobs.lever.co Saudi engineer",
        "site:jobs.lever.co Saudi manager",
        // Ashby
        "site:jobs.ashbyhq.com Saudi Arabia",
        "site:jobs.ashbyhq.com Riyadh OR Jeddah",
        // Workable
        "site:jobs.workable.com Saudi Arabia",
        "site:workable.com Saudi Arabia jobs",
        // Breezy
        "site:breezy.hr Saudi Arabia jobs",
        "site:breezy.hr Riyadh OR Jeddah",
        // Recruitee
        "site:recruitee.com Saudi Arabia jobs",
        "site:recruitee.com Riyadh",
        // Workday
        "site:myworkdayjobs.com Saudi Arabia",
        "site:myworkdayjobs.com Riyadh OR Jeddah",
        // SmartRecruiters
        "site:jobs.smartrecruiters.com Saudi Arabia",
        "site:app.smartrecruiters.com Saudi Arabia",
        // LinkedIn (keep running in Phase 2 too)
        "site:linkedin.com/company jobs Saudi Arabia",
        "site:linkedin.com/company Riyadh jobs",
        // General career pages in KSA
        "careers Saudi Arabia site:greenhouse.io OR site:lever.co OR site:ashbyhq.com",
        "وظائف السعودية site:greenhouse.io",
        "وظائف الرياض site:lever.co",
        "NEOM jobs careers apply greenhouse lever ashby",
        "Saudi Vision 2030 jobs careers apply",
        "Aramco careers site:greenhouse.io OR site:lever.co",
        "SABIC careers site:greenhouse.io OR site:lever.co",
        "STC careers jobs Riyadh greenhouse lever",
        "Almarai careers jobs apply",
        "Mobily careers jobs Riyadh",
        "Ma'aden careers jobs Saudi",
        "Al-Rajhi Bank jobs careers",
        "NHC National Housing Company jobs Saudi",
        "ACWA Power careers jobs Saudi",
        "PIF Saudi Arabia jobs careers apply",
        "McKinsey Riyadh jobs careers",
        "Deloitte Saudi Arabia careers jobs",
        "EY Ernst Young Riyadh jobs",
        "KPMG Saudi Arabia jobs",
        "PwC Saudi Arabia careers",
        "BCG Boston Consulting Group Saudi jobs",
        "Google Saudi Arabia jobs careers",
        "Microsoft Riyadh jobs careers",
        "Amazon Saudi Arabia careers jobs",
        "Oracle Saudi Arabia jobs",
        "SAP Saudi Arabia careers",
        "Siemens Saudi Arabia jobs",
        "IBM Saudi Arabia careers",
        "Cisco Saudi Arabia jobs",
        "HP Saudi Arabia careers"
    );

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final String   DDG_URL       = "https://html.duckduckgo.com/html/?q=";
    private static final Duration REQUEST_DELAY = Duration.ofSeconds(3);

    private final SafeSourceHttpClient http;
    private final JdbcClient           jdbc;
    private final boolean              enabled;
    private final int                  linkedInJobTarget;

    public WebSearchSeedDiscoverer(SafeSourceHttpClient http, JdbcClient jdbc,
            @Value("${app.jobs.legacy-web-seed-discovery-enabled:false}") boolean enabled,
            @Value("${app.jobs.linkedin-job-target:300}") int linkedInJobTarget) {
        this.http             = http;
        this.jdbc             = jdbc;
        this.enabled          = enabled;
        this.linkedInJobTarget = linkedInJobTarget;
    }

    // ── Scheduled entry-point ─────────────────────────────────────────────────

    /** Runs 60 s after startup and then every 4 hours. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 4 * 60 * 60 * 1000)
    public void discover() {
        if (!enabled) {
            log.info("[WebSearchSeedDiscoverer] Disabled via config — skipping.");
            return;
        }

        int linkedInJobs = countLinkedInJobs();
        boolean linkedInPhase = linkedInJobs < linkedInJobTarget;

        List<String> queries = linkedInPhase ? LINKEDIN_QUERIES : BROAD_QUERIES;

        if (linkedInPhase) {
            log.info("[WebSearchSeedDiscoverer] ▶ PHASE 1 – LinkedIn focus. "
                    + "Current LinkedIn jobs: {}/{}. Running {} LinkedIn queries.",
                    linkedInJobs, linkedInJobTarget, queries.size());
        } else {
            log.info("[WebSearchSeedDiscoverer] ▶ PHASE 2 – Broad discovery. "
                    + "LinkedIn target met ({} jobs). Running {} broad queries.",
                    linkedInJobs, queries.size());
        }

        AtomicInteger newSeeds = new AtomicInteger(0);
        AtomicInteger skipped  = new AtomicInteger(0);
        Set<String>   seenUrls = new HashSet<>();

        for (String query : queries) {
            // Re-check LinkedIn count after every query — switch to Phase 2 as soon
            // as the target is reached so we don't waste the rest of the round.
            if (linkedInPhase && countLinkedInJobs() >= linkedInJobTarget) {
                log.info("[WebSearchSeedDiscoverer] LinkedIn target reached mid-round — "
                        + "switching to broad discovery for remaining queries.");
                linkedInPhase = false;
                queries = BROAD_QUERIES;   // reassign; the for-loop will restart over new list
                continue;
            }

            try {
                List<String> urls = searchDuckDuckGo(query);
                for (String url : urls) {
                    if (!seenUrls.add(url.toLowerCase(Locale.ROOT))) continue;
                    AtsMatch match = detectAts(url);
                    if (match == null) continue;
                    // In LinkedIn phase, only accept LinkedIn seeds
                    if (linkedInPhase && !"LINKEDIN".equals(match.sourceType())) continue;
                    String  careersUrl = normaliseUrl(url, match);
                    boolean inserted   = upsertSeed(careersUrl, match);
                    if (inserted) {
                        newSeeds.incrementAndGet();
                        log.info("[WebSearchSeedDiscoverer] ✅ New {} seed: slug={}",
                                match.sourceType(), match.slug());
                    } else {
                        skipped.incrementAndGet();
                    }
                }
                Thread.sleep(REQUEST_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[WebSearchSeedDiscoverer] Error on query '{}': {}", query, e.getMessage());
            }
        }

        int finalCount = countLinkedInJobs();
        log.info("[WebSearchSeedDiscoverer] Round complete — {} new seeds, {} skipped. "
                + "LinkedIn jobs in DB: {}.", newSeeds.get(), skipped.get(), finalCount);
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    /** Returns total number of jobs collected from LINKEDIN sources. */
    private int countLinkedInJobs() {
        try {
            return jdbc.sql("""
                    SELECT COUNT(*) FROM jobs j
                    JOIN job_sources s ON j.source_id = s.id
                    WHERE s.source_type = 'LINKEDIN'
                    """)
                    .query(Integer.class).single();
        } catch (Exception e) {
            log.warn("[WebSearchSeedDiscoverer] Could not count LinkedIn jobs: {}", e.getMessage());
            return 0;
        }
    }

    // ── DuckDuckGo scraper ────────────────────────────────────────────────────

    private List<String> searchDuckDuckGo(String query) {
        String url = DDG_URL + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        try {
            SourceHttpResponse resp = http.get(URI.create(url), null);
            if (resp.status() != 200 || resp.body().isBlank()) return found;
            Document doc = Jsoup.parse(resp.body());
            for (Element a : doc.select("a.result__url, a[href*='greenhouse'], a[href*='lever.co'], " +
                    "a[href*='ashby'], a[href*='workable'], a[href*='breezy'], " +
                    "a[href*='recruitee'], a[href*='workdayjobs'], a[href*='smartrecruiters'], " +
                    "a[href*='linkedin.com/company']")) {
                String href = a.absUrl("href");
                if (href.isBlank()) href = a.attr("href");
                if (!href.isBlank()) found.add(href);
            }
            // Also search raw text for ATS URLs
            Matcher m = Pattern.compile("https?://[^\\s\"'<>]+(?:greenhouse\\.io|lever\\.co|ashbyhq\\.com|" +
                    "workable\\.com|breezy\\.hr|recruitee\\.com|myworkdayjobs\\.com|" +
                    "smartrecruiters\\.com|linkedin\\.com/company)[^\\s\"'<>]*").matcher(resp.body());
            while (m.find()) found.add(m.group());
        } catch (Exception e) {
            log.trace("[WebSearchSeedDiscoverer] DDG fetch failed for '{}': {}", query, e.getMessage());
        }
        return found;
    }

    // ── ATS detection ─────────────────────────────────────────────────────────

    private AtsMatch detectAts(String url) {
        for (AtsPattern p : ATS_PATTERNS) {
            Matcher m = p.pattern().matcher(url);
            if (m.find()) {
                String slug = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (slug.length() < 2 || slug.length() > 100) continue;
                // Skip known garbage slugs
                if (slug.equals("jobs") || slug.equals("company") || slug.equals("careers")) continue;
                return new AtsMatch(p.sourceType(), slug);
            }
        }
        return null;
    }

    // ── Canonical URL builder ─────────────────────────────────────────────────

    private String normaliseUrl(String raw, AtsMatch match) {
        return switch (match.sourceType()) {
            case "GREENHOUSE"      -> "https://job-boards.greenhouse.io/" + match.slug();
            case "LEVER"           -> "https://jobs.lever.co/" + match.slug();
            case "ASHBY"           -> "https://jobs.ashbyhq.com/" + match.slug();
            case "WORKABLE"        -> "https://" + match.slug() + ".workable.com/jobs";
            case "BREEZY"          -> "https://" + match.slug() + ".breezy.hr";
            case "RECRUITEE"       -> "https://" + match.slug() + ".recruitee.com";
            case "WORKDAY"         -> "https://" + match.slug() + ".myworkdayjobs.com/jobs";
            case "SMARTRECRUITERS" -> "https://jobs.smartrecruiters.com/" + match.slug();
            case "LINKEDIN"        -> "https://www.linkedin.com/company/" + match.slug() + "/jobs";
            default                -> raw;
        };
    }

    // ── Database upsert ───────────────────────────────────────────────────────

    /**
     * Inserts a company + job source row if neither the careers URL nor a company with
     * the same slug already exists.
     * @return true if a new row was inserted
     */
    private boolean upsertSeed(String careersUrl, AtsMatch match) {
        // Check if source already registered
        int existing = jdbc.sql("SELECT COUNT(*) FROM job_sources WHERE lower(careers_url) = lower(:u)")
                .param("u", careersUrl).query(Integer.class).single();
        if (existing > 0) return false;

        String slug        = match.slug();
        String companyName = toCompanyName(slug);
        String websiteUrl  = "https://www." + slug + ".com";

        // Upsert company (ignore if slug already exists)
        jdbc.sql("""
                INSERT INTO companies (slug, name, website_url, careers_url, source_type, automated)
                VALUES (:slug, :name, :website, :careers, 'PUBLIC_ATS_API', true)
                ON CONFLICT DO NOTHING
                """)
                .param("slug",    slug)
                .param("name",    companyName)
                .param("website", websiteUrl)
                .param("careers", careersUrl)
                .update();

        // Get company id (may have just been inserted or pre-existed)
        Optional<UUID> companyId = jdbc.sql("SELECT id FROM companies WHERE slug = :slug")
                .param("slug", slug).query(UUID.class).optional();

        // Insert job source
        jdbc.sql("""
                INSERT INTO job_sources
                    (company_id, company_name, careers_url, source_type, ats_provider,
                     board_identifier, country, permission_status, refresh_interval_minutes)
                VALUES
                    (:cid, :cname, :url, :type, :provider, :board, 'SA', 'PUBLIC_ALLOWED', 60)
                ON CONFLICT DO NOTHING
                """)
                .param("cid",      companyId.orElse(null))
                .param("cname",    companyName)
                .param("url",      careersUrl)
                .param("type",     match.sourceType())
                .param("provider", match.sourceType())
                .param("board",    slug)
                .update();

        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Converts a slug like "saudi-aramco" → "Saudi Aramco" */
    private static String toCompanyName(String slug) {
        String[] parts = slug.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class AtsPattern {
        private final String  sourceType;
        private final Pattern compiled;

        AtsPattern(String rawPattern, String sourceType) {
            this.sourceType = sourceType;
            this.compiled   = Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE);
        }

        String  sourceType() { return sourceType; }
        Pattern pattern()    { return compiled; }
    }

    private record AtsMatch(String sourceType, String slug) {}
}
