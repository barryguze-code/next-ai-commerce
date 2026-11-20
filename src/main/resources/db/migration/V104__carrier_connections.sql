CREATE TABLE carrier_connections (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    carrier TEXT NOT NULL DEFAULT 'FEDEX' CHECK (carrier='FEDEX'),
    display_name VARCHAR(120) NOT NULL,
    account_number VARCHAR(20) NOT NULL,
    environment TEXT NOT NULL CHECK (environment IN ('SANDBOX','PRODUCTION')),
    credential_source TEXT NOT NULL CHECK (credential_source IN ('PLATFORM','MERCHANT')),
    credential_payload BYTEA,
    credential_nonce BYTEA,
    revision BIGINT NOT NULL DEFAULT 1,
    health_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (health_status IN ('PENDING','ACTIVE','ERROR')),
    health_checked_at TIMESTAMPTZ,
    health_message TEXT,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    CHECK ((credential_source='PLATFORM' AND credential_payload IS NULL AND credential_nonce IS NULL)
       OR (credential_source='MERCHANT' AND credential_payload IS NOT NULL AND octet_length(credential_nonce)=12)
       OR deleted_at IS NOT NULL)
);
CREATE UNIQUE INDEX carrier_connections_account_idx ON carrier_connections(tenant_id,carrier,environment,account_number) WHERE deleted_at IS NULL;
ALTER TABLE carrier_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE carrier_connections FORCE ROW LEVEL SECURITY;
CREATE POLICY carrier_connections_tenant ON carrier_connections
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
