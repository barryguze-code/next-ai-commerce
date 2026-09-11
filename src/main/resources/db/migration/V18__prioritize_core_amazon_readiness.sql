-- Core commerce data unlocks the workspace; historical enrichment continues afterward.
UPDATE marketplace_sync_jobs
SET required_for_ready = job_type IN (
        'VERIFY_SELLER','LISTINGS_SNAPSHOT','ORDERS_30_DAY','ORDER_ITEMS_30_DAY','INVENTORY_SNAPSHOT'
    ),
    sequence_number = CASE job_type
        WHEN 'VERIFY_SELLER' THEN 1
        WHEN 'LISTINGS_SNAPSHOT' THEN 2
        WHEN 'ORDERS_30_DAY' THEN 3
        WHEN 'ORDER_ITEMS_30_DAY' THEN 4
        WHEN 'INVENTORY_SNAPSHOT' THEN 5
        WHEN 'INVENTORY_LEDGER_30_DAY' THEN 6
        WHEN 'RETURNS_30_DAY' THEN 7
        WHEN 'FINANCES_30_DAY' THEN 8
        WHEN 'REIMBURSEMENTS_30_DAY' THEN 9
        WHEN 'FEES_SNAPSHOT' THEN 10
        WHEN 'FBA_CUSTOMER_SHIPMENTS_30_DAY' THEN 11
        WHEN 'FINAL_RECONCILIATION' THEN 12
        ELSE sequence_number
    END
WHERE job_type IN (
    'VERIFY_SELLER','LISTINGS_SNAPSHOT','ORDERS_30_DAY','ORDER_ITEMS_30_DAY','INVENTORY_SNAPSHOT',
    'INVENTORY_LEDGER_30_DAY','RETURNS_30_DAY','FINANCES_30_DAY','REIMBURSEMENTS_30_DAY',
    'FEES_SNAPSHOT','FBA_CUSTOMER_SHIPMENTS_30_DAY','FINAL_RECONCILIATION'
);

UPDATE marketplace_connections connection
SET status='ACTIVE',last_synced_at=coalesce(last_synced_at,now())
WHERE connection.status='INITIALIZING'
  AND EXISTS (SELECT 1 FROM marketplace_sync_runs run
      WHERE run.tenant_id=connection.tenant_id AND run.marketplace_connection_id=connection.id
        AND run.status IN ('QUEUED','RUNNING')
        AND NOT EXISTS (SELECT 1 FROM marketplace_sync_jobs required_job
            WHERE required_job.tenant_id=run.tenant_id AND required_job.sync_run_id=run.id
              AND required_job.required_for_ready=true
              AND required_job.status NOT IN ('COMPLETED','SKIPPED')));
