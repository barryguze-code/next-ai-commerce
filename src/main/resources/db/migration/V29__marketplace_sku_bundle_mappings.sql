-- A marketplace SKU may contain one account product or a bundle of several products.
ALTER TABLE marketplace_sku_mappings
    ADD COLUMN mapping_source VARCHAR(20) NOT NULL DEFAULT 'LEGACY'
        CHECK (mapping_source IN ('LEGACY','AUTO','MANUAL')),
    ADD COLUMN auto_map_blocked BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX marketplace_sku_mappings_tenant_id_idx
    ON marketplace_sku_mappings (tenant_id, id);

CREATE TABLE marketplace_sku_mapping_components (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_sku_mapping_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    quantity NUMERIC(14,4) NOT NULL CHECK (quantity > 0),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_sku_mapping_id)
        REFERENCES marketplace_sku_mappings(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, account_catalog_item_id)
        REFERENCES account_catalog_items(tenant_id, id),
    UNIQUE (tenant_id, marketplace_sku_mapping_id, account_catalog_item_id)
);

INSERT INTO marketplace_sku_mapping_components
    (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order)
SELECT tenant_id,id,account_catalog_item_id,quantity_per_marketplace_unit,0
FROM marketplace_sku_mappings
WHERE status='ACTIVE';

CREATE TRIGGER marketplace_sku_mapping_components_set_updated_at
    BEFORE UPDATE ON marketplace_sku_mapping_components
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE marketplace_sku_mapping_components ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sku_mapping_components FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_sku_mapping_components_isolation ON marketplace_sku_mapping_components
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE INDEX marketplace_sku_mapping_components_mapping_idx
    ON marketplace_sku_mapping_components (tenant_id, marketplace_sku_mapping_id, sort_order);
CREATE INDEX marketplace_sku_mapping_components_item_idx
    ON marketplace_sku_mapping_components (tenant_id, account_catalog_item_id);
