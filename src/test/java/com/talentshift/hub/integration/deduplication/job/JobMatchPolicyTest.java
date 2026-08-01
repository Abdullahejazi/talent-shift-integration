package com.talentshift.hub.integration.deduplication.job;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JobMatchPolicyTest {
    private final JobMatchPolicy policy=new JobMatchPolicy();
    @Test void externalIdHasHighestPriority(){assertThat(policy.decide(true,null,null,null,null,"a","b","x","y","a","b").reason()).isEqualTo("SOURCE_EXTERNAL_ID");}
    @Test void normalizedUrlIsStrong(){assertThat(policy.decide(false,"url","url",null,null,"a","b","x","y","a","b").reason()).isEqualTo("NORMALIZED_JOB_URL");}
    @Test void fingerprintIsStrong(){assertThat(policy.decide(false,null,null,null,null,"same","same","x","y","a","b").automatic()).isTrue();}
    @Test void fuzzyMatchRequiresReview(){var d=policy.decide(false,null,null,null,null,"a","b","Senior Software Engineer","Sr Software Engineer","Riyadh","Riyadh");assertThat(d.review()).isTrue();assertThat(d.automatic()).isFalse();}
    @Test void unrelatedJobsRemainSeparate(){var d=policy.decide(false,null,null,null,null,"a","b","Accountant","Forklift Operator","Jeddah","Dammam");assertThat(d.automatic()).isFalse();assertThat(d.review()).isFalse();}
}
