-- A history lookup can match the same ASIN-and-quantity combination across orders.
CREATE INDEX temporary_order_packaging_lookup_signature_idx
    ON temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,order_item_summary,imported_at DESC);
