-- Operational FBA metadata is separate from immutable Amazon observations.
CREATE TABLE fba_shipment_user_preferences (
 tenant_id uuid NOT NULL, user_id uuid NOT NULL REFERENCES app_users(id), marketplace_connection_id uuid NOT NULL,
 shipment_id varchar(80) NOT NULL, removed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,user_id,marketplace_connection_id,shipment_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,shipment_id) REFERENCES amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id)
);
CREATE TABLE fba_warehouse_depletion_settings (
 tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL, warehouse_id uuid NOT NULL,
 automatic_enabled boolean NOT NULL DEFAULT false, enabled_after timestamptz,
 updated_by uuid NOT NULL REFERENCES app_users(id), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id,warehouse_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id),
 CHECK(NOT automatic_enabled OR enabled_after IS NOT NULL)
);
CREATE TABLE fba_shipment_depletion_overrides (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), revision bigint GENERATED ALWAYS AS IDENTITY,
 tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL, shipment_id varchar(80) NOT NULL,
 seller_sku varchar(240) NOT NULL, source_quantity integer NOT NULL CHECK(source_quantity>=0),
 depletion_quantity integer NOT NULL CHECK(depletion_quantity>=0), source_revision text NOT NULL,
 reason varchar(1000) NOT NULL CHECK(length(btrim(reason))>0), created_by uuid NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,shipment_id,seller_sku)
 REFERENCES amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku)
);
CREATE INDEX fba_overrides_latest_idx ON fba_shipment_depletion_overrides(tenant_id,marketplace_connection_id,shipment_id,seller_sku,revision DESC);
CREATE TABLE fba_shipment_depletions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL,
 shipment_id varchar(80) NOT NULL, warehouse_id uuid NOT NULL, request_id uuid NOT NULL,
 source_revision text NOT NULL, approval jsonb NOT NULL, created_by uuid NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,marketplace_connection_id,shipment_id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,shipment_id) REFERENCES amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id)
);
CREATE TABLE fba_shipment_depletion_allocations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, depletion_id uuid NOT NULL,
 account_catalog_item_id uuid NOT NULL, location_id uuid NOT NULL, expiration_date date,
 quantity numeric(18,4) NOT NULL CHECK(quantity>0 AND quantity=trunc(quantity)),
 ledger_entry_id uuid NOT NULL,
 receipt_layer_id uuid NOT NULL,unit_cost numeric(19,4),currency char(3),
 UNIQUE(tenant_id,ledger_entry_id), UNIQUE(tenant_id,depletion_id,receipt_layer_id),
 FOREIGN KEY(tenant_id,ledger_entry_id) REFERENCES inventory_ledger_entries(tenant_id,id),
 FOREIGN KEY(tenant_id,receipt_layer_id) REFERENCES inventory_ledger_entries(tenant_id,id),
 FOREIGN KEY(tenant_id,depletion_id) REFERENCES fba_shipment_depletions(tenant_id,id),
 FOREIGN KEY(tenant_id,account_catalog_item_id) REFERENCES account_catalog_items(tenant_id,id),
 FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_locations(tenant_id,id)
);
CREATE INDEX fba_depletion_allocations_operation_idx ON fba_shipment_depletion_allocations(tenant_id,depletion_id);
CREATE INDEX fba_warehouse_postal_idx ON warehouses(tenant_id,upper(country_code),upper(replace(btrim(postal_code),' ',''))) WHERE status='ACTIVE';
DO $$ DECLARE table_name text; BEGIN
 FOREACH table_name IN ARRAY ARRAY['fba_shipment_user_preferences','fba_warehouse_depletion_settings','fba_shipment_depletion_overrides','fba_shipment_depletions','fba_shipment_depletion_allocations'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
 EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
 END LOOP;
END $$;

CREATE FUNCTION protect_fba_depletion_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'FBA depletion audit is append-only'; END $$;
CREATE TRIGGER fba_overrides_immutable BEFORE UPDATE OR DELETE ON fba_shipment_depletion_overrides FOR EACH ROW EXECUTE FUNCTION protect_fba_depletion_audit();
CREATE TRIGGER fba_depletions_immutable BEFORE UPDATE OR DELETE ON fba_shipment_depletions FOR EACH ROW EXECUTE FUNCTION protect_fba_depletion_audit();
CREATE TRIGGER fba_allocations_immutable BEFORE UPDATE OR DELETE ON fba_shipment_depletion_allocations FOR EACH ROW EXECUTE FUNCTION protect_fba_depletion_audit();
