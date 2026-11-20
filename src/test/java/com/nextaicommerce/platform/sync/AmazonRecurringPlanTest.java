package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AmazonRecurringPlanTest {
    @Test
    void orderChangesUseFrequentOverlappingWindow() {
        var schedule=AmazonRecurringPlan.schedules().stream()
            .filter(candidate->"ORDER_CHANGES".equals(candidate.key())).findFirst().orElseThrow();
        assertThat(schedule.cadence()).isEqualTo(Duration.ofMinutes(5));
        assertThat(schedule.lookback()).isEqualTo(Duration.ofMinutes(15));
        assertThat(schedule.jobs()).containsExactly("ORDERS_API_DELTA");
    }

    @Test
    void reimbursementsAndFeesRefreshDaily() {
        assertThat(schedule("REIMBURSEMENTS").cadence()).isEqualTo(Duration.ofDays(1));
        assertThat(schedule("REIMBURSEMENTS").lookback()).isEqualTo(Duration.ofDays(30));
        assertThat(schedule("FBA_FEES").cadence()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void orderSafetyChecksUseSevenDayAndThirtyDayWindows() {
        assertThat(schedule("RECENT_ORDER_RECONCILIATION").cadence()).isEqualTo(Duration.ofHours(6));
        assertThat(schedule("RECENT_ORDER_RECONCILIATION").lookback()).isEqualTo(Duration.ofDays(7));
        assertThat(schedule("ORDER_LIFECYCLE").cadence()).isEqualTo(Duration.ofDays(1));
        assertThat(schedule("ORDER_LIFECYCLE").lookback()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void customerShipmentsHaveLowestReportPriority() {
        int shipmentPriority=schedule("FBA_CUSTOMER_SHIPMENTS").priority();
        assertThat(AmazonRecurringPlan.schedules().stream()
            .filter(candidate->!"FBA_CUSTOMER_SHIPMENTS".equals(candidate.key()))
            .allMatch(candidate->candidate.priority()<shipmentPriority)).isTrue();
    }

    private static AmazonRecurringPlan.Schedule schedule(String key) {
        return AmazonRecurringPlan.schedules().stream()
            .filter(candidate->key.equals(candidate.key())).findFirst().orElseThrow();
    }
}
