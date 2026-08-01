package com.talentshift.hub.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties("hub")
public record HubProperties(Security security, Import imports, Map<String, RemoteSystem> systems, RemoteSystem talentShift) {
    public record Security(String username, String password) {}
    public record Import(int pageSize) {}
    public record RemoteSystem(String baseUrl, String authToken) {}
}
