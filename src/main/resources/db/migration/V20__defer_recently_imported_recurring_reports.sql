-- Avoid immediately requesting daily reports that were just completed during initialization.
UPDATE marketplace_sync_schedules schedule
SET next_run_at=greatest(schedule.next_run_at,
        (SELECT max(job.completed_at) FROM marketplace_sync_jobs job
         WHERE job.tenant_id=schedule.tenant_id
           AND job.marketplace_connection_id=schedule.marketplace_connection_id
           AND job.status='COMPLETED'
           AND job.job_type=CASE schedule.schedule_key
               WHEN 'ORDER_CHANGES' THEN 'ORDERS_30_DAY'
               WHEN 'CURRENT_INVENTORY' THEN 'INVENTORY_SNAPSHOT'
               WHEN 'RECENT_ORDER_RECONCILIATION' THEN 'ORDERS_30_DAY'
               WHEN 'RETURNS' THEN 'RETURNS_30_DAY'
               WHEN 'FINANCES' THEN 'FINANCES_30_DAY'
               WHEN 'ORDER_LIFECYCLE' THEN 'ORDERS_30_DAY'
               WHEN 'LISTINGS' THEN 'LISTINGS_SNAPSHOT'
               WHEN 'INVENTORY_LEDGER' THEN 'INVENTORY_LEDGER_30_DAY'
               WHEN 'REIMBURSEMENTS' THEN 'REIMBURSEMENTS_30_DAY'
               WHEN 'FBA_FEES' THEN 'FEES_SNAPSHOT'
               WHEN 'FBA_CUSTOMER_SHIPMENTS' THEN 'FBA_CUSTOMER_SHIPMENTS_30_DAY'
           END)+make_interval(secs=>schedule.cadence_seconds)),
    updated_at=now()
WHERE EXISTS (SELECT 1 FROM marketplace_sync_jobs job
    WHERE job.tenant_id=schedule.tenant_id
      AND job.marketplace_connection_id=schedule.marketplace_connection_id
      AND job.status='COMPLETED');
