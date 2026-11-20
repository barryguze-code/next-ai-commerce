-- Additive planning rates only; source transactions and carrier bills stay intact.
ALTER TABLE packing_package_aliases ADD COLUMN independent_rate_only BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE packing_package_rates (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 package_key TEXT NOT NULL CHECK(package_key=packing_package_key(package_key) AND length(package_key) BETWEEN 1 AND 120),
 currency TEXT NOT NULL CHECK(currency='USD'),
 effective_from DATE NOT NULL CHECK(effective_from >= DATE '2025-01-01'),
 amount NUMERIC(12,2) NOT NULL CHECK(amount>=0),
 created_by TEXT NOT NULL CHECK(length(btrim(created_by))>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,package_key,currency,effective_from)
);
ALTER TABLE packing_package_rates ENABLE ROW LEVEL SECURITY;
ALTER TABLE packing_package_rates FORCE ROW LEVEL SECURITY;
CREATE POLICY packing_package_rate_isolation ON packing_package_rates USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE INDEX packing_package_rates_changed_idx ON packing_package_rates(tenant_id,created_at);
