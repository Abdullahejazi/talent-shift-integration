package com.talentshift.hub.integration.client;

import jakarta.validation.constraints.NotBlank;

public record JobRecordDto(
        @NotBlank String externalRecordId, @NotBlank String sourceExternalRecordId, @NotBlank String title,
        String organizationName, String location, String employmentType, String requisitionId,
        String jobUrl, String applyUrl) {}
