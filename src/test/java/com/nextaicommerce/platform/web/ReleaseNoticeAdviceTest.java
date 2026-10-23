package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReleaseNoticeAdviceTest {
    private final Instant now=Instant.parse("2026-10-23T18:00:00Z");
    @Test void defaultsOff() { assertThat(new ReleaseNoticeAdvice(false,"","","","").notice()).isNull(); }
    @Test void minorUpgradeEligible() { assertThat(ReleaseNoticeAdvice.featureUpgrade("1.6.9","1.7.0")).isTrue(); }
    @Test void patchHotfixIneligibleEvenWhenEnabled() { assertThat(new ReleaseNoticeAdvice(true,"patch","2999-01-01T00:00:00Z","1.6.9","1.6.10").notice()).isNull(); }
    @Test void majorUpgradeEligible() { assertThat(ReleaseNoticeAdvice.featureUpgrade("1.6.9","2.0.0")).isTrue(); }
    @Test void malformedSameOrDowngradeIneligible() {
        assertThat(ReleaseNoticeAdvice.featureUpgrade(null,"1.7.0")).isFalse();
        assertThat(ReleaseNoticeAdvice.featureUpgrade("1.6.9","1.6.9")).isFalse();
        assertThat(ReleaseNoticeAdvice.featureUpgrade("1.7.0","1.6.0")).isFalse();
        assertThat(ReleaseNoticeAdvice.featureUpgrade("1.6.9","1.7.1")).isFalse();
        assertThat(ReleaseNoticeAdvice.featureUpgrade("invalid","1.7.0")).isFalse();
    }
    @Test void explicitCurrentNotice() { assertThat(ReleaseNoticeAdvice.current(true,"update-1","2026-10-23T19:00:00Z",now)).isEqualTo(new ReleaseNoticeAdvice.Notice("update-1","2026-10-23T19:00:00Z")); }
    @Test void expiryDoesNotAssertCompletion() { assertThat(ReleaseNoticeAdvice.current(true,"update-1",now.toString(),now)).isNull(); }
    @Test void missingExpiryFailsClosed() { assertThat(ReleaseNoticeAdvice.current(true,"update-1","",now)).isNull(); }
    @Test void invalidVersionFailsClosed() { assertThat(ReleaseNoticeAdvice.current(true,"unsafe notice!","2026-10-23T19:00:00Z",now)).isNull(); }
    @Test void staleEvidenceFailsClosed() { assertThat(ReleaseNoticeAdvice.current(true,"update-1","2026-10-22T19:00:00Z",now)).isNull(); }
}
