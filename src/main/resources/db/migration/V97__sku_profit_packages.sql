CREATE TABLE profit_sku_packages (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 marketplace_connection_id UUID NOT NULL REFERENCES marketplace_connections(id),
 seller_sku TEXT NOT NULL,
 sequence INTEGER NOT NULL CHECK (sequence>0),
 description VARCHAR(120) NOT NULL,
 amount NUMERIC(19,2) NOT NULL CHECK (amount>=0),
 PRIMARY KEY (tenant_id,marketplace_connection_id,seller_sku,sequence)
);
ALTER TABLE profit_sku_packages ENABLE ROW LEVEL SECURITY;
ALTER TABLE profit_sku_packages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON profit_sku_packages
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
