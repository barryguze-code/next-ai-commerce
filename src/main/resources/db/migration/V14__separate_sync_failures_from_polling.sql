ALTER TABLE marketplace_sync_jobs
    ADD COLUMN failure_count SMALLINT NOT NULL DEFAULT 0;
