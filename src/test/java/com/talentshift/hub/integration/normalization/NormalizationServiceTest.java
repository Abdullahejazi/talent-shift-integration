package com.talentshift.hub.integration.normalization;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NormalizationServiceTest {
    private final NormalizationService service=new NormalizationService();
    @Test void duplicateSourceUrlsNormalizeIdentically(){
        assertThat(service.url("HTTPS://WWW.Example.COM/jobs/?utm_source=old"))
                .isEqualTo(service.url("https://example.com/jobs"));
    }
    @Test void distinctPathsRemainSeparate(){
        assertThat(service.url("https://example.com/jobs/engineering"))
                .isNotEqualTo(service.url("https://example.com/jobs/finance"));
    }
}
