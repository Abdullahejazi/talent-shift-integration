package com.talentshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkedIn public job collector.
 *
 * Uses LinkedIn's public guest jobs endpoint to retrieve job cards for a given
 * company slug/keyword.  Results are filtered to Saudi Arabia, Riyadh, Jeddah,
 * Remote, or Anywhere locations before being handed to the ingestion pipeline.
 *
 * The class is picked up automatically by {@link RegistryJobSource} because it
 * implements {@link RegisteredSourceCollector} and is annotated with {@code @Component}.
 */
@Component
public class LinkedInCollector extends JsonSourceCollector implements RegisteredSourceCollector {

    private static final int PAGE_SIZE = 25;
    private static final String[] VALID_LOCATIONS = {
            "saudi arabia", "riyadh", "jeddah", "remote", "anywhere"
    };

    // Matches a LinkedIn job card <li> block that contains a data-entity-urn attribute.
    // Group 1 = job posting URL path, Group 2 = inner card HTML.
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<a[^>]+href=\"(/jobs/view/[^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern H3_PATTERN  = Pattern.compile("<h3[^>]*>([^<]+)</h3>",  Pattern.CASE_INSENSITIVE);
    private static final Pattern H4_PATTERN  = Pattern.compile("<h4[^>]*>([^<]+)</h4>",  Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "<span[^>]*class=\"[^\"]*job-search-card__location[^\"]*\"[^>]*>([^<]+)</span>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PATTERN = Pattern.compile("<time[^>]+datetime=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    public LinkedInCollector(SafeSourceHttpClient http, ObjectMapper mapper) {
        super(http, mapper);
    }

    // ── RegisteredSourceCollector ─────────────────────────────────────────────

    @Override
    public boolean supports(RegisteredJobSource source) {
        return "LINKEDIN".equals(source.sourceType());
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public SourceCollectionResult collect(RegisteredJobSource source, SyncCursor cursor) {
        return collect(source, cursor, 1000);
    }

    SourceCollectionResult collect(RegisteredJobSource source, SyncCursor cursor, int maximumJobs) {
        String keyword = source.boardIdentifier();
        List<CollectedJob> out = new ArrayList<>();
        int offset = 0;
        SourceHttpResponse lastResponse = null;

        while (true) {
            URI uri = URI.create(buildUrl(keyword, offset));
            SourceHttpResponse response = http.get(uri, cursor);
            lastResponse = response;

            if (response.status() == 304) {
                break;
            }

            List<CollectedJob> page = parseLinkedInHtml(response.body(), source);
            if (page.isEmpty()) {
                break;  // no more pages
            }
            int remaining = Math.max(0, maximumJobs - out.size());
            out.addAll(page.subList(0, Math.min(remaining, page.size())));
            offset += PAGE_SIZE;

            // LinkedIn guest endpoint typically caps at a few hundred results.
            if (out.size() >= maximumJobs || offset >= 1000) {
                break;
            }
        }

        // If we never got a successful response, return empty.
        if (lastResponse == null) {
            return SourceCollectionResult.empty(0, false);
        }
        return result(out, lastResponse, true);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildUrl(String keyword, int offset) {
        return "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
                + "?keywords=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&location=Saudi%20Arabia"
                + "&f_TPR=r86400"   // last 24 hours
                + "&start=" + offset;
    }

    private List<CollectedJob> parseLinkedInHtml(String html, RegisteredJobSource source) {
        List<CollectedJob> jobs = new ArrayList<>();
        Document document = Jsoup.parse(html, "https://www.linkedin.com");
        for (Element card : document.select("li:has(a[href*=/jobs/view/]), div.base-card:has(a[href*=/jobs/view/])")) {
            Element link = card.selectFirst("a[href*=/jobs/view/]");
            if (link == null) continue;
            String jobUrl = link.absUrl("href").replaceAll("[?&]trk=[^&]+", "");
            String jobPath = URI.create(jobUrl).getPath();
            Element titleElement = card.selectFirst("h3");
            Element companyElement = card.selectFirst("h4");
            Element locationElement = card.selectFirst(".job-search-card__location");
            Element timeElement = card.selectFirst("time[datetime]");
            String title = titleElement == null ? "" : titleElement.text().trim();
            String company = companyElement == null ? "" : companyElement.text().trim();
            String location = locationElement == null ? "" : locationElement.text().trim().toLowerCase();
            String postedAt = timeElement == null ? "" : timeElement.attr("datetime");

            if (title == null || title.isBlank()) {
                continue;
            }
            if (!passesLocationFilter(location)) {
                continue;
            }

            jobs.add(new CollectedJob(
                    sourceName(source),
                    external(source, jobPath),   // use the URL path as the external id
                    title,
                    company == null || company.isBlank() ? source.companyName() : company,
                    location,
                    null,                        // employment type — not available in the fragment
                    false,
                    null,
                    null,
                    null,
                    null,
                    jobUrl,
                    jobUrl,
                    instant(postedAt),
                    "SA"
            ));
        }
        return jobs;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private boolean passesLocationFilter(String loc) {
        for (String valid : VALID_LOCATIONS) {
            if (loc.contains(valid)) {
                return true;
            }
        }
        return false;
    }
}
