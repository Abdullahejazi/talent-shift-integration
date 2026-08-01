package com.talentshift.hub.integration.client;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Profile("!real-integrations")
public class MockJobSystemProvider implements JobSystemProvider {
    private final List<SourceRecordDto> sources = List.of(
            new SourceRecordDto("src-1", "Acme Careers", "HTTPS://careers.acme.test/jobs/?utm_source=legacy",
                    "acme.test", "Acme Ltd.", "greenhouse", "acme", 1),
            new SourceRecordDto("src-2", "Acme jobs", "https://careers.acme.test/jobs",
                    "www.acme.test", "ACME LTD", "greenhouse", "acme", 1));
    private final List<JobRecordDto> jobs = List.of(
            new JobRecordDto("job-1", "src-1", "Senior Java Engineer", "Acme Ltd.", "Riyadh",
                    "FULL_TIME", "REQ-100", "https://careers.acme.test/jobs/100?utm_campaign=x", null),
            new JobRecordDto("job-2", "src-2", "Senior Java Engineer", "ACME LTD", "Riyadh",
                    "full time", "REQ-100", "https://careers.acme.test/jobs/100", null));

    @Override public String systemKey() { return "mock-system"; }
    @Override public CursorPage<SourceRecordDto> fetchSources(String cursor, int limit) { return page(sources, cursor, limit); }
    @Override public CursorPage<JobRecordDto> fetchJobs(String cursor, int limit) { return page(jobs, cursor, limit); }

    private static <T> CursorPage<T> page(List<T> all, String cursor, int limit) {
        int start = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        int end = Math.min(start + limit, all.size());
        return new CursorPage<>(all.subList(start, end), end < all.size() ? Integer.toString(end) : null, end < all.size());
    }
}
