-- Local/test capabilities only. No stock or historical rows are rewritten.
-- Variant identity is authoritative: Shopify SKU text may be blank or duplicated.
CREATE TABLE shopify_inventory_simulations (
 tenant_id uuid NOT NULL,
 marketplace_connection_id uuid NOT NULL,
 variant_id uuid NOT NULL,
 desired_quantity integer NOT NULL CHECK(desired_quantity>=0),
 revision bigint NOT NULL DEFAULT 1,
 state text NOT NULL CHECK(state IN ('DRY_RUN','ATTENTION','DISABLED')),
 detail text NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id,variant_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,variant_id) REFERENCES shopify_variants(tenant_id,marketplace_connection_id,id)
);
ALTER TABLE shopify_inventory_simulations ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_inventory_simulations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_inventory_simulations
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER publish_shopify_variant AFTER INSERT OR DELETE OR UPDATE OF mapping_hash,inventory_item_gid ON shopify_variants
 FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');
CREATE TRIGGER publish_shopify_product AFTER UPDATE OF product_status ON shopify_products
 FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');

-- Reuse carrier shipment/archive tables, but keep saved packaging provider-native.
-- The existing Amazon packaging FK is intentionally left unchanged.
CREATE TABLE shopify_order_packaging (
 tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL, order_id uuid NOT NULL,
 composition_key varchar(64) NOT NULL, parcel jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id,order_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,order_id) REFERENCES shopify_orders(tenant_id,marketplace_connection_id,id)
);
ALTER TABLE shopify_order_packaging ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_order_packaging FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_order_packaging
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
