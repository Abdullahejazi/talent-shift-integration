package com.talentshift.hub.integration.client;

public interface JobSystemProvider {
    String systemKey();
    CursorPage<SourceRecordDto> fetchSources(String cursor, int limit);
    CursorPage<JobRecordDto> fetchJobs(String cursor, int limit);
}
