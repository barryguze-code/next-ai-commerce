package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository;
import java.util.stream.Collectors;

/** Fresh, tenant-scoped totals: normalize each order and price each listing only once. */
final class OrderTabTotalsQuery {
    private OrderTabTotalsQuery() {}

    static final String SQL = build();

    private static String build() {
        String columns = OrderRepository.TAB_KEYS.stream().map(key -> {
            String condition = key.equals("BUY_BOX_LOST") ? "lost.amazon_order_id IS NOT NULL"
                : OrderRepository.tabPredicate(key).replace(
                    "regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g')",
                    "orders.normalized_status");
            return "count(*) FILTER (WHERE " + condition + "),coalesce(sum(orders.units) FILTER (WHERE " + condition + "),0)";
        }).collect(Collectors.joining(","));
        // Materialization prevents per-item expansion of the inventory and normalization work.
        // The initial inner join intentionally preserves the exclusion of orders with no items.
        return """
            WITH scope AS MATERIALIZED (SELECT ?::uuid tenant_id, ?::uuid connection_id),
            normalized_orders AS MATERIALIZED (
              SELECT orders.id,orders.amazon_order_id,orders.marketplace_id,orders.fulfillment_state,
                orders.platform_waiting_for_pickup,
                regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g') normalized_status,
                sum(item.quantity_ordered) units
              FROM scope JOIN amazon_orders orders ON orders.tenant_id=scope.tenant_id
                AND orders.marketplace_connection_id=scope.connection_id
              JOIN amazon_order_items item ON item.tenant_id=orders.tenant_id
                AND item.marketplace_connection_id=orders.marketplace_connection_id
                AND item.amazon_order_id=orders.amazon_order_id
              GROUP BY orders.id
            ), lost_skus AS MATERIALIZED (
              SELECT DISTINCT listing.marketplace_id,listing.seller_sku
              FROM amazon_listings listing JOIN scope ON listing.tenant_id=scope.tenant_id
                AND listing.marketplace_connection_id=scope.connection_id
              LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=listing.tenant_id
                AND mapping.marketplace_connection_id=listing.marketplace_connection_id
                AND mapping.marketplace_sku=listing.seller_sku AND mapping.status='ACTIVE'
            """ + MarketplaceSkuRepository.LOCAL_MAPPING_AVAILABILITY + """
              WHERE listing.price IS NOT NULL AND listing.buy_box_price IS NOT NULL
                AND coalesce(listing.currency,'USD')=coalesce(listing.buy_box_currency,listing.currency,'USD')
                AND listing.price<>listing.buy_box_price
                AND (CASE WHEN upper(coalesce(listing.fulfillment_channel,'')) LIKE '%AMAZON%'
                    OR upper(listing.fulfillment_channel) IN ('FBA','AFN')
                  THEN coalesce((SELECT snapshot.fulfillable_quantity FROM amazon_inventory_snapshots snapshot
                    WHERE snapshot.tenant_id=listing.tenant_id
                      AND snapshot.marketplace_connection_id=listing.marketplace_connection_id
                      AND snapshot.marketplace_id=listing.marketplace_id AND snapshot.seller_sku=listing.seller_sku
                    ORDER BY snapshot.snapshot_at DESC LIMIT 1),0)
                  ELSE coalesce(local_inventory.available,listing.quantity,0) END)>0
            ), lost_orders AS MATERIALIZED (
              SELECT DISTINCT priced_item.amazon_order_id,orders.marketplace_id
              FROM scope JOIN amazon_order_items priced_item ON priced_item.tenant_id=scope.tenant_id
                AND priced_item.marketplace_connection_id=scope.connection_id
              JOIN normalized_orders orders ON orders.amazon_order_id=priced_item.amazon_order_id
              JOIN lost_skus lost ON lost.seller_sku=priced_item.seller_sku AND lost.marketplace_id=orders.marketplace_id
            )
            SELECT
            """ + columns + """
              FROM normalized_orders orders LEFT JOIN lost_orders lost
                ON lost.amazon_order_id=orders.amazon_order_id AND lost.marketplace_id=orders.marketplace_id
            """;
    }
}
