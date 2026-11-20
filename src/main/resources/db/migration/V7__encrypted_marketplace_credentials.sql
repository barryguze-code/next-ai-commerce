CREATE TABLE marketplace_connection_credentials (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    marketplace_connection_id UUID NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    encryption_nonce BYTEA NOT NULL,
    key_version SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, marketplace_connection_id),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id, id) ON DELETE CASCADE
);

CREATE TRIGGER marketplace_connection_credentials_set_updated_at
    BEFORE UPDATE ON marketplace_connection_credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE marketplace_connection_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_connection_credentials FORCE ROW LEVEL SECURITY;

CREATE POLICY marketplace_connection_credentials_isolation
    ON marketplace_connection_credentials
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
