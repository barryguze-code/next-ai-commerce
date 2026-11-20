-- Existing imported orders are historical. Orders arriving after this store boundary may operate inventory.
ALTER TABLE marketplace_connections ADD COLUMN inventory_activated_at TIMESTAMPTZ;
-- This table uses FORCE ROW LEVEL SECURITY. Flyway intentionally has no tenant context, so a plain
-- backfill would update zero legacy rows and the following NOT NULL constraint would fail.
ALTER TABLE marketplace_connections NO FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_connections DISABLE ROW LEVEL SECURITY;
UPDATE marketplace_connections SET inventory_activated_at=now() WHERE inventory_activated_at IS NULL;
ALTER TABLE marketplace_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_connections FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_connections ALTER COLUMN inventory_activated_at SET NOT NULL;

ALTER TABLE amazon_orders
    ADD COLUMN operational_scope VARCHAR(24) NOT NULL DEFAULT 'HISTORICAL'
        CHECK (operational_scope IN ('HISTORICAL','LIVE','PRE_ACTIVATION_OPEN')),
    ADD COLUMN fulfillment_state VARCHAR(30) NOT NULL DEFAULT 'REPORTING_ONLY'
        CHECK (fulfillment_state IN ('REPORTING_ONLY','AMAZON_FULFILLED','NEEDS_MAPPING',
            'INVENTORY_SHORTAGE','READY_TO_SHIP','SHIPPED','CANCELLED','ON_HOLD')),
    ADD COLUMN operational_updated_at TIMESTAMPTZ;

CREATE UNIQUE INDEX amazon_order_items_tenant_id_idx ON amazon_order_items(tenant_id,id);

CREATE TABLE order_inventory_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_id VARCHAR(40) NOT NULL,
    amazon_order_item_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    expiration_date DATE,
    quantity NUMERIC(16,4) NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','RELEASED','SHIPPED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,marketplace_connection_id,amazon_order_id)
        REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,amazon_order_item_id)
        REFERENCES amazon_order_items(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,account_catalog_item_id)
        REFERENCES account_catalog_items(tenant_id,id)
);

CREATE TRIGGER order_inventory_reservations_set_updated_at BEFORE UPDATE ON order_inventory_reservations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE order_inventory_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_inventory_reservations FORCE ROW LEVEL SECURITY;
CREATE POLICY order_inventory_reservations_isolation ON order_inventory_reservations
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE INDEX order_inventory_reservations_available_idx
    ON order_inventory_reservations(tenant_id,account_catalog_item_id,expiration_date)
    WHERE status='ACTIVE';
CREATE UNIQUE INDEX order_inventory_reservations_one_active_layer_idx
    ON order_inventory_reservations(tenant_id,amazon_order_item_id,account_catalog_item_id,
        coalesce(expiration_date,DATE 'infinity'))
    WHERE status='ACTIVE';
CREATE INDEX amazon_orders_operational_idx
    ON amazon_orders(tenant_id,marketplace_connection_id,operational_scope,fulfillment_state,purchase_date DESC);
