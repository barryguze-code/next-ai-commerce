package com.nextaicommerce.platform.orders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exports exact queries for a separately authorized read-only database benchmark. */
@EnabledIfSystemProperty(named="exportOrderTabsSql", matches="true")
class OrderTabsSqlExportTest {
    static String legacySql() {
        String columns = OrderRepository.TAB_KEYS.stream().map(key ->
            "count(DISTINCT orders.id) FILTER (WHERE " + OrderRepository.tabPredicate(key)
            + "),coalesce(sum(item.quantity_ordered) FILTER (WHERE "
            + OrderRepository.tabPredicate(key) + "),0)").collect(Collectors.joining(","));
        return "SELECT " + columns + " FROM amazon_orders orders JOIN amazon_order_items item"
            + " ON item.tenant_id=orders.tenant_id AND item.marketplace_connection_id=orders.marketplace_connection_id"
            + " AND item.amazon_order_id=orders.amazon_order_id WHERE orders.tenant_id=? AND orders.marketplace_connection_id=?";
    }

    @Test void export() throws Exception {
        Files.createDirectories(Path.of(".local"));
        Files.writeString(Path.of(".local/order-tabs-legacy.sql"), legacySql());
        Files.writeString(Path.of(".local/order-tabs-candidate.sql"), OrderTabTotalsQuery.SQL);
    }
}
