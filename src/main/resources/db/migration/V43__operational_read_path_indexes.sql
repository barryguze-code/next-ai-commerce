-- Cover the high-frequency Marketplace SKU and receiving lookup paths.
-- These indexes keep page reads proportional to the visible result set as data grows.

CREATE INDEX IF NOT EXISTS amazon_inventory_snapshots_latest_cover_idx
    ON amazon_inventory_snapshots
      (tenant_id,marketplace_connection_id,marketplace_id,seller_sku,snapshot_at DESC)
    INCLUDE (fnsku,fulfillable_quantity,inbound_working_quantity,inbound_shipped_quantity,
             inbound_receiving_quantity,reserved_quantity,unfulfillable_quantity);

CREATE INDEX IF NOT EXISTS amazon_fee_estimates_latest_cover_idx
    ON amazon_fee_estimates
      (tenant_id,marketplace_connection_id,marketplace_id,seller_sku,effective_at DESC)
    INCLUDE (estimated_fee_total,currency);

CREATE INDEX IF NOT EXISTS amazon_order_items_sku_order_cover_idx
    ON amazon_order_items (tenant_id,marketplace_connection_id,seller_sku,amazon_order_id)
    INCLUDE (quantity_ordered,item_price,shipping_price);

CREATE INDEX IF NOT EXISTS marketplace_sku_active_lookup_idx
    ON marketplace_sku_mappings (tenant_id,marketplace_connection_id,marketplace_sku)
    INCLUDE (id,account_catalog_item_id,quantity_per_marketplace_unit,asin)
    WHERE status='ACTIVE';

CREATE INDEX IF NOT EXISTS marketplace_sku_active_item_cover_idx
    ON marketplace_sku_mappings (tenant_id,account_catalog_item_id,marketplace_connection_id)
    INCLUDE (marketplace_sku,asin)
    WHERE status='ACTIVE';

CREATE INDEX IF NOT EXISTS account_catalog_active_sku_lower_idx
    ON account_catalog_items (tenant_id,lower(account_sku))
    WHERE status='ACTIVE' AND account_sku IS NOT NULL;

CREATE INDEX IF NOT EXISTS vendor_catalog_offer_current_display_idx
    ON vendor_catalog_offers (tenant_id,account_catalog_item_id,is_default DESC,updated_at DESC)
    INCLUDE (vendor_id,vendor_item_code,list_cost,discount_rate,currency)
    WHERE effective_to IS NULL;

CREATE INDEX IF NOT EXISTS amazon_listings_product_image_lookup_idx
    ON amazon_listings (tenant_id,upper(product_id),last_seen_at DESC)
    INCLUDE (image_url)
    WHERE image_url IS NOT NULL AND product_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS amazon_listings_sku_image_lookup_idx
    ON amazon_listings (tenant_id,upper(seller_sku),last_seen_at DESC)
    INCLUDE (image_url)
    WHERE image_url IS NOT NULL;
