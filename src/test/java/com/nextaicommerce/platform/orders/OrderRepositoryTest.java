package com.nextaicommerce.platform.orders;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OrderRepositoryTest {
    @Test
    void marketplaceTimeLookupUsesTheFoundationSchemaColumn() {
        assertThat(OrderRepository.MARKETPLACE_IDENTIFIER_SQL)
            .contains("SELECT marketplace_identifier FROM marketplace_connections")
            .doesNotContain("marketplace_id FROM");
    }

    @Test
    void warehouseDepartureStatusesIncludePickupAndShipmentVariants() {
        assertThat(OrderRepository.statusLeavesWarehouse("Shipped")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Shipped - Out for Delivery")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Shipped · Out for Delivery")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Waiting for pick up")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("ReadyForPickup")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Picked up")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("In transit")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Out for delivery")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Delivered")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Returned")).isTrue();
        assertThat(OrderRepository.statusLeavesWarehouse("Unshipped")).isFalse();
        assertThat(OrderRepository.statusLeavesWarehouse("Partially shipped")).isFalse();
        assertThat(OrderRepository.statusLeavesWarehouse("Partially shipped - awaiting remaining items")).isFalse();
        assertThat(OrderRepository.statusLeavesWarehouse("Pending")).isFalse();
    }

    @Test
    void openUnshippedOrdersReserveWarehouseInventory() {
        assertThat(OrderRepository.statusReservesInventory("Pending")).isTrue();
        assertThat(OrderRepository.statusReservesInventory("Unshipped")).isTrue();
        assertThat(OrderRepository.statusReservesInventory("Shipped - Delivered to Buyer")).isFalse();
        assertThat(OrderRepository.statusReservesInventory("Shipped - Waiting for Pick Up")).isFalse();
        assertThat(OrderRepository.statusReservesInventory("Shipped - Out for Delivery")).isFalse();
        assertThat(OrderRepository.statusReservesInventory("Cancelled")).isFalse();
    }
}
