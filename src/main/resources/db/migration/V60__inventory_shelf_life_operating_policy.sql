ALTER TABLE inventory_shelf_life_policies
    ADD COLUMN auto_remove_from_available BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN auto_sale_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN default_sale_discount_percent NUMERIC(5,2) NOT NULL DEFAULT 10.00
        CHECK (default_sale_discount_percent > 0 AND default_sale_discount_percent < 100),
    ADD COLUMN sale_start_days_before_expiration INTEGER NOT NULL DEFAULT 30
        CHECK (sale_start_days_before_expiration >= 1 AND sale_start_days_before_expiration <= 730),
    ADD COLUMN sale_duration_days INTEGER NOT NULL DEFAULT 7
        CHECK (sale_duration_days >= 1 AND sale_duration_days <= 180);

-- Existing accounts begin their sale plan exactly at their already-approved warning boundary.
UPDATE inventory_shelf_life_policies
SET sale_start_days_before_expiration=warning_days;

ALTER TABLE inventory_expiration_actions
    ADD COLUMN discount_percent NUMERIC(5,2)
        CHECK (discount_percent IS NULL OR (discount_percent > 0 AND discount_percent < 100)),
    ADD COLUMN starts_at TIMESTAMPTZ,
    ADD COLUMN ends_at TIMESTAMPTZ,
    ADD COLUMN removal_method VARCHAR(24)
        CHECK (removal_method IS NULL OR removal_method IN ('DONATE','DISPOSE','RETURN_TO_VENDOR','OTHER')),
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
        CHECK (source IN ('MANUAL','AUTOMATION'));

CREATE TABLE inventory_shelf_life_product_overrides (
    tenant_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    discount_percent NUMERIC(5,2) NOT NULL
        CHECK (discount_percent > 0 AND discount_percent < 100),
    sale_start_days_before_expiration INTEGER NOT NULL
        CHECK (sale_start_days_before_expiration >= 1 AND sale_start_days_before_expiration <= 730),
    sale_duration_days INTEGER NOT NULL
        CHECK (sale_duration_days >= 1 AND sale_duration_days <= 180),
    updated_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, account_catalog_item_id),
    FOREIGN KEY (tenant_id, account_catalog_item_id)
        REFERENCES account_catalog_items(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE inventory_expiration_action_targets (
    tenant_id UUID NOT NULL,
    action_id UUID NOT NULL,
    seller_sku VARCHAR(240) NOT NULL,
    discount_percent NUMERIC(5,2)
        CHECK (discount_percent IS NULL OR (discount_percent > 0 AND discount_percent < 100)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, action_id, seller_sku),
    FOREIGN KEY (tenant_id, action_id)
        REFERENCES inventory_expiration_actions(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX inventory_expiration_action_targets_sku_idx
    ON inventory_expiration_action_targets (tenant_id, upper(seller_sku));

CREATE TRIGGER inventory_shelf_life_product_overrides_set_updated_at
    BEFORE UPDATE ON inventory_shelf_life_product_overrides
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE inventory_shelf_life_product_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_shelf_life_product_overrides FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_expiration_action_targets ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_expiration_action_targets FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_shelf_life_product_overrides_isolation
    ON inventory_shelf_life_product_overrides
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE POLICY inventory_expiration_action_targets_isolation
    ON inventory_expiration_action_targets
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
