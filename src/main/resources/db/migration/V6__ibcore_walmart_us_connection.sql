DO $$
DECLARE
    ibcore_tenant_id UUID;
BEGIN
    SELECT id INTO ibcore_tenant_id
    FROM tenants
    WHERE lower(display_name) = 'ibcore'
    ORDER BY created_at
    LIMIT 1;

    IF ibcore_tenant_id IS NOT NULL THEN
        PERFORM set_config('app.tenant_id', ibcore_tenant_id::text, true);

        INSERT INTO marketplace_connections (
            tenant_id, channel, seller_identifier, marketplace_identifier,
            credential_secret_ref, status, display_name
        ) VALUES (
            ibcore_tenant_id, 'WALMART', 'IBCORE-WALMART', 'Walmart-US',
            'aws-secretsmanager://next-ai-commerce/ibcore/walmart', 'PENDING', 'Ibcore Walmart'
        )
        ON CONFLICT (tenant_id, channel, seller_identifier, marketplace_identifier)
        DO UPDATE SET display_name = EXCLUDED.display_name;
    END IF;
END $$;
