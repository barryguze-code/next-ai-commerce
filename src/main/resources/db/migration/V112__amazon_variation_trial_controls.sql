-- Inert trial metadata only. No job, API, backfill or inventory activity.
ALTER TABLE marketplace_sync_jobs ADD CONSTRAINT marketplace_sync_jobs_variation_trial_scope_key
    UNIQUE(tenant_id,marketplace_connection_id,id);
CREATE TABLE amazon_variation_trial (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    marketplace_id VARCHAR(30) NOT NULL,
    job_id UUID NOT NULL UNIQUE,
    idempotency_key UUID NOT NULL,
    seller_identifier TEXT NOT NULL CHECK(seller_identifier<>''),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deadline TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    state VARCHAR(80) NOT NULL DEFAULT 'RUNNING',
    get_reserved INTEGER NOT NULL DEFAULT 0 CHECK(get_reserved BETWEEN 0 AND 10),
    oauth_reserved INTEGER NOT NULL DEFAULT 0 CHECK(oauth_reserved BETWEEN 0 AND 2),
    request_id UUID,
    request_owner TEXT,
    request_fence INTEGER,
    request_kind VARCHAR(8),
    CHECK(num_nonnulls(request_id,request_owner,request_fence,request_kind) IN (0,4)),
    CHECK(request_kind IS NULL OR request_kind IN ('GET','OAUTH')),
    CHECK(request_fence IS NULL OR request_fence>0),
    CHECK(state IN ('RUNNING','COMPLETED','CANCELLED','DEADLINE','GET_LIMIT','OAUTH_LIMIT',
        'REQUEST_OUTCOME_UNKNOWN','FAMILY_LIMIT','AUTHORIZATION_REQUIRED','RETRY_EXHAUSTED',
        'RETRY_OUTSIDE_WINDOW','UNRESOLVED_EVIDENCE','JOB_CLAIM_LIMIT')),
    PRIMARY KEY(tenant_id,marketplace_connection_id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,job_id)
        REFERENCES marketplace_sync_jobs(tenant_id,marketplace_connection_id,id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id)
        REFERENCES marketplace_connections(tenant_id,id,marketplace_identifier),
    CHECK(tenant_id='74aa8c8b-089b-497a-91b4-994b2be8e95b' AND
          marketplace_connection_id='9b72c443-cca7-4845-be40-7cc34339e446' AND marketplace_id='ATVPDKIKX0DER'),
    CHECK(deadline>started_at AND deadline<=started_at+interval '5 minutes')
);
ALTER TABLE amazon_variation_trial ENABLE ROW LEVEL SECURITY;
ALTER TABLE amazon_variation_trial FORCE ROW LEVEL SECURITY;
CREATE POLICY amazon_variation_trial_isolation ON amazon_variation_trial
    USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
