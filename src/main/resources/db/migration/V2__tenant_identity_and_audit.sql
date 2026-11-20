CREATE TABLE platform_roles (
    code VARCHAR(30) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    description VARCHAR(240) NOT NULL,
    system_role BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform_roles (code, display_name, description) VALUES
    ('OWNER', 'Owner', 'Full tenant control, including membership and billing administration.'),
    ('ADMIN', 'Administrator', 'Manages tenant configuration, integrations, and operational users.'),
    ('OPERATOR', 'Operator', 'Runs day-to-day commerce operations.'),
    ('VIEWER', 'Viewer', 'Has read-only access to tenant data.');

ALTER TABLE tenant_memberships
    ADD CONSTRAINT tenant_memberships_role_fk
    FOREIGN KEY (role) REFERENCES platform_roles(code);

ALTER TABLE app_users
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN created_by UUID REFERENCES app_users(id),
    ADD COLUMN updated_by UUID REFERENCES app_users(id);

ALTER TABLE tenants
    ADD COLUMN created_by UUID REFERENCES app_users(id),
    ADD COLUMN updated_by UUID REFERENCES app_users(id);

ALTER TABLE tenant_memberships
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN created_by UUID REFERENCES app_users(id),
    ADD COLUMN updated_by UUID REFERENCES app_users(id);

ALTER TABLE marketplace_connections
    ADD COLUMN created_by UUID REFERENCES app_users(id),
    ADD COLUMN updated_by UUID REFERENCES app_users(id);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenants_set_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER app_users_set_updated_at
    BEFORE UPDATE ON app_users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER tenant_memberships_set_updated_at
    BEFORE UPDATE ON tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER marketplace_connections_set_updated_at
    BEFORE UPDATE ON marketplace_connections
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER platform_roles_set_updated_at
    BEFORE UPDATE ON platform_roles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX tenants_created_by_idx ON tenants (created_by);
CREATE INDEX app_users_created_by_idx ON app_users (created_by);
CREATE INDEX tenant_memberships_created_by_idx ON tenant_memberships (created_by);
CREATE INDEX marketplace_connections_created_by_idx ON marketplace_connections (created_by);
