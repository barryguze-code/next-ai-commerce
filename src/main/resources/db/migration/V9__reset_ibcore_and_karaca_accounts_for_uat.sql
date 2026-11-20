DO $$
DECLARE
    target_tenant_id UUID;
BEGIN
    FOR target_tenant_id IN
        SELECT id FROM tenants
        WHERE lower(display_name) IN ('ibcore', 'karaca')
        ORDER BY created_at
    LOOP
        PERFORM set_config('app.tenant_id', target_tenant_id::text, true);
        DELETE FROM audit_events WHERE tenant_id = target_tenant_id;
        DELETE FROM tenant_invitations WHERE tenant_id = target_tenant_id;
        DELETE FROM marketplace_connections WHERE tenant_id = target_tenant_id;
        DELETE FROM tenant_memberships WHERE tenant_id = target_tenant_id;
        DELETE FROM tenants WHERE id = target_tenant_id;
    END LOOP;
END $$;
