package com.talentshift.hub.integration.deduplication.source;

import org.springframework.stereotype.Component;

@Component
public class SourceMatchPolicy {
    public Decision decide(String urlA, String urlB, String domainA, String domainB, String orgA, String orgB,
                           String atsA, String atsB, String tenantA, String tenantB) {
        if (eq(urlA,urlB)) return new Decision("NORMALIZED_URL", 1.0, true);
        if (eq(atsA,atsB) && eq(tenantA,tenantB)) return new Decision("ATS_TENANT", .99, true);
        if (eq(domainA,domainB) && eq(orgA,orgB)) return new Decision("DOMAIN_AND_ORGANIZATION", .96, true);
        if (eq(domainA,domainB)) return new Decision("OFFICIAL_DOMAIN", .88, false);
        if (eq(orgA,orgB)) return new Decision("ORGANIZATION_NAME", .82, false);
        return new Decision("NO_MATCH", 0, false);
    }
    private boolean eq(String a, String b) { return a != null && b != null && !a.isBlank() && a.equalsIgnoreCase(b); }
    public record Decision(String reason, double confidence, boolean automatic) {}
}
