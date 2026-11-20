ALTER TABLE warehouses
 ADD COLUMN address_line_1 VARCHAR(240), ADD COLUMN address_line_2 VARCHAR(240),
 ADD COLUMN city VARCHAR(120), ADD COLUMN state_or_province VARCHAR(80),
 ADD COLUMN postal_code VARCHAR(32), ADD COLUMN country_code VARCHAR(2);
CREATE TABLE warehouse_contacts (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id UUID NOT NULL,warehouse_id UUID NOT NULL,
 full_name VARCHAR(180) NOT NULL CHECK(length(btrim(full_name))>0),
 phone VARCHAR(40) NOT NULL CHECK(length(btrim(phone))>0),
 sort_order INTEGER NOT NULL CHECK(sort_order>=0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id),
 UNIQUE(tenant_id,warehouse_id,sort_order)
);
ALTER TABLE warehouse_contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse_contacts FORCE ROW LEVEL SECURITY;
CREATE POLICY warehouse_contacts_isolation ON warehouse_contacts
 USING(tenant_id=current_setting('app.tenant_id',true)::uuid)
 WITH CHECK(tenant_id=current_setting('app.tenant_id',true)::uuid);
