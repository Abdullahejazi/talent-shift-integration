package com.talentshift.hub.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!real-integrations")
public class MockTalentShiftClient implements TalentShiftClient {
    @Override public TransferResponse transfer(String idempotencyKey, JsonNode payload) {
        return new TransferResponse(true, 202, "accepted-by-mock");
    }
}
