ALTER TABLE amazon_listings ADD COLUMN profit_package_type VARCHAR(30)
 CHECK (profit_package_type IN ('FEDEX_XSMALL','FEDEX_PAK','FEDEX_SMALL','FEDEX_MEDIUM','FEDEX_LARGE','FEDEX_XLARGE','UPS'));
CREATE TABLE profit_order_packages (
 tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL, amazon_order_id VARCHAR(40) NOT NULL,
 sequence INTEGER NOT NULL CHECK (sequence BETWEEN 1 AND 50),
 description VARCHAR(120) NOT NULL, amount NUMERIC(19,2) NOT NULL CHECK (amount>=0),
 updated_by TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id,amazon_order_id,sequence),
 FOREIGN KEY(tenant_id,marketplace_connection_id,amazon_order_id)
 REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id)
);
ALTER TABLE profit_order_packages ENABLE ROW LEVEL SECURITY;
ALTER TABLE profit_order_packages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON profit_order_packages
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
