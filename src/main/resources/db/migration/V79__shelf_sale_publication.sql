-- Independent of quantity publication. Only production's explicit allowlist can send prices.
CREATE TABLE shelf_sale_settings (
 tenant_id uuid PRIMARY KEY REFERENCES tenants(id), enabled boolean NOT NULL DEFAULT false,
 updated_at timestamptz NOT NULL DEFAULT now(), updated_by text
);
CREATE TABLE shelf_sale_publications (
 tenant_id uuid NOT NULL REFERENCES tenants(id), connection_id uuid NOT NULL,
 marketplace_id text NOT NULL, seller_sku text NOT NULL,
 owned_discount jsonb, pending_discount jsonb,
 status text NOT NULL DEFAULT 'CHECK', attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error text,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,connection_id,marketplace_id,seller_sku),
 FOREIGN KEY(tenant_id,connection_id) REFERENCES marketplace_connections(tenant_id,id)
);
CREATE TABLE shelf_sale_events (
 id bigserial PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id),
 connection_id uuid NOT NULL, seller_sku text NOT NULL, event text NOT NULL,
 detail text, created_at timestamptz NOT NULL DEFAULT now()
);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['shelf_sale_settings','shelf_sale_publications','shelf_sale_events'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
 END LOOP;
END $$;
