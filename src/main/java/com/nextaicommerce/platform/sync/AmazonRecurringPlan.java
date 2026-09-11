package com.nextaicommerce.platform.sync;

import java.time.Duration;
import java.util.List;

public final class AmazonRecurringPlan {
    public record Schedule(String key, String runType, Duration cadence, Duration lookback,
            int priority, List<String> jobs) {}

    private static final List<Schedule> SCHEDULES=List.of(
        schedule("ORDER_CHANGES", "INCREMENTAL", Duration.ofMinutes(5), Duration.ofMinutes(15), 10,
            "ORDERS_API_DELTA"),
        schedule("CURRENT_INVENTORY", "INCREMENTAL", Duration.ofHours(1), Duration.ZERO, 20,
            "INVENTORY_SNAPSHOT"),
        schedule("RECENT_ORDER_RECONCILIATION", "RECONCILIATION", Duration.ofHours(6), Duration.ofDays(7), 30,
            "ORDERS_30_DAY", "ORDER_ITEMS_30_DAY"),
        schedule("RETURNS", "INCREMENTAL", Duration.ofHours(4), Duration.ofDays(7), 40,
            "RETURNS_30_DAY"),
        schedule("FINANCES", "INCREMENTAL", Duration.ofHours(4), Duration.ofDays(7), 50,
            "FINANCES_30_DAY"),
        schedule("ORDER_LIFECYCLE", "RECONCILIATION", Duration.ofDays(1), Duration.ofDays(30), 60,
            "ORDERS_30_DAY", "ORDER_ITEMS_30_DAY"),
        schedule("LISTINGS", "RECONCILIATION", Duration.ofDays(1), Duration.ZERO, 70,
            "LISTINGS_SNAPSHOT"),
        schedule("INVENTORY_LEDGER", "RECONCILIATION", Duration.ofDays(1), Duration.ofDays(14), 80,
            "INVENTORY_LEDGER_30_DAY"),
        schedule("REIMBURSEMENTS", "RECONCILIATION", Duration.ofDays(1), Duration.ofDays(30), 90,
            "REIMBURSEMENTS_30_DAY"),
        schedule("FBA_FEES", "RECONCILIATION", Duration.ofDays(1), Duration.ZERO, 100,
            "FEES_SNAPSHOT"),
        schedule("FBA_CUSTOMER_SHIPMENTS", "RECONCILIATION", Duration.ofDays(1), Duration.ofDays(7), 110,
            "FBA_CUSTOMER_SHIPMENTS_30_DAY"));

    private AmazonRecurringPlan() {}

    public static List<Schedule> schedules() { return SCHEDULES; }

    private static Schedule schedule(String key,String runType,Duration cadence,Duration lookback,
            int priority,String... jobs) {
        return new Schedule(key,runType,cadence,lookback,priority,List.of(jobs));
    }
}
