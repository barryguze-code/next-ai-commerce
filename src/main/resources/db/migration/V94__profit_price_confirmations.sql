CREATE TABLE profit_price_confirmations (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id),
 connection_id uuid NOT NULL, seller_sku text NOT NULL,
 old_price numeric(19,2) NOT NULL, new_price numeric(19,2) NOT NULL CHECK(new_price>0),
 requested_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'PREVIEW' CHECK(status IN ('PREVIEW','SENDING','ACCEPTED','UNCONFIRMED','CONFLICT')),
 updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,connection_id) REFERENCES marketplace_connections(tenant_id,id)
);
ALTER TABLE profit_price_confirmations ENABLE ROW LEVEL SECURITY;
ALTER TABLE profit_price_confirmations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON profit_price_confirmations
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
