-- Persistent Amazon Buy Shipping batches, reprinting, and cold-chain operating policy.
ALTER TABLE shipping_package_profiles
    ADD COLUMN temperature_class VARCHAR(20) NOT NULL DEFAULT 'AMBIENT'
        CHECK (temperature_class IN ('AMBIENT','REFRIGERATED','FROZEN'));

ALTER TABLE shipping_label_artifacts
    ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0 CHECK (access_count>=0),
    ADD COLUMN last_accessed_at TIMESTAMPTZ;

CREATE TABLE buy_shipping_policies (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    handling_days INTEGER NOT NULL DEFAULT 0 CHECK (handling_days BETWEEN 0 AND 10),
    target_transit_days INTEGER NOT NULL DEFAULT 2 CHECK (target_transit_days BETWEEN 1 AND 7),
    cutoff_time TIME NOT NULL DEFAULT TIME '11:00',
    blocked_service_terms JSONB NOT NULL DEFAULT '["SUREPOST"]'::jsonb,
    ups_ground_premium_limit NUMERIC(10,2) NOT NULL DEFAULT 1.00 CHECK (ups_ground_premium_limit>=0),
    cold_chain_weekend_hold BOOLEAN NOT NULL DEFAULT TRUE,
    paid_or_expedited_friday_handoff BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,marketplace_connection_id),
    FOREIGN KEY (tenant_id,marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE buy_shipping_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    state VARCHAR(30) NOT NULL DEFAULT 'RATING' CHECK (state IN (
        'RATING','AWAITING_REVIEW','PURCHASE_QUEUED','PROCESSING','READY','PARTIAL','FAILED')),
    policy_snapshot JSONB NOT NULL,
    currency CHAR(3),
    quoted_total NUMERIC(19,4),
    created_by_email VARCHAR(320) NOT NULL,
    confirmed_by_email VARCHAR(320),
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

ALTER TABLE buy_shipping_shipments
    ADD COLUMN batch_id UUID,
    ADD COLUMN planned_handoff_date DATE,
    ADD COLUMN extra_ice BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN service_decision JSONB,
    ADD FOREIGN KEY (tenant_id,batch_id) REFERENCES buy_shipping_batches(tenant_id,id);

CREATE TABLE buy_shipping_batch_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_id VARCHAR(40) NOT NULL,
    package_profile_id UUID NOT NULL,
    shipment_id UUID,
    selected_offer_id UUID,
    state VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (state IN (
        'PENDING','RATING','RATED','NEEDS_ATTENTION','PURCHASE_QUEUED','PURCHASED','PURCHASE_UNKNOWN','FAILED')),
    print_sequence INTEGER NOT NULL CHECK (print_sequence>0),
    priority INTEGER NOT NULL DEFAULT 1 CHECK (priority BETWEEN 0 AND 9),
    group_key VARCHAR(300) NOT NULL,
    primary_sku VARCHAR(240),
    primary_title TEXT,
    customer_shipping NUMERIC(19,4) NOT NULL DEFAULT 0,
    service_level VARCHAR(120),
    planned_handoff_date DATE,
    extra_ice BOOLEAN NOT NULL DEFAULT FALSE,
    selected_cost NUMERIC(19,4),
    currency CHAR(3),
    decision_reason VARCHAR(500),
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,batch_id,amazon_order_id),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES buy_shipping_batches(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,marketplace_connection_id,amazon_order_id)
        REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id),
    FOREIGN KEY (tenant_id,package_profile_id) REFERENCES shipping_package_profiles(tenant_id,id),
    FOREIGN KEY (tenant_id,shipment_id) REFERENCES buy_shipping_shipments(tenant_id,id),
    FOREIGN KEY (tenant_id,selected_offer_id) REFERENCES buy_shipping_rate_offers(tenant_id,id)
);

CREATE INDEX buy_shipping_batch_work_idx
    ON buy_shipping_batch_orders(tenant_id,state,updated_at) WHERE state IN ('PENDING','RATING','PURCHASE_QUEUED');
CREATE UNIQUE INDEX buy_shipping_one_active_batch_order_idx
    ON buy_shipping_batch_orders(tenant_id,marketplace_connection_id,amazon_order_id)
    WHERE state IN ('PENDING','RATING','RATED','PURCHASE_QUEUED');
CREATE INDEX buy_shipping_batch_print_idx
    ON buy_shipping_batch_orders(tenant_id,batch_id,print_sequence);
CREATE INDEX buy_shipping_shipment_batch_idx
    ON buy_shipping_shipments(tenant_id,batch_id,created_at);
CREATE INDEX shipping_label_library_idx
    ON buy_shipping_shipments(tenant_id,marketplace_connection_id,purchased_at DESC)
    WHERE state IN ('PURCHASED','REFUND_QUEUED','REFUND_PENDING','REFUND_REJECTED','REFUND_APPLIED');

CREATE TRIGGER buy_shipping_policies_set_updated_at BEFORE UPDATE ON buy_shipping_policies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER buy_shipping_batches_set_updated_at BEFORE UPDATE ON buy_shipping_batches
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER buy_shipping_batch_orders_set_updated_at BEFORE UPDATE ON buy_shipping_batch_orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DO $$ DECLARE table_name TEXT; BEGIN
  FOREACH table_name IN ARRAY ARRAY['buy_shipping_policies','buy_shipping_batches','buy_shipping_batch_orders'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name||'_isolation',table_name);
  END LOOP;
END $$;
