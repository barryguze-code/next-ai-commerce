-- Unit profit reuses the same scoped packaging history as packing slips.
CREATE INDEX temporary_order_packaging_lookup_sku_signature_idx
 ON temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,order_sku_qty_list,imported_at DESC);
