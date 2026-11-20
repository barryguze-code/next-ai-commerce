-- Local-UAT source data for the temporary internal packing-slip feature.
-- The uploaded history is scoped to one tenant and Amazon store; it is never
-- used to call Amazon or purchase a label.
CREATE TABLE temporary_order_packaging_lookup (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_id VARCHAR(40) NOT NULL,
    order_item_summary TEXT NOT NULL,
    packaging VARCHAR(120) NOT NULL,
    order_sku_qty_list TEXT NOT NULL,
    source_note VARCHAR(160) NOT NULL DEFAULT 'Local UAT packaging history',
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, marketplace_connection_id, amazon_order_id),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX temporary_order_packaging_lookup_order_idx
    ON temporary_order_packaging_lookup(tenant_id, marketplace_connection_id, amazon_order_id);

ALTER TABLE temporary_order_packaging_lookup ENABLE ROW LEVEL SECURITY;
ALTER TABLE temporary_order_packaging_lookup FORCE ROW LEVEL SECURITY;
CREATE POLICY temporary_order_packaging_lookup_isolation ON temporary_order_packaging_lookup
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
