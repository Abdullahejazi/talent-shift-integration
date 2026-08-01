package com.talentshift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobModuleTest {
    @Test
    void collectedJobNormalizesHtmlAndWhitespace() {
        CollectedJob job = new CollectedJob("TEST", "123", "  Java   Engineer ", " Example Co ", " Riyadh ",
                "Full-time", false, null, "Engineering", "<p>Build <strong>secure</strong> services</p>",
                "Java, Spring", "https://example.com/apply/123", "https://example.com/jobs/123",
                Instant.parse("2026-07-21T00:00:00Z"), "SA");
        assertThat(job.title()).isEqualTo("Java Engineer");
        assertThat(job.description()).isEqualTo("Build secure services");
    }

    @Test
    void collectedJobRejectsUnsafeApplyProtocol() {
        assertThatThrownBy(() -> new CollectedJob("TEST", "123", "Role", "Company", null, null, false,
                        null, null, null, null, "javascript:alert(1)", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP(S)");
    }

    @Test
    void policyAcceptsSaudiAndEnglishOrArabicWorldwideRemoteRoles() {
        SaudiJobPolicy policy = new SaudiJobPolicy();
        CollectedJob riyadh = job("Riyadh", false, null);
        CollectedJob worldwideRemote = new CollectedJob("TEST", "remote", "Software Engineer", "Company",
                "Worldwide", "Full-time", true, null, null,
                "You will work with our engineering team and need experience building secure services.",
                "The role requires strong communication skills.", "https://example.com/apply", "https://example.com/job",
                Instant.now(), null);
        CollectedJob berlin = job("Berlin, Germany", false, null);
        CollectedJob regionalRemote = new CollectedJob("TEST", "remote-us", "Platform Engineer", "Company",
                "Remote - United States", "Full-time", true, null, null,
                "You will work with our engineering team and need experience building reliable services.",
                "The role requires strong communication skills.", "https://example.com/apply-us",
                "https://example.com/job-us", Instant.now(), null);

        assertThat(policy.accepts(riyadh)).isTrue();
        assertThat(policy.accepts(worldwideRemote)).isTrue();
        assertThat(policy.accepts(regionalRemote)).isTrue();
        assertThat(policy.accepts(berlin)).isFalse();
        CollectedJob misleadingGlobalDescription = new CollectedJob("TEST", "brazil", "Engineer", "Company",
                "Porto Alegre, Brazil", "Full-time", true, null, null, "A global market leader", null,
                "https://example.com/apply", "https://example.com/job", Instant.now(), null);
        assertThat(policy.accepts(misleadingGlobalDescription)).isFalse();
    }

    @Test
    void dedupKeyMatchesAcrossDifferentSources() {
        CollectedJob first = new CollectedJob("SOURCE_A", "one", "Java Engineer", "Example Co", "Riyadh",
                null, false, null, null, null, null, "https://example.com/a", null, null, "SA");
        CollectedJob second = new CollectedJob("SOURCE_B", "two", " java  engineer ", "EXAMPLE CO", "riyadh",
                null, false, null, null, null, null, "https://example.com/b", null, null, "SA");

        assertThat(second.dedupKey()).isEqualTo(first.dedupKey());
    }

    private static CollectedJob job(String location, boolean remote, String countryCode) {
        return new CollectedJob("TEST", location, "Engineer", "Company", location, "Full-time", remote,
                null, null, "Build services", null, "https://example.com/apply", "https://example.com/job",
                Instant.parse("2026-07-21T00:00:00Z"), countryCode);
    }
}
