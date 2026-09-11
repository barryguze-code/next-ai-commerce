CREATE TABLE global_product_packaging_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    global_product_id UUID NOT NULL REFERENCES global_catalog_products(id) ON DELETE CASCADE,
    vendor_key VARCHAR(240) NOT NULL,
    normalized_vendor_item_code VARCHAR(160) NOT NULL,
    package_size VARCHAR(80),
    unit_of_measure VARCHAR(30) NOT NULL DEFAULT 'EA',
    units_per_case NUMERIC(12,4) NOT NULL CHECK (units_per_case > 0),
    units_of_sale NUMERIC(14,4),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED')),
    effective_from DATE NOT NULL DEFAULT current_date,
    effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX global_packaging_one_active_vendor_item_idx
    ON global_product_packaging_versions(vendor_key,normalized_vendor_item_code) WHERE status='ACTIVE';
CREATE INDEX global_packaging_product_history_idx
    ON global_product_packaging_versions(global_product_id,status,effective_from DESC);

ALTER TABLE vendor_catalog_offers ADD COLUMN packaging_version_id UUID
    REFERENCES global_product_packaging_versions(id);

CREATE TRIGGER global_product_packaging_versions_set_updated_at
    BEFORE UPDATE ON global_product_packaging_versions FOR EACH ROW EXECUTE FUNCTION set_updated_at();
