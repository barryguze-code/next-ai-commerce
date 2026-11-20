-- Independent durable sale invalidation: quantity workers must not consume sale work.
CREATE TABLE shelf_sale_dirty (
 tenant_id uuid NOT NULL REFERENCES tenants(id), item_id uuid NOT NULL,
 changed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,item_id)
);
ALTER TABLE shelf_sale_dirty ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelf_sale_dirty FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shelf_sale_dirty USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE shelf_sale_publications ADD COLUMN urgent boolean NOT NULL DEFAULT false;
CREATE FUNCTION enqueue_shelf_sale_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO shelf_sale_dirty(tenant_id,item_id) VALUES(NEW.tenant_id,NEW.item_id)
 ON CONFLICT(tenant_id,item_id) DO UPDATE SET changed_at=now();
 RETURN NULL;
END $$;
CREATE TRIGGER shelf_sale_stock_changes AFTER INSERT OR UPDATE ON inventory_publication_dirty
 FOR EACH ROW EXECUTE FUNCTION enqueue_shelf_sale_review();
-- Product-specific overrides and SKU selection also need prompt reconciliation.
CREATE TRIGGER publish_sale_product_defaults AFTER INSERT OR UPDATE OR DELETE ON inventory_shelf_life_product_overrides
 FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ITEM');
CREATE TRIGGER publish_sale_targets AFTER INSERT OR UPDATE OR DELETE ON inventory_expiration_action_targets
 FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');
