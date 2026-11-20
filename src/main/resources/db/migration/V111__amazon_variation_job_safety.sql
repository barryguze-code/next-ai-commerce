-- A2 stays disabled. No jobs, observations, backfill or inventory writes.
-- A connection has one configured marketplace (enforced by the A1 scope FK).
CREATE UNIQUE INDEX marketplace_one_active_variation_job_idx
    ON marketplace_sync_jobs(tenant_id,marketplace_connection_id)
    WHERE job_type='VARIATION_RELATIONSHIPS' AND status IN ('QUEUED','WAITING','RUNNING');
-- Non-sensitive application/operation pacing, shared across tenants and JVMs.
-- Sequence reservation is deliberately nontransactional: rollback wastes capacity,
-- never grants extra capacity. No relationship data is stored in these counters.
CREATE SEQUENCE amazon_variation_next_request_ms MINVALUE 1 START WITH 1 CACHE 1;
CREATE SEQUENCE amazon_variation_request_interval_ms MINVALUE 1000 START WITH 1000 CACHE 1;
