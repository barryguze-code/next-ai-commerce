-- Store dashboard totals do not constrain SKU; give them a bounded date-range index.
CREATE INDEX amazon_sku_refunds_store_period_idx ON amazon_sku_refunds
 (tenant_id,marketplace_connection_id,posted_date)
 INCLUDE(currency,item_refund_amount,transaction_id);
