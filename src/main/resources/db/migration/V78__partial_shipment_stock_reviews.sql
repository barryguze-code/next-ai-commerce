-- Missing historical allocations must never consume the unshipped remainder on the next sync.
CREATE TABLE order_unrecorded_shipments (
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 amazon_order_item_id uuid NOT NULL,
 account_catalog_item_id uuid NOT NULL,
 quantity numeric NOT NULL CHECK(quantity>0),
 detected_at timestamptz NOT NULL DEFAULT now(),
 reviewed_at timestamptz,
 PRIMARY KEY(tenant_id,amazon_order_item_id,account_catalog_item_id)
);
ALTER TABLE order_unrecorded_shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_unrecorded_shipments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_unrecorded_shipments
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE INDEX unrecorded_shipments_held_item ON order_unrecorded_shipments(tenant_id,account_catalog_item_id) WHERE reviewed_at IS NULL;
CREATE TRIGGER publish_unrecorded_shipments AFTER INSERT OR UPDATE OR DELETE ON order_unrecorded_shipments
 FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ITEM');
