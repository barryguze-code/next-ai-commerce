-- Additive Shopify Orders foundation. No activation, backfill, jobs or inventory movements.
CREATE TABLE shopify_order_controls (
    tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL,
    channel TEXT NOT NULL DEFAULT 'SHOPIFY' CHECK(channel='SHOPIFY'),
    inventory_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    activated_at TIMESTAMPTZ, baseline_completed_at TIMESTAMPTZ,
    polling_enabled BOOLEAN NOT NULL DEFAULT FALSE, actor_email TEXT,
    next_sync_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,marketplace_connection_id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,channel)
        REFERENCES marketplace_connections(tenant_id,id,channel),
    CHECK(NOT inventory_enabled OR (activated_at IS NOT NULL AND baseline_completed_at IS NOT NULL))
);
CREATE TABLE shopify_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL,
    channel TEXT NOT NULL DEFAULT 'SHOPIFY' CHECK(channel='SHOPIFY'),
    order_gid TEXT NOT NULL CHECK(order_gid ~ '^gid://shopify/Order/[0-9]+$'), order_name TEXT NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL, source_updated_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ, is_test BOOLEAN NOT NULL, payment_status TEXT NOT NULL, fulfillment_status TEXT NOT NULL,
    currency CHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'), total NUMERIC(19,4) CHECK(total>=0),
    evidence_complete BOOLEAN NOT NULL, observation_hash CHAR(64) NOT NULL,
    source_document_id UUID NOT NULL, observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), review_reason TEXT,
    UNIQUE(tenant_id,marketplace_connection_id,id), UNIQUE(tenant_id,marketplace_connection_id,order_gid),
    FOREIGN KEY(tenant_id,marketplace_connection_id,channel) REFERENCES marketplace_connections(tenant_id,id,channel),
    FOREIGN KEY(tenant_id,marketplace_connection_id,source_document_id)
        REFERENCES shopify_source_documents(tenant_id,marketplace_connection_id,id),
    CHECK(source_updated_at>=source_created_at)
);
CREATE TABLE shopify_order_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL,
    order_id UUID NOT NULL, line_gid TEXT NOT NULL CHECK(line_gid ~ '^gid://shopify/LineItem/[0-9]+$'),
    variant_gid TEXT CHECK(variant_gid IS NULL OR variant_gid ~ '^gid://shopify/ProductVariant/[0-9]+$'),
    variant_id UUID, seller_sku TEXT, title TEXT NOT NULL, image_url TEXT,
    ordered_quantity INTEGER NOT NULL CHECK(ordered_quantity>=0),
    current_quantity INTEGER NOT NULL CHECK(current_quantity>=0),
    unfulfilled_quantity INTEGER NOT NULL CHECK(unfulfilled_quantity>=0), requires_shipping BOOLEAN NOT NULL,
    ownership TEXT NOT NULL CHECK(ownership IN ('MERCHANT','EXTERNAL','UNKNOWN')),
    source_updated_at TIMESTAMPTZ NOT NULL, observation_hash CHAR(64) NOT NULL,
    inventory_revision TIMESTAMPTZ, inventory_hash CHAR(64),
    line_total NUMERIC(19,4) CHECK(line_total>=0), currency CHAR(3) CHECK(currency ~ '^[A-Z]{3}$'),
    stock_scope TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'
        CHECK(stock_scope IN ('LIVE','REPORTING_ONLY','TEST','NEEDS_REVIEW','CANCELLED')),
    inventory_state TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'
        CHECK(inventory_state IN ('REPORTING_ONLY','NEEDS_REVIEW','NEEDS_MAPPING','READY_TO_SHIP','INVENTORY_SHORTAGE','SHIPPED','CLOSED','CANCELLED')),
    reserve_units INTEGER NOT NULL DEFAULT 0 CHECK(reserve_units>=0),
    success_checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(success_checkpoint)='object'),
    basis_hash CHAR(64), basis_variant_gid TEXT, review_reason TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,marketplace_connection_id,id), UNIQUE(tenant_id,marketplace_connection_id,line_gid),
    FOREIGN KEY(tenant_id,marketplace_connection_id,order_id) REFERENCES shopify_orders(tenant_id,marketplace_connection_id,id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,variant_id) REFERENCES shopify_variants(tenant_id,marketplace_connection_id,id),
    CHECK(unfulfilled_quantity<=ordered_quantity),
    CHECK((inventory_revision IS NULL)=(inventory_hash IS NULL)),
    CHECK((basis_hash IS NULL)=(basis_variant_gid IS NULL))
);
-- Frozen once a live line is mapped: later catalogue edits never rewrite shipment composition.
CREATE TABLE shopify_order_components (
    tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL, order_line_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL, quantity NUMERIC(16,4) NOT NULL CHECK(quantity>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,marketplace_connection_id,order_line_id,account_catalog_item_id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,order_line_id) REFERENCES shopify_order_lines(tenant_id,marketplace_connection_id,id),
    FOREIGN KEY(tenant_id,account_catalog_item_id) REFERENCES account_catalog_items(tenant_id,id)
);

-- Preserve existing Amazon keys/foreign keys/indexes and ON CONFLICT contracts.
ALTER TABLE order_inventory_reservations ADD COLUMN shopify_order_line_id UUID;
ALTER TABLE order_inventory_reservations ALTER COLUMN amazon_order_id DROP NOT NULL;
ALTER TABLE order_inventory_reservations ALTER COLUMN amazon_order_item_id DROP NOT NULL;
ALTER TABLE order_inventory_reservations ADD CONSTRAINT order_reservation_provider_identity CHECK(
    (shopify_order_line_id IS NULL AND amazon_order_id IS NOT NULL AND amazon_order_item_id IS NOT NULL) OR
    (shopify_order_line_id IS NOT NULL AND amazon_order_id IS NULL AND amazon_order_item_id IS NULL));
ALTER TABLE order_inventory_reservations ADD CONSTRAINT order_reservation_shopify_line_scope
    FOREIGN KEY(tenant_id,marketplace_connection_id,shopify_order_line_id)
        REFERENCES shopify_order_lines(tenant_id,marketplace_connection_id,id);
CREATE UNIQUE INDEX shopify_reservation_one_active_layer ON order_inventory_reservations
    (tenant_id,marketplace_connection_id,shopify_order_line_id,account_catalog_item_id,cost_layer_id)
    WHERE status='ACTIVE' AND shopify_order_line_id IS NOT NULL;
CREATE FUNCTION validate_shopify_reservation_layer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.shopify_order_line_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM inventory_ledger_entries layer
        JOIN shopify_order_components component ON component.tenant_id=layer.tenant_id
          AND component.account_catalog_item_id=layer.account_catalog_item_id
        WHERE layer.tenant_id=NEW.tenant_id AND layer.id=NEW.cost_layer_id AND layer.quantity>0
          AND layer.account_catalog_item_id=NEW.account_catalog_item_id AND layer.location_id=NEW.location_id
          AND layer.expiration_date IS NOT DISTINCT FROM NEW.expiration_date
          AND component.marketplace_connection_id=NEW.marketplace_connection_id
          AND component.order_line_id=NEW.shopify_order_line_id) THEN
        RAISE EXCEPTION 'Shopify reservation requires its own frozen product and exact scoped inventory layer';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER shopify_reservation_layer BEFORE INSERT OR UPDATE ON order_inventory_reservations
    FOR EACH ROW EXECUTE FUNCTION validate_shopify_reservation_layer();

CREATE INDEX shopify_orders_queue ON shopify_orders(tenant_id,marketplace_connection_id,source_created_at,id);
CREATE INDEX shopify_order_lines_variant ON shopify_order_lines(tenant_id,marketplace_connection_id,variant_id,order_id);
CREATE INDEX shopify_order_lines_open ON shopify_order_lines(tenant_id,marketplace_connection_id,stock_scope,order_id);
CREATE UNIQUE INDEX shopify_one_active_order_job ON marketplace_sync_jobs(tenant_id,marketplace_connection_id)
    WHERE job_type='SHOPIFY_ORDERS' AND status IN ('QUEUED','WAITING','RUNNING');
DO $$ DECLARE t TEXT; BEGIN
    FOREACH t IN ARRAY ARRAY['shopify_order_controls','shopify_orders','shopify_order_lines','shopify_order_components'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
    END LOOP;
END $$;
