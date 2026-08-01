package com.talentshift.hub.integration.client;

import com.fasterxml.jackson.databind.JsonNode;

public interface TalentShiftClient {
    TransferResponse transfer(String idempotencyKey, JsonNode payload);
    record TransferResponse(boolean success, int statusCode, String body) {}
}
