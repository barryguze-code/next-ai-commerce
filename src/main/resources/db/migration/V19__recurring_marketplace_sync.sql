ALTER TABLE marketplace_sync_runs
    ADD COLUMN sync_profile VARCHAR(80) NOT NULL DEFAULT 'INITIAL_30_DAY';

CREATE INDEX marketplace_sync_runs_profile_idx
    ON marketplace_sync_runs(tenant_id,marketplace_connection_id,sync_profile,created_at DESC);

CREATE TABLE marketplace_sync_schedules (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    schedule_key VARCHAR(80) NOT NULL,
    run_type VARCHAR(40) NOT NULL CHECK (run_type IN ('INCREMENTAL','RECONCILIATION')),
    cadence_seconds BIGINT NOT NULL CHECK (cadence_seconds>=300),
    lookback_seconds BIGINT NOT NULL CHECK (lookback_seconds>=0),
    priority SMALLINT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT true,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_enqueued_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_run_id UUID REFERENCES marketplace_sync_runs(id) ON DELETE SET NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,marketplace_connection_id,schedule_key),
    FOREIGN KEY(tenant_id,marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

CREATE INDEX marketplace_sync_schedules_due_idx
    ON marketplace_sync_schedules(next_run_at,priority) WHERE enabled=true;

ALTER TABLE marketplace_sync_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sync_schedules FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_sync_schedules_isolation ON marketplace_sync_schedules
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE TRIGGER marketplace_sync_schedules_set_updated_at
    BEFORE UPDATE ON marketplace_sync_schedules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
