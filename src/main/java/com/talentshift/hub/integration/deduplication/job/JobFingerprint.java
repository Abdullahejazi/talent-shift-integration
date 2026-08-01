package com.talentshift.hub.integration.deduplication.job;

import com.talentshift.hub.integration.client.JobRecordDto;
import com.talentshift.hub.integration.normalization.NormalizationService;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

@Component
public class JobFingerprint {
    private final NormalizationService normalization;
    public JobFingerprint(NormalizationService normalization) { this.normalization = normalization; }
    public String of(JobRecordDto j) {
        String joined = Stream.of(j.organizationName(), j.title(), j.location(), j.employmentType(), j.requisitionId())
                .map(normalization::text).map(v -> v == null ? "" : v).reduce((a,b) -> a + "|" + b).orElse("");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
