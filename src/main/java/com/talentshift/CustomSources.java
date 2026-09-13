package com.talentshift;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
class NaukrigulfSource implements JobSourceClient {
    public String sourceName() { return "NAUKRIGULF"; }
    public Duration refreshInterval() { return Duration.ofHours(24); }
    public void afterSuccessfulCollection(Instant started, List<CollectedJob> accepted) { }

    public List<CollectedJob> fetch() {
        List<CollectedJob> jobs = new ArrayList<>();
        try {
            Document doc = Jsoup.connect("https://www.naukrigulf.com/jobs-in-saudi-arabia")
                    .userAgent("Mozilla/5.0")
                    .timeout(10000).get();
            Elements cards = doc.select(".srp-tuple"); 
            for (Element card : cards) {
                if (jobs.size() >= 150) break; // User requested limit
                
                String title = card.select(".designation-title").text();
                String url = card.select("a.designation-title").attr("abs:href");
                String company = card.select(".info-org").text();
                String location = card.select(".info-loc").text();
                String desc = card.select(".info-desc").text();
                
                if (title.isBlank() || url.isBlank()) continue;
                String id = AuthService.hash(url);
                
                jobs.add(new CollectedJob(sourceName(), id, title, company, location, null, false, null, null, desc, null, url, url, Instant.now(), "SA"));
            }
        } catch (Exception e) {
            // Ignore error for now
        }
        return jobs;
    }
}

@Component
class GulfTalentSource implements JobSourceClient {
    public String sourceName() { return "GULFTALENT"; }
    public Duration refreshInterval() { return Duration.ofHours(24); }
    public void afterSuccessfulCollection(Instant started, List<CollectedJob> accepted) { }

    public List<CollectedJob> fetch() {
        List<CollectedJob> jobs = new ArrayList<>();
        try {
            Document doc = Jsoup.connect("https://www.gulftalent.com/saudi-arabia/jobs")
                    .userAgent("Mozilla/5.0")
                    .timeout(10000).get();
            Elements cards = doc.select("a.job-result-item");
            for (Element card : cards) {
                if (jobs.size() >= 150) break; // User requested limit
                
                String title = card.select(".job-title").text();
                String url = card.attr("abs:href");
                String company = card.select(".company-name").text();
                String location = card.select(".location").text();
                
                if (title.isBlank() || url.isBlank()) continue;
                String id = AuthService.hash(url);
                
                jobs.add(new CollectedJob(sourceName(), id, title, company, location, null, false, null, null, "", null, url, url, Instant.now(), "SA"));
            }
        } catch (Exception e) {
            // Ignore error for now
        }
        return jobs;
    }
}
