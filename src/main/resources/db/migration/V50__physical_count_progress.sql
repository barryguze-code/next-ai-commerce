CREATE TABLE physical_count_import_progress (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    physical_count_import_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    phase VARCHAR(120) NOT NULL DEFAULT 'Waiting to start',
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,physical_count_import_id),
    FOREIGN KEY (tenant_id,physical_count_import_id) REFERENCES physical_count_imports(tenant_id,id) ON DELETE CASCADE
);
ALTER TABLE physical_count_import_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE physical_count_import_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY physical_count_import_progress_isolation ON physical_count_import_progress
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
