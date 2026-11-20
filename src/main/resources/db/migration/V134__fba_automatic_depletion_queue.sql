-- Durable automatic attempts use the existing background queue; never enqueue legacy stock.
ALTER TABLE fba_warehouse_depletion_settings ADD COLUMN revision bigint NOT NULL DEFAULT 1;
ALTER TABLE marketplace_sync_jobs ADD CONSTRAINT fba_queue_tenant_id_unique UNIQUE(tenant_id,id);
CREATE UNIQUE INDEX fba_depletion_one_active_job ON marketplace_sync_jobs(tenant_id,marketplace_connection_id)
 WHERE job_type='FBA_WAREHOUSE_DEPLETION' AND status IN ('QUEUED','WAITING','RUNNING');
CREATE TABLE fba_shipment_depletion_attempts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL,
 shipment_id varchar(80) NOT NULL, warehouse_id uuid NOT NULL, job_id uuid NOT NULL,
 import_job_id uuid NOT NULL, source_revision text, allocations jsonb NOT NULL DEFAULT '[]'::jsonb,
 setting_revision bigint NOT NULL, enabled_after timestamptz NOT NULL, actor_id uuid NOT NULL REFERENCES app_users(id),
 status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','COMPLETED','NEEDS_REVIEW')),
 reason varchar(500), depletion_id uuid, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
 UNIQUE(tenant_id,marketplace_connection_id,shipment_id), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,shipment_id) REFERENCES amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id),
 FOREIGN KEY(tenant_id,import_job_id) REFERENCES marketplace_sync_jobs(tenant_id,id),
 FOREIGN KEY(tenant_id,job_id) REFERENCES marketplace_sync_jobs(tenant_id,id),
 FOREIGN KEY(tenant_id,depletion_id) REFERENCES fba_shipment_depletions(tenant_id,id)
);
CREATE INDEX fba_depletion_attempt_job ON fba_shipment_depletion_attempts(tenant_id,job_id,status,created_at);
ALTER TABLE fba_shipment_depletion_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE fba_shipment_depletion_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fba_shipment_depletion_attempts
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE FUNCTION protect_fba_depletion_attempt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'QUEUED' THEN RAISE EXCEPTION 'Completed FBA attempt is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fba_attempt_immutable BEFORE UPDATE OR DELETE ON fba_shipment_depletion_attempts FOR EACH ROW EXECUTE FUNCTION protect_fba_depletion_attempt();
