CREATE TABLE platform_administrators (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    role VARCHAR(30) NOT NULL DEFAULT 'SUPER_ADMIN' CHECK (role = 'SUPER_ADMIN'),
    active BOOLEAN NOT NULL DEFAULT true,
    granted_by UUID REFERENCES app_users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by UUID REFERENCES app_users(id),
    revoked_at TIMESTAMPTZ,
    CHECK ((active AND revoked_at IS NULL) OR (NOT active AND revoked_at IS NOT NULL))
);

CREATE TABLE tenant_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    email VARCHAR(320) NOT NULL,
    role VARCHAR(30) NOT NULL REFERENCES platform_roles(code),
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    invited_by UUID NOT NULL REFERENCES app_users(id),
    accepted_by UUID REFERENCES app_users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (email = lower(email)),
    CHECK (expires_at > created_at),
    CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX tenant_invitations_pending_email_idx
    ON tenant_invitations (tenant_id, email)
    WHERE status = 'PENDING';

ALTER TABLE tenant_invitations
    ADD CONSTRAINT tenant_invitations_tenant_id_id_uk UNIQUE (tenant_id, id);

ALTER TABLE marketplace_connections
    ADD CONSTRAINT marketplace_connections_tenant_id_id_uk UNIQUE (tenant_id, id);

CREATE TABLE invitation_store_access (
    invitation_id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    marketplace_connection_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (invitation_id, marketplace_connection_id),
    FOREIGN KEY (tenant_id, invitation_id)
        REFERENCES tenant_invitations(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE membership_store_access (
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    granted_by UUID NOT NULL REFERENCES app_users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, marketplace_connection_id),
    FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_memberships(tenant_id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id),
    actor_user_id UUID NOT NULL REFERENCES app_users(id),
    actor_is_super_admin BOOLEAN NOT NULL DEFAULT false,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160),
    reason VARCHAR(500),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (NOT actor_is_super_admin OR reason IS NOT NULL)
);

CREATE TRIGGER tenant_invitations_set_updated_at
    BEFORE UPDATE ON tenant_invitations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE tenant_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation_store_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_store_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_invitations FORCE ROW LEVEL SECURITY;
ALTER TABLE invitation_store_access FORCE ROW LEVEL SECURITY;
ALTER TABLE membership_store_access FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_invitations_isolation ON tenant_invitations
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY invitation_store_access_isolation ON invitation_store_access
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY membership_store_access_isolation ON membership_store_access
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY audit_events_isolation ON audit_events
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX tenant_invitations_tenant_idx ON tenant_invitations (tenant_id);
CREATE INDEX invitation_store_access_tenant_idx ON invitation_store_access (tenant_id);
CREATE INDEX membership_store_access_user_idx ON membership_store_access (tenant_id, user_id);
CREATE INDEX membership_store_access_store_idx ON membership_store_access (tenant_id, marketplace_connection_id);
CREATE INDEX audit_events_tenant_created_idx ON audit_events (tenant_id, created_at DESC);
CREATE INDEX audit_events_actor_idx ON audit_events (actor_user_id, created_at DESC);
