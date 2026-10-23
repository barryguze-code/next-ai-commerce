-- Preserve legacy profiles and packing history for audit, Amazon shipping and profit estimates.
-- FedEx selections are authoritative only after an operator saves a validated package.
CREATE TABLE fedex_order_packaging (
 tenant_id UUID NOT NULL,
 marketplace_connection_id UUID NOT NULL,
 amazon_order_id VARCHAR(40) NOT NULL,
 composition_key VARCHAR(64) NOT NULL,
 parcel JSONB NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id,amazon_order_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,amazon_order_id)
 REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id)
);
ALTER TABLE fedex_order_packaging ENABLE ROW LEVEL SECURITY;
ALTER TABLE fedex_order_packaging FORCE ROW LEVEL SECURITY;
CREATE POLICY fedex_order_packaging_tenant ON fedex_order_packaging
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
