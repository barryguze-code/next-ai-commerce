-- Append-only compensation: original shipment observations and deductions are immutable.
CREATE TABLE fba_deduction_adjustments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), revision bigint GENERATED ALWAYS AS IDENTITY, tenant_id uuid NOT NULL,
 depletion_id uuid NOT NULL, request_id uuid NOT NULL, previous_revision text NOT NULL,
 quantities jsonb NOT NULL CHECK(jsonb_typeof(quantities)='object'),
 reason varchar(1000) NOT NULL CHECK(length(btrim(reason))>0),
 created_by uuid NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,depletion_id) REFERENCES fba_shipment_depletions(tenant_id,id)
);
CREATE INDEX fba_adjustment_history_idx ON fba_deduction_adjustments(tenant_id,depletion_id,revision);
CREATE TABLE fba_deduction_adjustment_entries (
 tenant_id uuid NOT NULL, adjustment_id uuid NOT NULL, ledger_entry_id uuid NOT NULL,
 debit_entry_id uuid NOT NULL, receipt_layer_id uuid NOT NULL, allocation_order integer NOT NULL CHECK(allocation_order>0),
 quantity numeric NOT NULL CHECK(quantity<>0 AND quantity=trunc(quantity)),
 PRIMARY KEY(tenant_id,ledger_entry_id), UNIQUE(tenant_id,adjustment_id,allocation_order),
 FOREIGN KEY(tenant_id,adjustment_id) REFERENCES fba_deduction_adjustments(tenant_id,id),
 FOREIGN KEY(tenant_id,ledger_entry_id) REFERENCES inventory_ledger_entries(tenant_id,id),
 FOREIGN KEY(tenant_id,debit_entry_id) REFERENCES inventory_ledger_entries(tenant_id,id),
 FOREIGN KEY(tenant_id,receipt_layer_id) REFERENCES inventory_ledger_entries(tenant_id,id)
);
CREATE INDEX fba_adjustment_debit_idx ON fba_deduction_adjustment_entries(tenant_id,debit_entry_id);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['fba_deduction_adjustments','fba_deduction_adjustment_entries'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
 EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
 EXECUTE format('CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_fba_depletion_audit()',t);
 END LOOP;
END $$;
