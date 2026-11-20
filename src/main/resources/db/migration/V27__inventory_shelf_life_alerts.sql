CREATE TABLE inventory_shelf_life_policies (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    minimum_sellable_days INTEGER NOT NULL DEFAULT 10 CHECK (minimum_sellable_days >= 0 AND minimum_sellable_days <= 365),
    warning_days INTEGER NOT NULL DEFAULT 30 CHECK (warning_days >= 1 AND warning_days <= 730),
    updated_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (warning_days > minimum_sellable_days)
);

CREATE TABLE inventory_expiration_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    expiration_date DATE NOT NULL,
    action_type VARCHAR(24) NOT NULL CHECK (action_type IN ('DISCOUNT','DONATE','HOLD','REMOVE','OTHER')),
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','COMPLETED','CANCELLED')),
    notes VARCHAR(500),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX inventory_expiration_actions_one_plan_idx
    ON inventory_expiration_actions (tenant_id, account_catalog_item_id, expiration_date)
    WHERE status='PLANNED';
CREATE INDEX inventory_expiration_actions_tenant_idx
    ON inventory_expiration_actions (tenant_id, expiration_date, status);

CREATE TRIGGER inventory_shelf_life_policies_set_updated_at BEFORE UPDATE ON inventory_shelf_life_policies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER inventory_expiration_actions_set_updated_at BEFORE UPDATE ON inventory_expiration_actions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE inventory_shelf_life_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_shelf_life_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_expiration_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_expiration_actions FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_shelf_life_policies_isolation ON inventory_shelf_life_policies
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY inventory_expiration_actions_isolation ON inventory_expiration_actions
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
