package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CollaborationCompletionWindowTest {
    @Test void monthAndWeekOverlapHasOneOwnerAndNoGap() {
        var snapshot = Instant.parse("2026-11-02T20:00:00Z");
        var ranges = CollaborationCompletionWindow.at(snapshot, ZoneId.of("America/Los_Angeles"));
        assertThat(ranges).extracting(CollaborationCompletionWindow::key)
            .containsExactly("THIS_WEEK", "LAST_WEEK", "LAST_MONTH", "OLDER");
        for (Instant t = snapshot.minusSeconds(120L * 86400); t.isBefore(snapshot); t = t.plusSeconds(3600)) {
            Instant sample = t;
            assertThat(ranges.stream().filter(w -> w.contains(sample))).hasSize(1);
        }
        assertThat(ranges.stream().anyMatch(w -> w.contains(snapshot))).isFalse();
    }

    @Test void daylightSavingUsesCalendarMidnightNotSevenTimesTwentyFourHours() {
        var ranges = CollaborationCompletionWindow.at(Instant.parse("2026-11-02T20:00:00Z"), ZoneId.of("America/Los_Angeles"));
        var lastWeek = ranges.stream().filter(w -> w.key().equals("LAST_WEEK")).findFirst().orElseThrow();
        assertThat(lastWeek.from()).isEqualTo(Instant.parse("2026-10-26T07:00:00Z"));
        assertThat(lastWeek.until()).isEqualTo(Instant.parse("2026-11-02T08:00:00Z"));
    }

    @Test void midMonthHasAllFiveRangesAndUsesViewerZone() {
        var ranges = CollaborationCompletionWindow.at(Instant.parse("2026-11-20T12:00:00Z"), ZoneId.of("Asia/Tokyo"));
        assertThat(ranges).extracting(CollaborationCompletionWindow::key)
            .containsExactly("THIS_WEEK", "LAST_WEEK", "EARLIER_THIS_MONTH", "LAST_MONTH", "OLDER");
        assertThat(ranges.get(0).from()).isEqualTo(Instant.parse("2026-11-15T15:00:00Z"));
        assertThat(ranges.get(2).from()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(ranges.get(3).until()).isEqualTo(ranges.get(2).from());
    }
}
