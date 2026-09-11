CREATE TABLE warehouse_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id)
);

CREATE UNIQUE INDEX warehouse_locations_code_ci_uk
    ON warehouse_locations(tenant_id,upper(code));
CREATE INDEX warehouse_locations_active_idx
    ON warehouse_locations(tenant_id,status,upper(code));

CREATE TABLE account_catalog_item_locations (
    tenant_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    location_id UUID NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,account_catalog_item_id,location_id),
    FOREIGN KEY (tenant_id,account_catalog_item_id)
        REFERENCES account_catalog_items(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,location_id)
        REFERENCES warehouse_locations(tenant_id,id)
);

CREATE UNIQUE INDEX account_catalog_item_one_default_location_uk
    ON account_catalog_item_locations(tenant_id,account_catalog_item_id)
    WHERE is_default;
CREATE INDEX account_catalog_item_locations_location_idx
    ON account_catalog_item_locations(tenant_id,location_id,account_catalog_item_id);

CREATE TRIGGER warehouse_locations_set_updated_at BEFORE UPDATE ON warehouse_locations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER account_catalog_item_locations_set_updated_at BEFORE UPDATE ON account_catalog_item_locations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO warehouse_locations(tenant_id,code,name)
SELECT tenant.id,'MAIN','Main storage' FROM tenants tenant;

-- Catalogue rows use forced RLS, while Flyway intentionally has no tenant context.
ALTER TABLE account_catalog_items NO FORCE ROW LEVEL SECURITY;
ALTER TABLE account_catalog_items DISABLE ROW LEVEL SECURITY;
INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
SELECT item.tenant_id,item.id,location.id,TRUE
FROM account_catalog_items item
JOIN warehouse_locations location ON location.tenant_id=item.tenant_id AND location.code='MAIN';
ALTER TABLE account_catalog_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_catalog_items FORCE ROW LEVEL SECURITY;

ALTER TABLE inventory_ledger_entries ADD COLUMN location_id UUID;
ALTER TABLE receiving_line_receipts ADD COLUMN location_id UUID;
ALTER TABLE order_inventory_reservations ADD COLUMN location_id UUID;

-- Flyway has no tenant context; temporarily relax forced RLS for this one-time backfill.
ALTER TABLE inventory_ledger_entries NO FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_ledger_entries DISABLE ROW LEVEL SECURITY;
UPDATE inventory_ledger_entries ledger SET location_id=location.id
FROM warehouse_locations location
WHERE location.tenant_id=ledger.tenant_id AND location.code='MAIN';
ALTER TABLE inventory_ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_ledger_entries FORCE ROW LEVEL SECURITY;

ALTER TABLE receiving_line_receipts NO FORCE ROW LEVEL SECURITY;
ALTER TABLE receiving_line_receipts DISABLE ROW LEVEL SECURITY;
UPDATE receiving_line_receipts receipt SET location_id=location.id
FROM warehouse_locations location
WHERE location.tenant_id=receipt.tenant_id AND location.code='MAIN';
ALTER TABLE receiving_line_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_line_receipts FORCE ROW LEVEL SECURITY;

ALTER TABLE order_inventory_reservations NO FORCE ROW LEVEL SECURITY;
ALTER TABLE order_inventory_reservations DISABLE ROW LEVEL SECURITY;
UPDATE order_inventory_reservations reservation SET location_id=location.id
FROM warehouse_locations location
WHERE location.tenant_id=reservation.tenant_id AND location.code='MAIN';
ALTER TABLE order_inventory_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_inventory_reservations FORCE ROW LEVEL SECURITY;

ALTER TABLE warehouse_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse_locations FORCE ROW LEVEL SECURITY;
CREATE POLICY warehouse_locations_isolation ON warehouse_locations
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE account_catalog_item_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_catalog_item_locations FORCE ROW LEVEL SECURITY;
CREATE POLICY account_catalog_item_locations_isolation ON account_catalog_item_locations
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

ALTER TABLE inventory_ledger_entries ALTER COLUMN location_id SET NOT NULL;
ALTER TABLE receiving_line_receipts ALTER COLUMN location_id SET NOT NULL;
ALTER TABLE order_inventory_reservations ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE inventory_ledger_entries ADD CONSTRAINT inventory_ledger_location_fk
    FOREIGN KEY (tenant_id,location_id) REFERENCES warehouse_locations(tenant_id,id);
ALTER TABLE receiving_line_receipts ADD CONSTRAINT receiving_receipt_location_fk
    FOREIGN KEY (tenant_id,location_id) REFERENCES warehouse_locations(tenant_id,id);
ALTER TABLE order_inventory_reservations ADD CONSTRAINT order_reservation_location_fk
    FOREIGN KEY (tenant_id,location_id) REFERENCES warehouse_locations(tenant_id,id);

DROP INDEX order_inventory_reservations_one_active_layer_idx;
CREATE UNIQUE INDEX order_inventory_reservations_one_active_layer_idx
    ON order_inventory_reservations(tenant_id,amazon_order_item_id,account_catalog_item_id,
        location_id,coalesce(expiration_date,DATE 'infinity'),coalesce(cost_layer_id,'00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status='ACTIVE';
DROP INDEX order_inventory_reservations_available_idx;
CREATE INDEX order_inventory_reservations_available_idx
    ON order_inventory_reservations(tenant_id,account_catalog_item_id,location_id,expiration_date)
    WHERE status='ACTIVE';
CREATE INDEX inventory_ledger_position_idx
    ON inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,expiration_date,occurred_at DESC);

CREATE OR REPLACE FUNCTION ensure_item_default_location() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
DECLARE main_location UUID;
BEGIN
    INSERT INTO warehouse_locations(tenant_id,code,name)
    VALUES (NEW.tenant_id,'MAIN','Main storage') ON CONFLICT DO NOTHING;
    SELECT id INTO main_location FROM warehouse_locations
      WHERE tenant_id=NEW.tenant_id AND upper(code)='MAIN';
    INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
    VALUES (NEW.tenant_id,NEW.id,main_location,TRUE) ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER account_catalog_items_ensure_default_location AFTER INSERT ON account_catalog_items
    FOR EACH ROW EXECUTE FUNCTION ensure_item_default_location();

CREATE OR REPLACE FUNCTION fill_inventory_ledger_location() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
BEGIN
    IF NEW.location_id IS NULL THEN
        SELECT location_id INTO NEW.location_id FROM account_catalog_item_locations
        WHERE tenant_id=NEW.tenant_id AND account_catalog_item_id=NEW.account_catalog_item_id AND is_default;
    END IF;
    IF NEW.location_id IS NULL THEN RAISE EXCEPTION 'No default inventory location is configured for this product.'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER inventory_ledger_fill_location BEFORE INSERT ON inventory_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fill_inventory_ledger_location();

CREATE OR REPLACE FUNCTION fill_receiving_receipt_location() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
BEGIN
    IF NEW.location_id IS NULL THEN
        SELECT assignment.location_id INTO NEW.location_id
        FROM purchase_order_items item
        JOIN account_catalog_item_locations assignment
          ON assignment.tenant_id=item.tenant_id AND assignment.account_catalog_item_id=item.account_catalog_item_id
         AND assignment.is_default
        WHERE item.tenant_id=NEW.tenant_id AND item.id=NEW.purchase_order_item_id;
    END IF;
    IF NEW.location_id IS NULL THEN RAISE EXCEPTION 'No default inventory location is configured for this product.'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER receiving_receipt_fill_location BEFORE INSERT ON receiving_line_receipts
    FOR EACH ROW EXECUTE FUNCTION fill_receiving_receipt_location();

CREATE OR REPLACE FUNCTION fill_order_reservation_location() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public AS $$
BEGIN
    IF NEW.location_id IS NULL AND NEW.cost_layer_id IS NOT NULL THEN
        SELECT location_id INTO NEW.location_id FROM inventory_ledger_entries
        WHERE tenant_id=NEW.tenant_id AND id=NEW.cost_layer_id;
    END IF;
    IF NEW.location_id IS NULL THEN
        SELECT location_id INTO NEW.location_id FROM account_catalog_item_locations
        WHERE tenant_id=NEW.tenant_id AND account_catalog_item_id=NEW.account_catalog_item_id AND is_default;
    END IF;
    IF NEW.location_id IS NULL THEN RAISE EXCEPTION 'No inventory location is available for this reservation.'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER order_reservation_fill_location BEFORE INSERT ON order_inventory_reservations
    FOR EACH ROW EXECUTE FUNCTION fill_order_reservation_location();
