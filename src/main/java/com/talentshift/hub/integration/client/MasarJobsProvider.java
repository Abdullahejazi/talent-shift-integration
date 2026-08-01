package com.talentshift.hub.integration.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.talentshift.hub.integration.config.HubProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class MasarJobsProvider implements JobSystemProvider {

    private final HubProperties hubProperties;
    private final RestTemplate restTemplate;

    public MasarJobsProvider(HubProperties hubProperties, RestTemplateBuilder builder) {
        this.hubProperties = hubProperties;
        this.restTemplate = builder.build();
    }

    @Override
    public String systemKey() {
        return "masarjobs";
    }

    @Override
    public CursorPage<SourceRecordDto> fetchSources(String cursor, int limit) {
        HubProperties.RemoteSystem config = hubProperties.systems().get("masarjobs");
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalStateException("MasarJobs configuration missing");
        }

        int page = (cursor == null || cursor.isBlank()) ? 0 : Integer.parseInt(cursor);
        
        String url = UriComponentsBuilder.fromHttpUrl(config.baseUrl())
                .path("/api/admin/job-sources")
                .queryParam("page", page)
                .queryParam("size", limit)
                .queryParam("sort", "createdAt,desc")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        if (config.authToken() != null && !config.authToken().isBlank()) {
            headers.setBearerAuth(config.authToken());
        }

        MasarPage body = null;
        try {
            ResponseEntity<MasarPage> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MasarPage.class);
            body = response.getBody();
        } catch (Exception e) {
            System.err.println("Failed to fetch sources from masarjobs: " + e.getMessage());
            return new CursorPage<>(List.of(), null, false);
        }

        if (body == null || body.content() == null) {
            return new CursorPage<>(List.of(), null, false);
        }

        List<SourceRecordDto> sources = body.content().stream().map(item -> new SourceRecordDto(
                item.sourceName() != null ? item.sourceName() : (item.id() != null ? String.valueOf(item.id()) : null),
                item.sourceName() != null ? item.sourceName() : "Unknown Source",
                item.sourceUrl(),
                item.officialOrganizationDomain(),
                item.organizationName(),
                item.collectorType(),
                null,
                determineSourceType(item)
        )).collect(Collectors.toList());

        boolean hasMore = false;
        String nextCursor = null;
        if (body.totalPages() != null) {
            int currentPage = (body.pageable() != null) ? body.pageable().pageNumber() : page;
            int totalPages = body.totalPages();
            hasMore = currentPage + 1 < totalPages;
            if (hasMore) {
                nextCursor = String.valueOf(currentPage + 1);
            }
        } else if (body.pageable() != null) {
            int currentPage = body.pageable().pageNumber();
            int totalPages = body.pageable().totalPages();
            hasMore = currentPage + 1 < totalPages;
            if (hasMore) {
                nextCursor = String.valueOf(currentPage + 1);
            }
        }

        return new CursorPage<>(sources, nextCursor, hasMore);
    }

    @Override
    public CursorPage<JobRecordDto> fetchJobs(String cursor, int limit) {
        HubProperties.RemoteSystem config = hubProperties.systems().get("masarjobs");
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalStateException("MasarJobs configuration missing");
        }

        int page = (cursor == null || cursor.isBlank()) ? 0 : Integer.parseInt(cursor);
        
        String url = UriComponentsBuilder.fromHttpUrl(config.baseUrl())
                .path("/api/jobs")
                .queryParam("page", page)
                .queryParam("size", limit)
                .queryParam("sort", "publishedAt,desc")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        // Public endpoint according to requirements, but we can pass token anyway if needed

        ResponseEntity<MasarPage> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MasarPage.class);
        MasarPage body = response.getBody();
        if (body == null || body.content() == null) {
            return new CursorPage<>(List.of(), null, false);
        }

        List<JobRecordDto> jobs = body.content().stream().map(item -> new JobRecordDto(
                item.id() != null ? String.valueOf(item.id()) : null,
                item.sourceName() != null ? item.sourceName() : (item.id() != null ? String.valueOf(item.id()) : null),
                item.title() != null ? item.title() : (item.organizationName() != null ? "Job at " + item.organizationName() : "Unknown Job"),
                item.organizationName(),
                "Unknown",
                item.opportunityTypes(),
                null,
                item.sourceUrl(),
                item.sourceUrl()
        )).collect(Collectors.toList());

        boolean hasMore = false;
        String nextCursor = null;
        if (body.totalPages() != null) {
            int currentPage = (body.pageable() != null) ? body.pageable().pageNumber() : page;
            int totalPages = body.totalPages();
            hasMore = currentPage + 1 < totalPages;
            if (hasMore) {
                nextCursor = String.valueOf(currentPage + 1);
            }
        } else if (body.pageable() != null) {
            int currentPage = body.pageable().pageNumber();
            int totalPages = body.pageable().totalPages();
            hasMore = currentPage + 1 < totalPages;
            if (hasMore) {
                nextCursor = String.valueOf(currentPage + 1);
            }
        }

        return new CursorPage<>(jobs, nextCursor, hasMore);
    }

    private Integer determineSourceType(MasarItem item) {
        String name = item.sourceName() != null ? item.sourceName().toLowerCase() : (item.organizationName() != null ? item.organizationName().toLowerCase() : "");
        String domain = item.officialOrganizationDomain() != null ? item.officialOrganizationDomain().toLowerCase() : "";
        String collector = item.collectorType() != null ? item.collectorType().toLowerCase() : "";
        String type = item.sourceType() != null ? item.sourceType().toLowerCase() : "";
        
        if (collector.contains("jadarat") || name.contains("jadarat")) return 2;
        if (collector.contains("linkedin") || name.contains("linkedin")) return 4;
        if (name.contains("university") || name.contains("جامعة")) return 11;
        if (collector.contains("recruitment") || type.contains("agency")) return 9;
        if (name.contains("ministry") || name.contains("وزارة") || type.contains("gov") || name.contains("authority") || name.contains("هيئة")) return 7;
        if (name.contains("hospital") || name.contains("مستشفى") || name.contains("medical")) return 7; // Government/semi-gov entities
        if (collector.contains("ats")) return 15;
        if (name.contains("neom") || name.contains("red sea") || name.contains("qiddiya") || name.contains("roshn")) return 6; // Mega projects
        if (name.contains("twitter") || name.contains("x.com")) return 10; // Social media
        
        return 1; // Default to Official company websites
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MasarPage(List<MasarItem> content, MasarPageable pageable, Integer totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MasarPageable(int pageNumber, int pageSize, long totalElements, int totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MasarItem(
            Long id,
            String title,
            String organizationName,
            String sourceName,
            String sector,
            String coverage,
            String opportunityTypes,
            String sourceUrl,
            String normalizedSourceUrl,
            String officialOrganizationDomain,
            String sourceType,
            String discoveryMethod,
            Boolean verified,
            String collectorType,
            String monitoringState,
            String healthStatus,
            String priority,
            String lifecycleStatus
    ) {}
}
