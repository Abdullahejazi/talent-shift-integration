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

@Component
class SaudiaSource implements JobSourceClient {
    public String sourceName() { return "SAUDIA_AIRLINES"; }
    public Duration refreshInterval() { return Duration.ofHours(24); }
    public void afterSuccessfulCollection(Instant started, List<CollectedJob> accepted) { }

    public List<CollectedJob> fetch() {
        List<CollectedJob> jobs = new ArrayList<>();
        try {
            Document doc = Jsoup.connect("https://careers.saudia.com/search/?q=&locationsearch=Saudi+Arabia")
                    .userAgent("Mozilla/5.0")
                    .timeout(10000).get();
            Elements rows = doc.select("tr.data-row");
            for (Element row : rows) {
                if (jobs.size() >= 150) break;
                
                String title = row.select(".jobTitle-link").text();
                String url = row.select("a.jobTitle-link").attr("abs:href");
                String location = row.select(".jobLocation").text().replaceAll("\\s+", " ").trim();
                String department = row.select(".jobFacility").text().replaceAll("\\s+", " ").trim();
                String date = row.select(".jobDate").text().trim(); // Might need parsing, but we can default to Instant.now()
                
                if (title.isBlank() || url.isBlank()) continue;
                String id = AuthService.hash(url);
                
                jobs.add(new CollectedJob(sourceName(), id, title, "Saudia Airlines", location, null, false, null, department, "", null, url, url, Instant.now(), "SA"));
            }
        } catch (Exception e) {
            // Ignore error for now
        }
        return jobs;
    }
}
