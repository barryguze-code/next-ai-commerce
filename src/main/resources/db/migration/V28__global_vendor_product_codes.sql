-- Vendor item codes are first-class product identity evidence. Prices remain account-private.
CREATE TABLE global_product_vendor_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    global_product_id UUID NOT NULL REFERENCES global_catalog_products(id) ON DELETE CASCADE,
    vendor_key VARCHAR(240) NOT NULL,
    vendor_name VARCHAR(240) NOT NULL,
    vendor_item_code VARCHAR(160) NOT NULL,
    normalized_item_code VARCHAR(160) NOT NULL,
    source_tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX global_product_vendor_code_identity_idx
    ON global_product_vendor_codes (vendor_key, normalized_item_code);
CREATE INDEX global_product_vendor_code_product_idx
    ON global_product_vendor_codes (global_product_id);

-- A platform delete is a reversible listing state; Amazon source history is never erased.
ALTER TABLE amazon_listings
    ADD COLUMN platform_status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE'
        CHECK (platform_status IN ('VISIBLE','DELETED')),
    ADD COLUMN platform_deleted_at TIMESTAMPTZ;
