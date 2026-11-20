package com.nextaicommerce.platform.collaboration;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Half-open, disjoint Done ranges pinned to one board snapshot in the viewer's zone. */
public record CollaborationCompletionWindow(String key, Instant from, Instant until) {
    public boolean contains(Instant completedAt) {
        return completedAt != null && (from == null || !completedAt.isBefore(from))
            && completedAt.isBefore(until);
    }

    public static List<CollaborationCompletionWindow> at(Instant snapshot, ZoneId zone) {
        LocalDate today = snapshot.atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var windows = new ArrayList<CollaborationCompletionWindow>();
        Instant end = snapshot;
        end = add(windows, "THIS_WEEK", monday, zone, end);
        end = add(windows, "LAST_WEEK", monday.minusWeeks(1), zone, end);
        end = add(windows, "EARLIER_THIS_MONTH", today.withDayOfMonth(1), zone, end);
        end = add(windows, "LAST_MONTH", today.withDayOfMonth(1).minusMonths(1), zone, end);
        windows.add(new CollaborationCompletionWindow("OLDER", null, end));
        return List.copyOf(windows);
    }

    private static Instant add(List<CollaborationCompletionWindow> windows, String key,
            LocalDate start, ZoneId zone, Instant end) {
        Instant from = start.atStartOfDay(zone).toInstant();
        // Week buckets take precedence when they cross a month/year boundary.
        if (!from.isBefore(end)) return end;
        windows.add(new CollaborationCompletionWindow(key, from, end));
        return from;
    }
}
