-- Repair terminal job groups left active by earlier deployments. Never advance
-- a watermark here: only a new successful import can certify data coverage.
DO $$
DECLARE account_id uuid;
BEGIN
FOR account_id IN SELECT id FROM tenants LOOP
PERFORM set_config('app.tenant_id',account_id::text,true);
UPDATE marketplace_sync_runs run
SET status=CASE WHEN EXISTS (
        SELECT 1 FROM marketplace_sync_jobs job
        WHERE job.tenant_id=run.tenant_id AND job.sync_run_id=run.id AND job.status='SKIPPED'
    ) THEN 'COMPLETED_WITH_WARNINGS' ELSE 'COMPLETED' END,
    progress_percent=100,
    completed_at=(SELECT max(job.completed_at) FROM marketplace_sync_jobs job
        WHERE job.tenant_id=run.tenant_id AND job.sync_run_id=run.id),
    user_message='Completed job tracking recovered; scheduled refreshes can continue'
WHERE run.tenant_id=account_id AND run.status IN ('QUEUED','RUNNING')
  AND EXISTS (SELECT 1 FROM marketplace_sync_jobs job
      WHERE job.tenant_id=run.tenant_id AND job.sync_run_id=run.id
        AND job.job_type='FINAL_RECONCILIATION' AND job.status='COMPLETED')
  AND NOT EXISTS (SELECT 1 FROM marketplace_sync_jobs job
      WHERE job.tenant_id=run.tenant_id AND job.sync_run_id=run.id
        AND job.status NOT IN ('COMPLETED','SKIPPED'));
END LOOP;
PERFORM set_config('app.tenant_id','',true);
END $$;
