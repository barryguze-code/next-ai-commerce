-- Planning only: no receiving, stock, reservations or vendor submission hooks.
CREATE TABLE replenishment_purchase_plans (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id),
 name varchar(160) NOT NULL CHECK(length(btrim(name))>0),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','SUBMITTED')),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), request_hash varchar(64) NOT NULL,
 created_by uuid NOT NULL REFERENCES app_users(id), updated_by uuid NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), submitted_at timestamptz,
 UNIQUE(tenant_id,id), CHECK((status='SUBMITTED')=(submitted_at IS NOT NULL))
);
CREATE INDEX replenishment_plans_list_idx ON replenishment_purchase_plans(tenant_id,status,updated_at DESC,id);
CREATE TABLE replenishment_purchase_plan_lines (
 tenant_id uuid NOT NULL, plan_id uuid NOT NULL, item_id uuid NOT NULL, vendor_id uuid,
 name text NOT NULL, item_code text NOT NULL, barcode text NOT NULL, vendor_name text NOT NULL, dc text NOT NULL,
 units_per_case integer NOT NULL CHECK(units_per_case>0), quantity bigint NOT NULL CHECK(quantity BETWEEN 1 AND 100000000),
 unit_cost numeric(19,4) CHECK(unit_cost>=0), currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 PRIMARY KEY(tenant_id,plan_id,item_id),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES replenishment_purchase_plans(tenant_id,id),
 FOREIGN KEY(tenant_id,item_id) REFERENCES account_catalog_items(tenant_id,id),
 FOREIGN KEY(tenant_id,vendor_id) REFERENCES vendors(tenant_id,id)
);
CREATE TABLE replenishment_vendor_pos (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, plan_id uuid NOT NULL, vendor_id uuid NOT NULL,
 po_number varchar(48) NOT NULL, currency char(3) NOT NULL, total numeric(19,2) NOT NULL CHECK(total>=0),
 created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL REFERENCES app_users(id),
 UNIQUE(tenant_id,po_number), UNIQUE(tenant_id,plan_id,vendor_id),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES replenishment_purchase_plans(tenant_id,id),
 FOREIGN KEY(tenant_id,vendor_id) REFERENCES vendors(tenant_id,id)
);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['replenishment_purchase_plans','replenishment_purchase_plan_lines','replenishment_vendor_pos'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
 EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
 END LOOP;
END $$;
CREATE FUNCTION protect_submitted_purchase_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text;
BEGIN
 IF TG_TABLE_NAME='replenishment_purchase_plans' THEN
  IF OLD.status='SUBMITTED' THEN RAISE EXCEPTION 'Submitted purchase plans are immutable'; END IF;
  IF TG_OP='UPDATE' AND (NEW.created_at<>OLD.created_at OR NEW.created_by<>OLD.created_by OR NEW.tenant_id<>OLD.tenant_id OR NEW.id<>OLD.id) THEN RAISE EXCEPTION 'Purchase plan identity is immutable'; END IF;
 ELSE
  IF TG_OP IN ('UPDATE','DELETE') THEN
   SELECT status INTO parent_status FROM replenishment_purchase_plans WHERE tenant_id=OLD.tenant_id AND id=OLD.plan_id FOR SHARE;
   IF parent_status='SUBMITTED' THEN RAISE EXCEPTION 'Submitted purchase plan lines are immutable'; END IF;
  END IF;
  IF TG_OP IN ('INSERT','UPDATE') THEN
   SELECT status INTO parent_status FROM replenishment_purchase_plans WHERE tenant_id=NEW.tenant_id AND id=NEW.plan_id FOR SHARE;
   IF parent_status='SUBMITTED' THEN RAISE EXCEPTION 'Submitted purchase plan lines are immutable'; END IF;
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER purchase_plan_protection BEFORE UPDATE OR DELETE ON replenishment_purchase_plans FOR EACH ROW EXECUTE FUNCTION protect_submitted_purchase_plan();
CREATE TRIGGER purchase_plan_line_protection BEFORE INSERT OR UPDATE OR DELETE ON replenishment_purchase_plan_lines FOR EACH ROW EXECUTE FUNCTION protect_submitted_purchase_plan();
CREATE FUNCTION protect_vendor_po() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Submitted POs are immutable'; END $$;
CREATE TRIGGER vendor_po_protection BEFORE UPDATE OR DELETE ON replenishment_vendor_pos FOR EACH ROW EXECUTE FUNCTION protect_vendor_po();

CREATE TRIGGER vendor_po_insert_protection BEFORE INSERT ON replenishment_vendor_pos FOR EACH ROW EXECUTE FUNCTION protect_submitted_purchase_plan();
