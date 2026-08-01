package com.talentshift.hub.integration.deduplication.job;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

@Component
public class JobMatchPolicy {
    private final JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();
    public Decision decide(boolean sameSourceAndExternalId, String urlA, String urlB, String applyA, String applyB,
                           String fingerprintA, String fingerprintB, String titleA, String titleB, String locationA, String locationB) {
        return new Decision("NO_MATCH", 0.0, false, false);
    }
    private double score(String a, String b) { return a == null || b == null ? 0 : similarity.apply(a.toLowerCase(), b.toLowerCase()); }
    private boolean eq(String a, String b) { return a != null && b != null && a.equals(b); }
    public record Decision(String reason, double confidence, boolean automatic, boolean review) {}
}
