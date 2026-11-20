-- Warehouses group existing storage positions; no stock or reservation rows change.
CREATE TABLE warehouses (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 name VARCHAR(160) NOT NULL CHECK (length(btrim(name))>0),
 is_default BOOLEAN NOT NULL DEFAULT false,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id)
);
CREATE UNIQUE INDEX warehouses_name_uk ON warehouses(tenant_id,lower(name));
CREATE UNIQUE INDEX warehouses_default_uk ON warehouses(tenant_id) WHERE is_default;
CREATE TRIGGER warehouses_updated BEFORE UPDATE ON warehouses FOR EACH ROW EXECUTE FUNCTION set_updated_at();
INSERT INTO warehouses(tenant_id,name,is_default) SELECT id,'Default Warehouse',true FROM tenants;
ALTER TABLE warehouse_locations ADD COLUMN warehouse_id UUID;
-- Respect existing forced location RLS while attaching each account's locations.
DO $$ DECLARE account UUID; BEGIN
 FOR account IN SELECT id FROM tenants LOOP
  PERFORM set_config('app.tenant_id',account::text,true);
  UPDATE warehouse_locations l SET warehouse_id=w.id FROM warehouses w
   WHERE l.tenant_id=account AND w.tenant_id=l.tenant_id AND w.is_default;
 END LOOP;
 PERFORM set_config('app.tenant_id','',true);
END $$;
ALTER TABLE warehouse_locations ALTER COLUMN warehouse_id SET NOT NULL;
ALTER TABLE warehouse_locations ADD CONSTRAINT warehouse_locations_parent_fk
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id);
CREATE INDEX warehouse_locations_parent_idx ON warehouse_locations(tenant_id,warehouse_id,status);
-- Retain account-wide location codes for compatibility with imports and MAIN fallback.
ALTER TABLE warehouses ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouses FORCE ROW LEVEL SECURITY;
CREATE POLICY warehouses_isolation ON warehouses USING(tenant_id=current_setting('app.tenant_id',true)::uuid)
 WITH CHECK(tenant_id=current_setting('app.tenant_id',true)::uuid);
CREATE OR REPLACE FUNCTION fill_location_warehouse() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.warehouse_id IS NULL THEN
  SELECT id INTO NEW.warehouse_id FROM warehouses WHERE tenant_id=NEW.tenant_id AND is_default;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_locations_parent BEFORE INSERT ON warehouse_locations
 FOR EACH ROW EXECUTE FUNCTION fill_location_warehouse();
-- New accounts receive their parent immediately, before any catalogue/location creation.
CREATE OR REPLACE FUNCTION initialize_account_warehouse() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE previous_scope text;
BEGIN
 previous_scope:=current_setting('app.tenant_id',true);
 PERFORM set_config('app.tenant_id',NEW.id::text,true);
 INSERT INTO warehouses(tenant_id,name,is_default) VALUES(NEW.id,'Default Warehouse',true);
 PERFORM set_config('app.tenant_id',coalesce(previous_scope,''),true);
 RETURN NEW;
END $$;
CREATE TRIGGER tenants_initialize_warehouse AFTER INSERT ON tenants
 FOR EACH ROW EXECUTE FUNCTION initialize_account_warehouse();
