-- Retain referenced identities while releasing only their live catalogue codes.
ALTER TABLE global_catalog_products ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_identity jsonb;
ALTER TABLE account_catalog_items ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_identity jsonb;
ALTER TABLE account_catalog_items DROP CONSTRAINT account_catalog_items_tenant_id_global_product_id_key;
CREATE UNIQUE INDEX account_catalog_live_product ON account_catalog_items(tenant_id,global_product_id)
    WHERE deleted_at IS NULL;
CREATE OR REPLACE FUNCTION protect_deleted_catalogue_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.deleted_at IS NOT NULL AND (NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
        OR NEW.deleted_identity IS DISTINCT FROM OLD.deleted_identity) THEN
        RAISE EXCEPTION 'Deleted catalogue identity audit cannot be changed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER global_deleted_identity_audit BEFORE UPDATE ON global_catalog_products
    FOR EACH ROW EXECUTE FUNCTION protect_deleted_catalogue_identity();
CREATE TRIGGER account_deleted_identity_audit BEFORE UPDATE ON account_catalog_items
    FOR EACH ROW EXECUTE FUNCTION protect_deleted_catalogue_identity();

-- A stale form must never resurrect an identity or reclaim its released codes.
CREATE OR REPLACE FUNCTION reject_deleted_catalogue_edit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.deleted_at IS NOT NULL THEN
   RAISE EXCEPTION 'This product was deleted. Reopen the catalogue.';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER global_deleted_edit BEFORE UPDATE ON global_catalog_products
 FOR EACH ROW EXECUTE FUNCTION reject_deleted_catalogue_edit();
CREATE TRIGGER account_deleted_edit BEFORE UPDATE ON account_catalog_items
 FOR EACH ROW EXECUTE FUNCTION reject_deleted_catalogue_edit();

CREATE OR REPLACE FUNCTION require_live_global_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM global_catalog_products WHERE id=NEW.global_product_id AND deleted_at IS NULL FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'This global product was deleted. Reopen the catalogue.'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER live_account_product BEFORE INSERT OR UPDATE OF global_product_id ON account_catalog_items
 FOR EACH ROW EXECUTE FUNCTION require_live_global_identity();
CREATE TRIGGER live_global_barcode BEFORE INSERT OR UPDATE ON global_product_identifiers
 FOR EACH ROW EXECUTE FUNCTION require_live_global_identity();
CREATE TRIGGER live_global_vendor_code BEFORE INSERT OR UPDATE ON global_product_vendor_codes
 FOR EACH ROW EXECUTE FUNCTION require_live_global_identity();

-- Row locking closes the race with a receiving command validated before deletion.
CREATE OR REPLACE FUNCTION require_live_stock_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='order_inventory_reservations' THEN
   IF NEW.status<>'ACTIVE' THEN RETURN NEW; END IF;
 END IF;
 PERFORM 1 FROM account_catalog_items WHERE tenant_id=NEW.tenant_id
   AND id=NEW.account_catalog_item_id AND deleted_at IS NULL FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'This product was deleted. Reopen the catalogue before changing stock.'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER live_ledger_product BEFORE INSERT ON inventory_ledger_entries
 FOR EACH ROW EXECUTE FUNCTION require_live_stock_identity();
CREATE TRIGGER live_reservation_product BEFORE INSERT OR UPDATE ON order_inventory_reservations
 FOR EACH ROW EXECUTE FUNCTION require_live_stock_identity();
