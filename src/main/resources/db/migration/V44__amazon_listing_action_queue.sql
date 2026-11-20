CREATE TABLE amazon_listing_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    seller_sku VARCHAR(240) NOT NULL,
    marketplace_id VARCHAR(120) NOT NULL,
    action_type VARCHAR(30) NOT NULL CHECK (action_type IN ('SALE_PRICE','ZERO_MFN_QUANTITY')),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    execute_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_by UUID REFERENCES app_users(id),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id, id),
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id)
);

CREATE INDEX amazon_listing_actions_due_idx
    ON amazon_listing_actions (tenant_id,status,execute_at,created_at)
    WHERE status='QUEUED';
CREATE INDEX amazon_listing_actions_item_idx
    ON amazon_listing_actions (tenant_id,account_catalog_item_id,created_at DESC);

CREATE TRIGGER amazon_listing_actions_set_updated_at BEFORE UPDATE ON amazon_listing_actions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE amazon_listing_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE amazon_listing_actions FORCE ROW LEVEL SECURITY;
CREATE POLICY amazon_listing_actions_isolation ON amazon_listing_actions
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
