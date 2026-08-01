package com.talentshift.hub.integration.deduplication.source;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceMatchPolicyTest {
    private final SourceMatchPolicy policy=new SourceMatchPolicy();
    @Test void urlIsStrong(){assertThat(policy.decide("u","u","a","b","x","y",null,null,null,null).automatic()).isTrue();}
    @Test void atsTenantIsStrong(){assertThat(policy.decide(null,null,null,null,null,null,"greenhouse","GREENHOUSE","tenant","tenant").automatic()).isTrue();}
    @Test void domainOnlyRequiresReview(){var d=policy.decide(null,null,"acme.test","acme.test","acme","other",null,null,null,null);assertThat(d.automatic()).isFalse();assertThat(d.confidence()).isBetween(.75,.95);}
    @Test void unrelatedSourcesRemainSeparate(){assertThat(policy.decide("a","b","a.test","b.test","a","b",null,null,null,null).confidence()).isZero();}
}
