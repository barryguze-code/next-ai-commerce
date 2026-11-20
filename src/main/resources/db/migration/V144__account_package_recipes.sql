-- User-authored planning matches. No fulfillment, inventory, or provider side effects.
CREATE TABLE account_package_recipes (
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 composition_key text NOT NULL CHECK(length(composition_key)=64),
 composition jsonb NOT NULL CHECK(jsonb_typeof(composition)='object'),
 packages jsonb NOT NULL CHECK(jsonb_typeof(packages)='array' AND jsonb_array_length(packages) BETWEEN 1 AND 20),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 updated_by text NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,composition_key)
);
ALTER TABLE account_package_recipes ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_package_recipes FORCE ROW LEVEL SECURITY;
CREATE POLICY account_package_recipe_tenant ON account_package_recipes
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
