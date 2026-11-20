-- Order reports supply money at line level. Materialize the customer order total for fast dashboards.
WITH totals AS (
    SELECT tenant_id,marketplace_connection_id,amazon_order_id,
           sum(coalesce(item_price,0)+coalesce(item_tax,0)+coalesce(shipping_price,0)
               +coalesce(shipping_tax,0)-coalesce(promotion_discount,0)) total,
           max(currency) currency
    FROM amazon_order_items
    GROUP BY tenant_id,marketplace_connection_id,amazon_order_id
)
UPDATE amazon_orders orders
SET order_total=totals.total,currency=coalesce(totals.currency,orders.currency),updated_at=now()
FROM totals
WHERE orders.tenant_id=totals.tenant_id
  AND orders.marketplace_connection_id=totals.marketplace_connection_id
  AND orders.amazon_order_id=totals.amazon_order_id;
