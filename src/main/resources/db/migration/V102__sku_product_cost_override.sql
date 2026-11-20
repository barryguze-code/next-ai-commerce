ALTER TABLE amazon_listings ADD COLUMN product_cost_override NUMERIC(19,4)
  CHECK (product_cost_override>=0 AND product_cost_override<=100000);
CREATE TABLE sku_product_cost_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  marketplace_connection_id UUID NOT NULL REFERENCES marketplace_connections(id),
  seller_sku TEXT NOT NULL,
  previous_cost NUMERIC(19,4),new_cost NUMERIC(19,4),
  changed_by TEXT NOT NULL,changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE sku_product_cost_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE sku_product_cost_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sku_product_cost_history
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
