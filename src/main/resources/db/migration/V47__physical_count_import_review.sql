CREATE TABLE physical_count_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    vendor_id UUID,
    original_filename VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'STAGED'
        CHECK (status IN ('STAGED','APPLIED','FAILED','CANCELLED')),
    headers JSONB NOT NULL DEFAULT '[]'::jsonb,
    column_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    total_rows INTEGER NOT NULL DEFAULT 0,
    applied_rows INTEGER NOT NULL DEFAULT 0,
    uploaded_by UUID NOT NULL REFERENCES app_users(id),
    applied_by UUID REFERENCES app_users(id),
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,vendor_id) REFERENCES vendors(tenant_id,id)
);

CREATE TABLE physical_count_import_rows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    physical_count_import_id UUID NOT NULL,
    row_number INTEGER NOT NULL,
    source_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,physical_count_import_id)
        REFERENCES physical_count_imports(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id,physical_count_import_id,row_number)
);

CREATE INDEX physical_count_imports_tenant_idx
    ON physical_count_imports(tenant_id,created_at DESC);
CREATE INDEX physical_count_import_rows_import_idx
    ON physical_count_import_rows(tenant_id,physical_count_import_id,row_number);

CREATE TRIGGER physical_count_imports_set_updated_at BEFORE UPDATE ON physical_count_imports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE physical_count_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE physical_count_imports FORCE ROW LEVEL SECURITY;
CREATE POLICY physical_count_imports_isolation ON physical_count_imports
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE physical_count_import_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE physical_count_import_rows FORCE ROW LEVEL SECURITY;
CREATE POLICY physical_count_import_rows_isolation ON physical_count_import_rows
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
