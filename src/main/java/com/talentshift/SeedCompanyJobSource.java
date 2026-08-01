package com.talentshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

class SeedCompanyJobSource implements JobSourceClient {
    private static final String USER_AGENT = "TalentShiftJobCollector/1.0 (+public-career-board; contact=site-admin)";
    private static final Pattern SAUDI_LOCATION = Pattern.compile(
            "(?i)Saudi Arabia|Saudi|Riyadh|Jeddah|Makkah|Mecca|Madinah|Medina|Dammam|Khobar|Dhahran|Tabuk|Jubail|Yanbu|Abha|Taif|Qassim|NEOM|KSA|Alkhubar|King Abdullah Economic City");
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final Duration maintenanceInterval;
    private final Duration backfillInterval;
    private final int targetMinimum;
    private final int maximumJobs;

    SeedCompanyJobSource(RestClient restClient, ObjectMapper mapper, JdbcClient jdbc,
            @Value("${app.jobs.seed-refresh-minutes:1440}") long refreshMinutes,
            @Value("${app.jobs.seed-backfill-refresh-minutes:15}") long backfillMinutes,
            @Value("${app.jobs.target-minimum:4500}") int targetMinimum,
            @Value("${app.jobs.seed-maximum-jobs:1000}") int maximumJobs) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.maintenanceInterval = Duration.ofMinutes(Math.max(60, refreshMinutes));
        this.backfillInterval = Duration.ofMinutes(Math.max(15, backfillMinutes));
        this.targetMinimum = Math.max(1, targetMinimum);
        this.maximumJobs = Integer.MAX_VALUE;
    }

    public String sourceName() { return "SEED_COMPANIES"; }
    public Duration refreshInterval() {
        Long active = jdbc.sql("SELECT count(*) FROM jobs WHERE status='ACTIVE' AND saudi_relevant=true")
                .query(Long.class).single();
        return active < targetMinimum ? backfillInterval : maintenanceInterval;
    }

    public List<CollectedJob> fetch() {
        List<CompanySeed> seeds = jdbc.sql("""
                SELECT slug, name, careers_url FROM companies
                WHERE automated=true ORDER BY name
                """).query((rs, rowNum) -> new CompanySeed(rs.getString("slug"), rs.getString("name"),
                        URI.create(rs.getString("careers_url")))).list();
        List<CollectedJob> jobs = new ArrayList<>();
        for (CompanySeed seed : seeds) {
            try {
                String host = seed.careersUrl().getHost().toLowerCase(Locale.ROOT);
                if (host.equals("jobs.lever.co")) collectLever(seed, jobs);
                else if (host.equals("job-boards.greenhouse.io") || host.equals("boards.greenhouse.io")) collectGreenhouse(seed, jobs);
                else collectHtml(seed, jobs);
            } catch (RuntimeException ignored) { }
        }
        return List.copyOf(jobs);
    }

    private void collectLever(CompanySeed seed, List<CollectedJob> jobs) {
        String token = firstPathSegment(seed.careersUrl());
        URI api = URI.create("https://api.lever.co/v0/postings/" + token + "?mode=json");
        if (!loadRobots(api).allowed(api.getPath())) return;
        JsonNode postings = read(get(api));
        for (JsonNode item : postings) {
            String location = text(item.path("categories"), "location");
            String workplace = text(item, "workplaceType");
            boolean remote = "remote".equalsIgnoreCase(workplace) || containsRemote(location);
            if (!isSaudi(location) && !remote) continue;
            String applyUrl = text(item, "applyUrl");
            String hostedUrl = text(item, "hostedUrl");
            if (!http(applyUrl)) applyUrl = hostedUrl;
            if (!http(applyUrl)) continue;
            String description = text(item, "descriptionPlain");
            jobs.add(new CollectedJob(sourceName(), required(item, "id"), required(item, "text"), seed.name(),
                    location, text(item.path("categories"), "commitment"), remote, null,
                    text(item.path("categories"), "team"), description, leverLists(item), applyUrl,
                    http(hostedUrl) ? hostedUrl : applyUrl, epoch(item.path("createdAt").asLong(0)),
                    isSaudi(location) ? "SA" : null));
        }
    }

    private void collectGreenhouse(CompanySeed seed, List<CollectedJob> jobs) {
        String token = firstPathSegment(seed.careersUrl());
        URI api = URI.create("https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs?content=true");
        if (!loadRobots(api).allowed(api.getPath())) return;
        for (JsonNode item : read(get(api)).path("jobs")) {
            String location = text(item.path("location"), "name");
            boolean remote = containsRemote(location);
            if (!isSaudi(location) && !remote) continue;
            String url = text(item, "absolute_url");
            if (!http(url)) continue;
            String description = Jsoup.parse(Objects.toString(text(item, "content"), "")).text();
            jobs.add(new CollectedJob(sourceName(), item.path("id").asText(), required(item, "title"), seed.name(),
                    location, null, remote, null, null, description, null, url, url,
                    instant(text(item, "updated_at")), isSaudi(location) ? "SA" : null));
        }
    }

    private void collectHtml(CompanySeed seed, List<CollectedJob> jobs) {
        RobotsRules robots = loadRobots(seed.careersUrl());
        if (!robots.allowed(seed.careersUrl().getPath())) return;
        Document listing = Jsoup.parse(get(seed.careersUrl()), seed.careersUrl().toString());
        Set<URI> links = new LinkedHashSet<>();
        for (Element anchor : listing.select("a[href]")) {
            try {
                URI candidate = seed.careersUrl().resolve(anchor.attr("href"));
                String path = candidate.getPath().toLowerCase(Locale.ROOT);
                if (sameHost(seed.careersUrl(), candidate) && robots.allowed(candidate.getPath())
                        && (path.contains("/job/") || path.contains("/jobs/"))) links.add(candidate);
            } catch (IllegalArgumentException ignored) { }
        }
        for (URI link : links) {
            Document page = Jsoup.parse(get(link), link.toString());
            Element heading = page.selectFirst("h1");
            String title = heading == null ? page.title() : heading.text();
            String body = page.body() == null ? "" : page.body().text();
            Matcher location = SAUDI_LOCATION.matcher(body);
            if (!title.isBlank() && location.find()) jobs.add(new CollectedJob(sourceName(), AuthService.hash(link.toString()),
                    title, seed.name(), location.group(), null, false, null, null, body, null,
                    link.toString(), link.toString(), null, "SA"));
        }
    }

    private String get(URI uri) { return restClient.get().uri(uri).header("User-Agent", USER_AGENT).retrieve().body(String.class); }
    private JsonNode read(String body) {
        try { return mapper.readTree(Objects.requireNonNull(body)); }
        catch (Exception exception) { throw new IllegalStateException("Invalid public job-board response", exception); }
    }
    private RobotsRules loadRobots(URI site) {
        try {
            URI robots = URI.create(site.getScheme() + "://" + site.getHost() + "/robots.txt");
            return RobotsRules.parse(get(robots));
        } catch (RuntimeException exception) { return new RobotsRules(List.of()); }
    }
    private static String firstPathSegment(URI uri) {
        String[] pieces = uri.getPath().split("/");
        for (String piece : pieces) if (!piece.isBlank()) return piece;
        throw new IllegalArgumentException("Missing public board token");
    }
    private static String leverLists(JsonNode item) {
        List<String> values = new ArrayList<>();
        for (JsonNode list : item.path("lists")) {
            String value = Jsoup.parse(Objects.toString(text(list, "content"), "")).text();
            if (!value.isBlank()) values.add(value);
        }
        return values.isEmpty() ? null : String.join("\n", values);
    }
    private static boolean isSaudi(String value) { return value != null && SAUDI_LOCATION.matcher(value).find(); }
    private static boolean containsRemote(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).matches(".*(remote|worldwide|anywhere|global|emea|mena).*" );
    }
    private static boolean http(String value) {
        if (value == null) return false;
        try { String scheme = URI.create(value).getScheme(); return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme); }
        catch (RuntimeException exception) { return false; }
    }
    private static boolean sameHost(URI first, URI second) {
        return "https".equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost());
    }
    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }
    private static Instant epoch(long millis) { return millis <= 0 ? null : Instant.ofEpochMilli(millis); }
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) {
            try { return Instant.parse(value); } catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }

    private record CompanySeed(String slug, String name, URI careersUrl) {}
    private record RobotsRule(String path, boolean allow) {}
    private record RobotsRules(List<RobotsRule> rules) {
        static RobotsRules parse(String content) {
            List<RobotsRule> rules = new ArrayList<>();
            boolean applies = false;
            for (String rawLine : content.split("\\R")) {
                String line = rawLine.split("#", 2)[0].trim();
                int separator = line.indexOf(':');
                if (separator < 0) continue;
                String field = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                if ("user-agent".equals(field)) applies = "*".equals(value) || USER_AGENT.toLowerCase(Locale.ROOT).startsWith(value.toLowerCase(Locale.ROOT));
                else if (applies && ("allow".equals(field) || "disallow".equals(field)) && !value.isBlank())
                    rules.add(new RobotsRule(value, "allow".equals(field)));
            }
            return new RobotsRules(List.copyOf(rules));
        }
        boolean allowed(String path) {
            return rules.stream().filter(rule -> path.startsWith(rule.path()))
                    .max(Comparator.comparingInt(rule -> rule.path().length()))
                    .map(RobotsRule::allow).orElse(true);
        }
    }
}
