ALTER TABLE marketplace_connections DROP CONSTRAINT marketplace_connections_status_check;
ALTER TABLE marketplace_connections ADD CONSTRAINT marketplace_connections_status_check
    CHECK (status IN ('PENDING', 'INITIALIZING', 'ACTIVE', 'ERROR', 'DISABLED'));

CREATE TABLE marketplace_sync_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    run_type VARCHAR(40) NOT NULL CHECK (run_type IN ('INITIAL_30_DAY', 'INCREMENTAL', 'RECONCILIATION')),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'CANCELLED')),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    current_stage VARCHAR(60),
    progress_percent SMALLINT NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    user_message VARCHAR(240) NOT NULL DEFAULT 'Preparing your Amazon store',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX marketplace_sync_one_initial_run_idx
    ON marketplace_sync_runs (tenant_id, marketplace_connection_id)
    WHERE run_type = 'INITIAL_30_DAY' AND status IN ('QUEUED', 'RUNNING');
CREATE INDEX marketplace_sync_runs_connection_idx
    ON marketplace_sync_runs (tenant_id, marketplace_connection_id, created_at DESC);

CREATE TABLE marketplace_sync_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    sync_run_id UUID NOT NULL REFERENCES marketplace_sync_runs(id) ON DELETE CASCADE,
    marketplace_connection_id UUID NOT NULL,
    job_type VARCHAR(60) NOT NULL,
    sequence_number SMALLINT NOT NULL,
    required_for_ready BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'SKIPPED')),
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    max_attempts SMALLINT NOT NULL DEFAULT 8,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner VARCHAR(120),
    lease_expires_at TIMESTAMPTZ,
    amazon_request_id VARCHAR(160),
    cursor JSONB NOT NULL DEFAULT '{}'::jsonb,
    records_processed BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sync_run_id, job_type),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX marketplace_sync_jobs_claim_idx
    ON marketplace_sync_jobs (status, available_at, sequence_number)
    WHERE status IN ('QUEUED', 'WAITING');

ALTER TABLE marketplace_sync_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sync_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sync_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sync_jobs FORCE ROW LEVEL SECURITY;

CREATE POLICY marketplace_sync_runs_isolation ON marketplace_sync_runs
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY marketplace_sync_jobs_isolation ON marketplace_sync_jobs
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TRIGGER marketplace_sync_runs_set_updated_at BEFORE UPDATE ON marketplace_sync_runs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER marketplace_sync_jobs_set_updated_at BEFORE UPDATE ON marketplace_sync_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
