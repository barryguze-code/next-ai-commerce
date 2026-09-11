-- A delayed optional report may finish after final reconciliation. Such a late
-- result must enrich the completed run without reopening its onboarding UI.
UPDATE marketplace_sync_runs run
SET status = 'COMPLETED',
    progress_percent = 100,
    current_stage = 'FINAL_RECONCILIATION',
    user_message = 'Amazon data is ready',
    completed_at = COALESCE(run.completed_at, final_job.completed_at, now())
FROM marketplace_sync_jobs final_job
WHERE final_job.sync_run_id = run.id
  AND final_job.tenant_id = run.tenant_id
  AND final_job.job_type = 'FINAL_RECONCILIATION'
  AND final_job.status = 'COMPLETED'
  AND run.status <> 'COMPLETED';
