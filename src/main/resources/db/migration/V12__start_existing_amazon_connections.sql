DO $$
DECLARE
    tenant_record RECORD;
    connection_record RECORD;
    new_run_id UUID;
    job_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenants WHERE status='ACTIVE'
    LOOP
      PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
      FOR connection_record IN
        SELECT c.tenant_id, c.id FROM marketplace_connections c
        WHERE c.tenant_id=tenant_record.id AND c.channel='AMAZON'
          AND c.marketplace_identifier='ATVPDKIKX0DER' AND c.status='ACTIVE'
          AND EXISTS (SELECT 1 FROM marketplace_connection_credentials credentials
                      WHERE credentials.tenant_id=c.tenant_id AND credentials.marketplace_connection_id=c.id)
      LOOP
        IF NOT EXISTS (SELECT 1 FROM marketplace_sync_runs r
                       WHERE r.tenant_id=connection_record.tenant_id
                         AND r.marketplace_connection_id=connection_record.id
                         AND r.run_type='INITIAL_30_DAY') THEN
            INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,window_start,window_end,current_stage,user_message)
            VALUES(connection_record.tenant_id,connection_record.id,'INITIAL_30_DAY',now()-interval '30 days',now(),
                   'VERIFY_SELLER','Verifying your Amazon store') RETURNING id INTO new_run_id;
            FOR job_record IN SELECT * FROM (VALUES
                (1,'VERIFY_SELLER',true),(2,'LISTINGS_SNAPSHOT',true),(3,'ORDERS_30_DAY',true),
                (4,'ORDER_ITEMS_30_DAY',true),(5,'INVENTORY_SNAPSHOT',true),(6,'INVENTORY_LEDGER_30_DAY',true),
                (7,'FBA_CUSTOMER_SHIPMENTS_30_DAY',false),(8,'RETURNS_30_DAY',false),
                (9,'FINANCES_30_DAY',false),(10,'REIMBURSEMENTS_30_DAY',false),
                (11,'FEES_SNAPSHOT',false),(12,'FINAL_RECONCILIATION',true)
            ) AS jobs(sequence_number,job_type,required_for_ready)
            LOOP
                INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,required_for_ready)
                VALUES(connection_record.tenant_id,new_run_id,connection_record.id,job_record.job_type,
                       job_record.sequence_number,job_record.required_for_ready);
            END LOOP;
            UPDATE marketplace_connections SET status='INITIALIZING',last_synced_at=NULL
            WHERE tenant_id=connection_record.tenant_id AND id=connection_record.id;
        END IF;
      END LOOP;
    END LOOP;
END $$;
