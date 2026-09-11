CREATE TABLE catalog_import_progress (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    catalog_import_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    processed_rows INTEGER NOT NULL DEFAULT 0,
    total_rows INTEGER NOT NULL,
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,catalog_import_id),
    FOREIGN KEY (tenant_id,catalog_import_id) REFERENCES catalog_imports(tenant_id,id) ON DELETE CASCADE
);
ALTER TABLE catalog_import_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_import_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY catalog_import_progress_isolation ON catalog_import_progress
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
