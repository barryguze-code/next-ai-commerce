-- Forward-only read-path indexes. No stock, prices or document records are changed.
CREATE INDEX IF NOT EXISTS amazon_listings_connection_sku_ci_idx
    ON amazon_listings (tenant_id,marketplace_connection_id,upper(seller_sku));
CREATE INDEX IF NOT EXISTS global_identifiers_display_idx
    ON global_product_identifiers (global_product_id,is_primary DESC,created_at) INCLUDE (identifier_value);
CREATE INDEX IF NOT EXISTS inventory_ledger_item_page_idx
    ON inventory_ledger_entries (tenant_id,account_catalog_item_id,occurred_at DESC,created_at DESC,id DESC);
