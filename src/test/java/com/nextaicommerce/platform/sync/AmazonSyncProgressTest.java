package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AmazonSyncProgressTest {
    @Test
    void stageWeightsRepresentTheWholeInitialization() {
        int total = 0;
        for (String stage : new String[]{"VERIFY_SELLER", "LISTINGS_SNAPSHOT", "ORDERS_30_DAY",
                "ORDER_ITEMS_30_DAY", "INVENTORY_SNAPSHOT", "INVENTORY_LEDGER_30_DAY",
                "FBA_CUSTOMER_SHIPMENTS_30_DAY", "RETURNS_30_DAY", "FINANCES_30_DAY",
                "REIMBURSEMENTS_30_DAY", "FEES_SNAPSHOT", "FINAL_RECONCILIATION"}) {
            total += AmazonSyncProgress.weight(stage);
        }
        assertThat(total).isEqualTo(100);
    }

    @Test
    void runningStageShowsVisibleProgressBeforeCompletion() {
        assertThat(AmazonSyncProgress.displayPercent(0, "VERIFY_SELLER", "RUNNING")).isEqualTo(1);
        assertThat(AmazonSyncProgress.displayPercent(3, "LISTINGS_SNAPSHOT", "RUNNING")).isEqualTo(7);
        assertThat(AmazonSyncProgress.displayPercent(100, "FINAL_RECONCILIATION", "COMPLETED")).isEqualTo(100);
    }

    @Test
    void reportStagesSetAnHonestAmazonPreparationExpectation() {
        assertThat(AmazonSyncProgress.stageTiming("ORDERS_30_DAY"))
            .isEqualTo("Amazon report preparation may take up to an hour");
    }
}
