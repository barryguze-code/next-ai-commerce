-- Durable invalidations commit with stock changes; no HTTP calls from database triggers.
CREATE TABLE inventory_publication_dirty (
 tenant_id uuid NOT NULL REFERENCES tenants(id), item_id uuid NOT NULL,
 changed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,item_id)
);
CREATE TABLE inventory_publications (
 tenant_id uuid NOT NULL REFERENCES tenants(id), connection_id uuid NOT NULL,
 marketplace_id text NOT NULL, seller_sku text NOT NULL,
 desired_quantity integer NOT NULL CHECK(desired_quantity>=0), revision bigint NOT NULL DEFAULT 1,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DRY_RUN','VERIFYING','CONFIRMED','RETRY','ATTENTION','DISABLED')),
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_error text, observed_quantity integer, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,connection_id,marketplace_id,seller_sku),
 FOREIGN KEY(tenant_id,connection_id) REFERENCES marketplace_connections(tenant_id,id)
);
CREATE TABLE inventory_publication_reconciliation (
 tenant_id uuid PRIMARY KEY REFERENCES tenants(id), checked_at timestamptz NOT NULL
);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['inventory_publication_dirty','inventory_publications','inventory_publication_reconciliation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
 END LOOP;
END $$;
CREATE INDEX inventory_publications_due ON inventory_publications(tenant_id,next_attempt_at) WHERE status NOT IN ('DRY_RUN','DISABLED');

CREATE FUNCTION invalidate_inventory_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb; t uuid; item uuid; BEGIN
 FOR r IN SELECT x FROM unnest(ARRAY[CASE WHEN TG_OP<>'INSERT' THEN to_jsonb(OLD) END,CASE WHEN TG_OP<>'DELETE' THEN to_jsonb(NEW) END]) x WHERE x IS NOT NULL LOOP
  t=(r->>'tenant_id')::uuid;
  IF TG_TABLE_NAME='inventory_ledger_entries' AND (r->>'quantity')::numeric=0 THEN CONTINUE; END IF;
  IF TG_ARGV[0]='ORDER' THEN
   INSERT INTO inventory_publication_dirty(tenant_id,item_id)
   SELECT DISTINCT t,c.account_catalog_item_id FROM amazon_order_items i
   JOIN marketplace_sku_mappings m ON m.tenant_id=i.tenant_id AND m.marketplace_connection_id=i.marketplace_connection_id AND m.marketplace_sku=i.seller_sku
   JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   WHERE i.tenant_id=t AND i.marketplace_connection_id=(r->>'marketplace_connection_id')::uuid AND i.amazon_order_id=r->>'amazon_order_id'
   ON CONFLICT(tenant_id,item_id) DO UPDATE SET changed_at=now();
  ELSE
   item=CASE WHEN TG_ARGV[0]='ITEM' THEN (r->>'account_catalog_item_id')::uuid ELSE NULL END;
   INSERT INTO inventory_publication_dirty VALUES(t,coalesce(item,'00000000-0000-0000-0000-000000000000'::uuid),now())
   ON CONFLICT(tenant_id,item_id) DO UPDATE SET changed_at=now();
  END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE TRIGGER publish_stock AFTER INSERT OR UPDATE OR DELETE ON inventory_ledger_entries FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ITEM');
CREATE TRIGGER publish_reservations AFTER INSERT OR UPDATE OR DELETE ON order_inventory_reservations FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ITEM');
CREATE TRIGGER publish_holds AFTER INSERT OR UPDATE OR DELETE ON inventory_expiration_actions FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ITEM');
CREATE TRIGGER publish_policy AFTER INSERT OR UPDATE OR DELETE ON inventory_shelf_life_policies FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');
CREATE TRIGGER publish_mapping AFTER INSERT OR UPDATE OR DELETE ON marketplace_sku_mappings FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');
CREATE TRIGGER publish_components AFTER INSERT OR UPDATE OR DELETE ON marketplace_sku_mapping_components FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');
CREATE TRIGGER publish_order_state AFTER INSERT OR DELETE OR UPDATE OF order_status,platform_waiting_for_pickup,operational_scope ON amazon_orders FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ORDER');
CREATE TRIGGER publish_order_items AFTER INSERT OR DELETE OR UPDATE OF quantity_ordered,quantity_shipped,seller_sku ON amazon_order_items FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ORDER');
CREATE TRIGGER publish_listing AFTER INSERT OR DELETE OR UPDATE OF fulfillment_channel,listing_status,seller_sku,marketplace_id ON amazon_listings FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');

ALTER TABLE inventory_ledger_entries DROP CONSTRAINT inventory_ledger_entries_quantity_check;
ALTER TABLE inventory_ledger_entries ADD CONSTRAINT inventory_ledger_entries_quantity_check
 CHECK(quantity<>0 OR source_type='PHYSICAL_COUNT' OR entry_type IN ('SHIPMENT_UNRECORDED','SHARED_STOCK_SALES'));

CREATE TRIGGER publish_connection AFTER UPDATE OF status,marketplace_identifier ON marketplace_connections FOR EACH ROW EXECUTE FUNCTION invalidate_inventory_publication('ALL');
-- Global low-rate safety budget; no account data in this singleton.
CREATE TABLE inventory_publication_rate_slot (id integer PRIMARY KEY CHECK(id=1), ready_at timestamptz NOT NULL);
INSERT INTO inventory_publication_rate_slot VALUES(1,now());
