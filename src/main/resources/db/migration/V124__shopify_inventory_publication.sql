-- Explicit account-owned source bins and provider destination; no implicit stock pool.
-- No bindings, backfill, publication jobs, or marketplace calls are created here.
CREATE TABLE shopify_inventory_bindings (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
 marketplace_connection_id UUID NOT NULL, channel TEXT NOT NULL DEFAULT 'SHOPIFY' CHECK(channel='SHOPIFY'),
 shop_gid TEXT NOT NULL CHECK(shop_gid ~ '^gid://shopify/Shop/[0-9]+$'),
 location_gid TEXT NOT NULL CHECK(location_gid ~ '^gid://shopify/Location/[0-9]+$'),
 revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0), enabled BOOLEAN NOT NULL DEFAULT FALSE,
 approved_by TEXT NOT NULL, approved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,marketplace_connection_id,id), UNIQUE(tenant_id,marketplace_connection_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,channel) REFERENCES marketplace_connections(tenant_id,id,channel)
);
CREATE TABLE shopify_inventory_binding_sources (
 tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL, binding_id UUID NOT NULL,
 location_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,marketplace_connection_id,binding_id,location_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,binding_id) REFERENCES shopify_inventory_bindings(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,location_id) REFERENCES warehouse_locations(tenant_id,id)
);
CREATE TABLE shopify_inventory_publications (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL,
 binding_id UUID NOT NULL, binding_revision BIGINT NOT NULL CHECK(binding_revision>0), variant_id UUID NOT NULL,
 inventory_item_gid TEXT NOT NULL CHECK(inventory_item_gid ~ '^gid://shopify/InventoryItem/[0-9]+$'),
 shop_gid TEXT NOT NULL CHECK(shop_gid ~ '^gid://shopify/Shop/[0-9]+$'),
 location_gid TEXT NOT NULL CHECK(location_gid ~ '^gid://shopify/Location/[0-9]+$'),
 mapping_hash CHAR(64) NOT NULL, desired_quantity INTEGER NOT NULL CHECK(desired_quantity>=0),
 expected_quantity INTEGER, observed_quantity INTEGER,
 state TEXT NOT NULL DEFAULT 'PREPARING' CHECK(state IN ('PREPARING','READY','VERIFYING','CONFIRMED','ATTENTION','CANCELLED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 prepared_at TIMESTAMPTZ, submitted_at TIMESTAMPTZ, confirmed_at TIMESTAMPTZ, reconciled_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), detail TEXT,
 FOREIGN KEY(tenant_id,marketplace_connection_id,binding_id) REFERENCES shopify_inventory_bindings(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,variant_id) REFERENCES shopify_variants(tenant_id,marketplace_connection_id,id),
 CHECK(state NOT IN ('READY','VERIFYING','CONFIRMED') OR expected_quantity IS NOT NULL)
);
CREATE UNIQUE INDEX shopify_inventory_one_active ON shopify_inventory_publications(tenant_id,marketplace_connection_id,variant_id)
 WHERE state IN ('PREPARING','READY','VERIFYING');
CREATE INDEX shopify_inventory_ready ON shopify_inventory_publications(available_at,created_at)
 WHERE state IN ('PREPARING','READY','VERIFYING');
-- Changing approved source membership invalidates pending revisions, never silently expands them.
CREATE FUNCTION invalidate_shopify_inventory_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN
   UPDATE shopify_inventory_bindings SET revision=revision+1,enabled=FALSE WHERE tenant_id=OLD.tenant_id AND id=OLD.binding_id;
 END IF;
 IF TG_OP<>'DELETE' THEN
   UPDATE shopify_inventory_bindings SET revision=revision+1,enabled=FALSE WHERE tenant_id=NEW.tenant_id AND id=NEW.binding_id;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER shopify_inventory_source_changed AFTER INSERT OR UPDATE OR DELETE ON shopify_inventory_binding_sources
 FOR EACH ROW EXECUTE FUNCTION invalidate_shopify_inventory_binding();
ALTER TABLE shopify_inventory_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_inventory_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_inventory_bindings
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE shopify_inventory_binding_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_inventory_binding_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_inventory_binding_sources
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE shopify_inventory_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_inventory_publications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_inventory_publications
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE FUNCTION protect_shopify_inventory_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.marketplace_connection_id) IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.marketplace_connection_id) THEN
   RAISE EXCEPTION 'Inventory binding identity cannot be reassigned';
 END IF;
 IF (NEW.shop_gid,NEW.location_gid) IS DISTINCT FROM (OLD.shop_gid,OLD.location_gid) THEN
   NEW.revision=OLD.revision+1; NEW.enabled=FALSE;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_shopify_inventory_binding BEFORE UPDATE ON shopify_inventory_bindings
 FOR EACH ROW EXECUTE FUNCTION protect_shopify_inventory_binding();
CREATE FUNCTION protect_shopify_publication_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.tenant_id,NEW.marketplace_connection_id,NEW.binding_id,NEW.binding_revision,NEW.variant_id,NEW.inventory_item_gid,NEW.mapping_hash,NEW.shop_gid,NEW.location_gid,NEW.desired_quantity)
 IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.marketplace_connection_id,OLD.binding_id,OLD.binding_revision,OLD.variant_id,OLD.inventory_item_gid,OLD.mapping_hash,OLD.shop_gid,OLD.location_gid,OLD.desired_quantity)
 OR (OLD.expected_quantity IS NOT NULL AND NEW.expected_quantity IS DISTINCT FROM OLD.expected_quantity) THEN
   RAISE EXCEPTION 'Published intent is immutable; reconcile and create a new operation';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_shopify_publication_intent BEFORE UPDATE ON shopify_inventory_publications
 FOR EACH ROW EXECUTE FUNCTION protect_shopify_publication_intent();

-- Explicitly scoped, remaining-only cutover proof. Never edits archived source or past shipments.
CREATE TABLE shopify_order_cutovers (
 tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL, order_line_id UUID NOT NULL,
 source_document_id UUID NOT NULL, sync_job_id UUID NOT NULL, source_revision TIMESTAMPTZ NOT NULL,
 source_hash CHAR(64) NOT NULL, fulfillment_checkpoint JSONB NOT NULL,
 composition JSONB NOT NULL, remaining_units INTEGER NOT NULL CHECK(remaining_units>0),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,marketplace_connection_id,order_line_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,order_line_id) REFERENCES shopify_order_lines(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,source_document_id) REFERENCES shopify_source_documents(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,sync_job_id) REFERENCES marketplace_sync_jobs(tenant_id,marketplace_connection_id,id),
 CHECK(jsonb_typeof(fulfillment_checkpoint)='object'),
 CHECK(jsonb_typeof(composition)='array' AND jsonb_array_length(composition)>0)
);
ALTER TABLE shopify_order_cutovers ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_order_cutovers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_order_cutovers
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER shopify_cutover_append_only BEFORE UPDATE OR DELETE ON shopify_order_cutovers
 FOR EACH ROW EXECUTE FUNCTION preserve_shopify_source_document();
