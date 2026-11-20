-- Local FBA planning uses the shared reservation ledger; source observations stay immutable.
CREATE TABLE fba_replenishment_plans (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), marketplace_connection_id uuid NOT NULL,
 name varchar(160) NOT NULL, status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ALLOCATED','PART_DISPATCHED','DISPATCHED','CANCELLED')),
 revision bigint NOT NULL DEFAULT 1, step integer NOT NULL DEFAULT 0 CHECK(step BETWEEN 0 AND 5),
 checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb, created_by uuid REFERENCES app_users(id),updated_by uuid REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id)
);
CREATE UNIQUE INDEX fba_replenishment_one_editable_draft ON fba_replenishment_plans(tenant_id,marketplace_connection_id) WHERE status='DRAFT';
CREATE INDEX fba_replenishment_plans_store ON fba_replenishment_plans(tenant_id,marketplace_connection_id,status,updated_at DESC);
CREATE TABLE fba_replenishment_actions (
 tenant_id uuid NOT NULL,plan_id uuid NOT NULL,request_id uuid NOT NULL,kind varchar(32) NOT NULL,request_hash varchar(64) NOT NULL,
 result jsonb NOT NULL DEFAULT '{}'::jsonb,created_at timestamptz NOT NULL DEFAULT now(),actor uuid REFERENCES app_users(id),
 PRIMARY KEY(tenant_id,request_id),FOREIGN KEY(tenant_id,plan_id) REFERENCES fba_replenishment_plans(tenant_id,id)
);
CREATE TABLE fba_replenishment_allocations (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL,plan_id uuid NOT NULL,marketplace_connection_id uuid NOT NULL,
 warehouse_id uuid NOT NULL,seller_sku varchar(240) NOT NULL,cost_layer_id uuid NOT NULL,
 each_per_sku integer NOT NULL CHECK(each_per_sku>0),sku_units integer NOT NULL CHECK(sku_units>0),
 quantity integer NOT NULL CHECK(quantity>0),dispatched integer NOT NULL DEFAULT 0 CHECK(dispatched>=0 AND dispatched<=quantity),
 reservation_id uuid NOT NULL UNIQUE,amazon_shipment_id varchar(100),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,marketplace_connection_id,plan_id) REFERENCES fba_replenishment_plans(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id),
 FOREIGN KEY(tenant_id,cost_layer_id) REFERENCES inventory_ledger_entries(tenant_id,id)
);
ALTER TABLE order_inventory_reservations ADD COLUMN fba_plan_id uuid;
ALTER TABLE order_inventory_reservations ADD CONSTRAINT reservation_fba_plan_scope FOREIGN KEY(tenant_id,marketplace_connection_id,fba_plan_id) REFERENCES fba_replenishment_plans(tenant_id,marketplace_connection_id,id);
ALTER TABLE order_inventory_reservations DROP CONSTRAINT order_reservation_provider_identity;
ALTER TABLE order_inventory_reservations ADD CONSTRAINT order_reservation_provider_identity CHECK(
 (fba_plan_id IS NULL AND shopify_order_line_id IS NULL AND amazon_order_id IS NOT NULL AND amazon_order_item_id IS NOT NULL) OR
 (fba_plan_id IS NULL AND shopify_order_line_id IS NOT NULL AND amazon_order_id IS NULL AND amazon_order_item_id IS NULL) OR
 (fba_plan_id IS NOT NULL AND shopify_order_line_id IS NULL AND amazon_order_id IS NULL AND amazon_order_item_id IS NULL AND cost_layer_id IS NOT NULL));
CREATE INDEX reservation_fba_plan ON order_inventory_reservations(tenant_id,fba_plan_id) WHERE fba_plan_id IS NOT NULL;
ALTER TABLE order_inventory_reservations ADD CONSTRAINT reservation_tenant_identity UNIQUE(tenant_id,id);
ALTER TABLE order_inventory_reservations ADD CONSTRAINT reservation_fba_identity UNIQUE(tenant_id,marketplace_connection_id,fba_plan_id,id);
ALTER TABLE fba_replenishment_allocations ADD FOREIGN KEY(tenant_id,marketplace_connection_id,plan_id,reservation_id) REFERENCES order_inventory_reservations(tenant_id,marketplace_connection_id,fba_plan_id,id);
-- Keep existing Amazon/Shopify identity indexes and upsert contracts unchanged.
CREATE FUNCTION validate_fba_reservation_layer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.fba_plan_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM inventory_ledger_entries layer WHERE layer.tenant_id=NEW.tenant_id AND layer.id=NEW.cost_layer_id
   AND layer.quantity>0 AND layer.account_catalog_item_id=NEW.account_catalog_item_id
   AND layer.location_id=NEW.location_id AND layer.expiration_date IS NOT DISTINCT FROM NEW.expiration_date
 ) THEN RAISE EXCEPTION 'FBA reservation requires its exact scoped positive inventory layer'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fba_reservation_layer BEFORE INSERT OR UPDATE ON order_inventory_reservations
 FOR EACH ROW EXECUTE FUNCTION validate_fba_reservation_layer();
CREATE FUNCTION validate_fba_allocation_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(
  SELECT 1 FROM order_inventory_reservations reservation
   JOIN warehouse_locations location ON location.tenant_id=reservation.tenant_id AND location.id=reservation.location_id
  WHERE reservation.tenant_id=NEW.tenant_id AND reservation.marketplace_connection_id=NEW.marketplace_connection_id
   AND reservation.fba_plan_id=NEW.plan_id AND reservation.id=NEW.reservation_id
   AND reservation.cost_layer_id=NEW.cost_layer_id AND location.warehouse_id=NEW.warehouse_id
 ) THEN RAISE EXCEPTION 'FBA allocation must retain its own scoped reservation, origin and cost layer'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fba_allocation_source BEFORE INSERT OR UPDATE ON fba_replenishment_allocations
 FOR EACH ROW EXECUTE FUNCTION validate_fba_allocation_source();
CREATE TABLE fba_replenishment_policies (
 tenant_id uuid NOT NULL REFERENCES tenants(id),scope_key varchar(500) NOT NULL,configuration jsonb NOT NULL,revision bigint NOT NULL DEFAULT 1,
 updated_at timestamptz NOT NULL DEFAULT now(),updated_by uuid REFERENCES app_users(id),PRIMARY KEY(tenant_id,scope_key)
);
CREATE TABLE fba_replenishment_events (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES tenants(id),name varchar(160) NOT NULL,
 start_date date NOT NULL,end_date date NOT NULL,configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
 revision bigint NOT NULL DEFAULT 1,updated_at timestamptz NOT NULL DEFAULT now(),updated_by uuid REFERENCES app_users(id),
 CHECK(end_date>=start_date),UNIQUE(tenant_id,id)
);
CREATE INDEX fba_replenishment_events_horizon ON fba_replenishment_events(tenant_id,start_date,end_date);
CREATE TABLE fba_replenishment_forecasts (
 tenant_id uuid NOT NULL,marketplace_connection_id uuid NOT NULL,payload jsonb NOT NULL,generated_at timestamptz NOT NULL DEFAULT now(),invalidated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id),FOREIGN KEY(tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id)
);
DO $$ DECLARE tab text;BEGIN
 FOREACH tab IN ARRAY ARRAY['fba_replenishment_plans','fba_replenishment_actions','fba_replenishment_allocations','fba_replenishment_policies','fba_replenishment_events','fba_replenishment_forecasts'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
 EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
END $$;

-- Transition tables invalidate once per changed account per SQL statement, rather than per item.
-- Keep the actual change time even when already dirty so a concurrent summary build cannot lose an invalidation.
CREATE FUNCTION invalidate_fba_replenishment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  UPDATE fba_replenishment_forecasts SET invalidated_at=clock_timestamp() WHERE tenant_id IN(SELECT DISTINCT tenant_id FROM old_rows);
 ELSIF TG_OP='INSERT' THEN
  UPDATE fba_replenishment_forecasts SET invalidated_at=clock_timestamp() WHERE tenant_id IN(SELECT DISTINCT tenant_id FROM new_rows);
 ELSE
  UPDATE fba_replenishment_forecasts SET invalidated_at=clock_timestamp() WHERE tenant_id IN(SELECT tenant_id FROM new_rows UNION SELECT tenant_id FROM old_rows);
 END IF;
 RETURN NULL;
END $$;
DO $$ DECLARE tab text;BEGIN
 FOREACH tab IN ARRAY ARRAY['inventory_ledger_entries','order_inventory_reservations','marketplace_sku_mappings','marketplace_sku_mapping_components','amazon_orders','amazon_order_items','shopify_orders','shopify_order_lines','amazon_inventory_snapshots','account_catalog_items','fba_replenishment_policies','fba_replenishment_events'] LOOP
 EXECUTE format('CREATE TRIGGER invalidate_fba_forecast_insert AFTER INSERT ON %I REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION invalidate_fba_replenishment()',tab);
 EXECUTE format('CREATE TRIGGER invalidate_fba_forecast_update AFTER UPDATE ON %I REFERENCING NEW TABLE AS new_rows OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION invalidate_fba_replenishment()',tab);
 EXECUTE format('CREATE TRIGGER invalidate_fba_forecast_delete AFTER DELETE ON %I REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION invalidate_fba_replenishment()',tab);
 END LOOP;END $$;
