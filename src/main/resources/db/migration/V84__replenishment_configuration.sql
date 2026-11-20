-- Local v1.4 development: preferences only; no marketplace or purchasing writes.
CREATE TABLE replenishment_settings (
 tenant_id uuid PRIMARY KEY REFERENCES tenants(id),
 configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
 updated_at timestamptz NOT NULL DEFAULT now(), updated_by text NOT NULL
);
ALTER TABLE replenishment_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE replenishment_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON replenishment_settings
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
