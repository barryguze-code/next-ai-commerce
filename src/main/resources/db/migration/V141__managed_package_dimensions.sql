-- Account package metadata only. Existing transaction and printed snapshots stay unchanged.
CREATE TABLE packing_package_dimensions (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 package_key TEXT NOT NULL CHECK(package_key=packing_package_key(package_key) AND length(package_key) BETWEEN 1 AND 120),
 length NUMERIC(12,3) NOT NULL CHECK(length>0),
 width NUMERIC(12,3) NOT NULL CHECK(width>0),
 height NUMERIC(12,3) NOT NULL CHECK(height>0),
 dimension_unit TEXT NOT NULL CHECK(dimension_unit IN ('inches','centimeters')),
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0),
 updated_by TEXT NOT NULL CHECK(length(btrim(updated_by))>0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,package_key)
);
ALTER TABLE packing_package_dimensions ENABLE ROW LEVEL SECURITY;
ALTER TABLE packing_package_dimensions FORCE ROW LEVEL SECURITY;
CREATE POLICY packing_package_dimensions_isolation ON packing_package_dimensions
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
