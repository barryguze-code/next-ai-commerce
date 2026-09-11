-- Store-scoped Amazon Merchant Fulfillment / Buy Shipping workflow.
ALTER TABLE marketplace_connections
    ADD COLUMN buy_shipping_mode VARCHAR(24) NOT NULL DEFAULT 'RATES_ONLY'
        CHECK (buy_shipping_mode IN ('DISABLED','RATES_ONLY','PURCHASE_ENABLED')),
    ADD COLUMN print_packing_slip BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE merchant_ship_from_addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    label VARCHAR(120) NOT NULL,
    contact_name VARCHAR(180) NOT NULL,
    company_name VARCHAR(180),
    address_line_1 VARCHAR(240) NOT NULL,
    address_line_2 VARCHAR(240),
    address_line_3 VARCHAR(240),
    city VARCHAR(120) NOT NULL,
    state_or_province_code VARCHAR(80),
    postal_code VARCHAR(32) NOT NULL,
    country_code CHAR(2) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    email VARCHAR(320),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX merchant_ship_from_one_default_idx
    ON merchant_ship_from_addresses(tenant_id,marketplace_connection_id)
    WHERE is_default AND status='ACTIVE';
CREATE INDEX merchant_ship_from_store_idx
    ON merchant_ship_from_addresses(tenant_id,marketplace_connection_id,status,label);

CREATE TABLE shipping_package_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    container_code VARCHAR(80),
    length NUMERIC(12,3) NOT NULL CHECK (length>0),
    width NUMERIC(12,3) NOT NULL CHECK (width>0),
    height NUMERIC(12,3) NOT NULL CHECK (height>0),
    dimension_unit VARCHAR(12) NOT NULL CHECK (dimension_unit IN ('inches','centimeters')),
    weight NUMERIC(12,3) NOT NULL CHECK (weight>0),
    weight_unit VARCHAR(12) NOT NULL CHECK (weight_unit IN ('oz','g')),
    preferred_carrier VARCHAR(120),
    preferred_service_id VARCHAR(240),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,marketplace_connection_id,name),
    FOREIGN KEY (tenant_id,marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE marketplace_sku_package_defaults (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    seller_sku VARCHAR(240) NOT NULL,
    package_profile_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,marketplace_connection_id,seller_sku),
    FOREIGN KEY (tenant_id,marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,package_profile_id)
        REFERENCES shipping_package_profiles(tenant_id,id)
);

CREATE TABLE buy_shipping_shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_id VARCHAR(40) NOT NULL,
    ship_from_address_id UUID NOT NULL,
    package_profile_id UUID,
    package_sequence INTEGER NOT NULL DEFAULT 1 CHECK (package_sequence>0),
    state VARCHAR(30) NOT NULL DEFAULT 'RATED' CHECK (state IN (
        'RATED','PURCHASE_QUEUED','PURCHASE_IN_PROGRESS','PURCHASED','PURCHASE_UNKNOWN',
        'REFUND_QUEUED','REFUND_PENDING','REFUND_REJECTED','REFUND_APPLIED','FAILED')),
    request_fingerprint CHAR(64) NOT NULL,
    request_details JSONB NOT NULL,
    package_snapshot JSONB NOT NULL,
    amazon_shipment_id VARCHAR(100),
    carrier_name VARCHAR(160),
    shipping_service_name VARCHAR(240),
    shipping_service_id VARCHAR(240),
    shipping_service_offer_id TEXT,
    tracking_id VARCHAR(240),
    rate_amount NUMERIC(19,4),
    adjusted_rate_amount NUMERIC(19,4),
    currency CHAR(3),
    label_format VARCHAR(40) NOT NULL DEFAULT 'PDF',
    ship_date TIMESTAMPTZ,
    earliest_delivery_date TIMESTAMPTZ,
    latest_delivery_date TIMESTAMPTZ,
    packing_slip_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rating_expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '10 minutes',
    purchase_started_at TIMESTAMPTZ,
    purchased_at TIMESTAMPTZ,
    refund_requested_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    raw_rate_response JSONB,
    raw_purchase_response JSONB,
    last_error_code VARCHAR(120),
    last_error_message VARCHAR(500),
    recovery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (recovery_attempts BETWEEN 0 AND 20),
    created_by_email VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,marketplace_connection_id,amazon_order_id)
        REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id),
    FOREIGN KEY (tenant_id,ship_from_address_id)
        REFERENCES merchant_ship_from_addresses(tenant_id,id),
    FOREIGN KEY (tenant_id,package_profile_id)
        REFERENCES shipping_package_profiles(tenant_id,id)
);
CREATE UNIQUE INDEX buy_shipping_amazon_shipment_idx
    ON buy_shipping_shipments(tenant_id,marketplace_connection_id,amazon_shipment_id)
    WHERE amazon_shipment_id IS NOT NULL;
CREATE INDEX buy_shipping_work_idx
    ON buy_shipping_shipments(state,updated_at) WHERE state IN ('PURCHASE_QUEUED','REFUND_QUEUED');
CREATE INDEX buy_shipping_order_idx
    ON buy_shipping_shipments(tenant_id,marketplace_connection_id,amazon_order_id,created_at DESC);

CREATE TABLE buy_shipping_shipment_items (
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    amazon_order_item_id VARCHAR(80) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity>0),
    inventory_location_id UUID,
    inventory_expiration_date DATE,
    display_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (tenant_id,shipment_id,amazon_order_item_id),
    FOREIGN KEY (tenant_id,shipment_id)
        REFERENCES buy_shipping_shipments(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,marketplace_connection_id,amazon_order_item_id)
        REFERENCES amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_item_id),
    FOREIGN KEY (tenant_id,inventory_location_id)
        REFERENCES warehouse_locations(tenant_id,id)
);

CREATE TABLE buy_shipping_rate_offers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    service_id VARCHAR(240) NOT NULL,
    offer_id TEXT,
    carrier_name VARCHAR(160) NOT NULL,
    service_name VARCHAR(240) NOT NULL,
    base_amount NUMERIC(19,4),
    adjusted_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    ship_date TIMESTAMPTZ,
    earliest_delivery TIMESTAMPTZ,
    latest_delivery TIMESTAMPTZ,
    is_cheapest BOOLEAN NOT NULL DEFAULT FALSE,
    is_fastest BOOLEAN NOT NULL DEFAULT FALSE,
    requires_seller_input BOOLEAN NOT NULL DEFAULT FALSE,
    available_label_formats JSONB NOT NULL DEFAULT '[]'::jsonb,
    payload JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,shipment_id)
        REFERENCES buy_shipping_shipments(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX buy_shipping_offer_shipment_idx
    ON buy_shipping_rate_offers(tenant_id,shipment_id,adjusted_amount,latest_delivery);

CREATE TABLE shipping_label_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    artifact_type VARCHAR(30) NOT NULL CHECK (artifact_type IN (
        'CARRIER_LABEL','PACKING_SLIP','COMPOSED_PRINT_FILE')),
    encrypted_payload BYTEA NOT NULL,
    encryption_nonce BYTEA NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    checksum_algorithm VARCHAR(20),
    checksum_value VARCHAR(180),
    width NUMERIC(10,3),
    height NUMERIC(10,3),
    dimension_unit VARCHAR(12),
    page_count INTEGER NOT NULL DEFAULT 1 CHECK (page_count>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,shipment_id,artifact_type),
    FOREIGN KEY (tenant_id,shipment_id)
        REFERENCES buy_shipping_shipments(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE buy_shipping_cost_allocations (
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    amazon_order_item_id VARCHAR(80) NOT NULL,
    allocated_postage NUMERIC(19,4) NOT NULL CHECK (allocated_postage>=0),
    currency CHAR(3) NOT NULL,
    PRIMARY KEY (tenant_id,shipment_id,amazon_order_item_id),
    FOREIGN KEY (tenant_id,shipment_id)
        REFERENCES buy_shipping_shipments(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE buy_shipping_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_email VARCHAR(320),
    amazon_request_id VARCHAR(160),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,shipment_id)
        REFERENCES buy_shipping_shipments(tenant_id,id) ON DELETE CASCADE
);

CREATE TRIGGER merchant_ship_from_addresses_set_updated_at BEFORE UPDATE ON merchant_ship_from_addresses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER shipping_package_profiles_set_updated_at BEFORE UPDATE ON shipping_package_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER marketplace_sku_package_defaults_set_updated_at BEFORE UPDATE ON marketplace_sku_package_defaults
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER buy_shipping_shipments_set_updated_at BEFORE UPDATE ON buy_shipping_shipments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DO $$ DECLARE table_name TEXT; BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'merchant_ship_from_addresses','shipping_package_profiles','marketplace_sku_package_defaults',
    'buy_shipping_shipments','buy_shipping_shipment_items','buy_shipping_rate_offers',
    'shipping_label_artifacts','buy_shipping_cost_allocations','buy_shipping_audit_events'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name||'_isolation',table_name);
  END LOOP;
END $$;
