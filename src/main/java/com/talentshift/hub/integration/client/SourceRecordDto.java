package com.talentshift.hub.integration.client;

import jakarta.validation.constraints.NotBlank;

public record SourceRecordDto(
        @NotBlank String externalRecordId, @NotBlank String name, String url, String officialDomain,
        String organizationName, String atsProvider, String atsTenantId, Integer sourceType) {}
