ALTER TABLE carrier_connections ADD COLUMN is_simulated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT carrier_simulation_sandbox CHECK (NOT is_simulated OR (environment='SANDBOX' AND credential_source='PLATFORM' AND registered_customer_payload IS NULL AND registered_customer_nonce IS NULL));
ALTER TABLE carrier_shipments ADD COLUMN is_simulated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT carrier_shipment_simulation_sandbox CHECK (NOT is_simulated OR environment='SANDBOX');

-- Deliberately separate from imported provider orders and every synchronization queue.
CREATE TABLE local_shipping_fixtures (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    marketplace_connection_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition)='object'),
    saved_parcel JSONB CHECK (saved_parcel IS NULL OR jsonb_typeof(saved_parcel)='object'),
    UNIQUE(tenant_id,marketplace_connection_id,id),
    FOREIGN KEY(tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id)
);
ALTER TABLE local_shipping_fixtures ENABLE ROW LEVEL SECURITY;
ALTER TABLE local_shipping_fixtures FORCE ROW LEVEL SECURITY;
CREATE POLICY local_shipping_fixtures_tenant ON local_shipping_fixtures
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
