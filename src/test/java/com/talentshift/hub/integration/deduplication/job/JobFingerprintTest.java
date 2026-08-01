package com.talentshift.hub.integration.deduplication.job;

import com.talentshift.hub.integration.client.JobRecordDto;
import com.talentshift.hub.integration.normalization.NormalizationService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JobFingerprintTest {
    @Test void normalizedFieldsProduceDeterministicFingerprint(){
        var f=new JobFingerprint(new NormalizationService());
        var a=new JobRecordDto("1","s","Senior Engineer","ACME LTD.","Riyadh","FULL TIME","R-1",null,null);
        var b=new JobRecordDto("2","s"," senior engineer ","acme ltd","RIYADH","full-time","R-1",null,null);
        assertThat(f.of(a)).isEqualTo(f.of(b));
    }
}
