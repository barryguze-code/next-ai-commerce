-- Amazon source documents and normalized operational data. Every record is account/store scoped.
CREATE TABLE amazon_report_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, sync_job_id UUID REFERENCES marketplace_sync_jobs(id) ON DELETE SET NULL,
    report_type VARCHAR(120) NOT NULL, amazon_report_id VARCHAR(120), amazon_document_id VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED', data_start_time TIMESTAMPTZ, data_end_time TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ, processing_completed_at TIMESTAMPTZ,
    compression_algorithm VARCHAR(20), error_message VARCHAR(500), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, amazon_report_id)
);

CREATE TABLE amazon_source_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, sync_job_id UUID REFERENCES marketplace_sync_jobs(id) ON DELETE SET NULL,
    report_request_id UUID REFERENCES amazon_report_requests(id) ON DELETE SET NULL,
    source_type VARCHAR(80) NOT NULL, amazon_request_id VARCHAR(160), source_key VARCHAR(240),
    content_type VARCHAR(100), content_encoding VARCHAR(30), sha256 CHAR(64) NOT NULL,
    payload JSONB, text_payload TEXT, record_count BIGINT, received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, source_type, sha256)
);

CREATE TABLE amazon_listings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, marketplace_id VARCHAR(30) NOT NULL,
    seller_sku VARCHAR(240) NOT NULL, asin VARCHAR(20), product_id VARCHAR(80), product_id_type VARCHAR(30),
    item_name TEXT, listing_id VARCHAR(160), listing_status VARCHAR(40), condition_type VARCHAR(60),
    fulfillment_channel VARCHAR(30), merchant_shipping_group VARCHAR(160),
    price NUMERIC(19,4), currency CHAR(3), quantity INTEGER, pending_quantity INTEGER,
    open_date TIMESTAMPTZ, image_url TEXT, raw_attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, marketplace_id, seller_sku)
);

CREATE TABLE amazon_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, marketplace_id VARCHAR(30) NOT NULL,
    amazon_order_id VARCHAR(40) NOT NULL, purchase_date TIMESTAMPTZ, last_update_date TIMESTAMPTZ,
    order_status VARCHAR(40), fulfillment_channel VARCHAR(20), sales_channel VARCHAR(100),
    ship_service_level VARCHAR(100), order_type VARCHAR(60), currency CHAR(3), order_total NUMERIC(19,4),
    items_shipped INTEGER, items_unshipped INTEGER, is_business_order BOOLEAN, is_prime BOOLEAN,
    earliest_ship_date TIMESTAMPTZ, latest_ship_date TIMESTAMPTZ,
    earliest_delivery_date TIMESTAMPTZ, latest_delivery_date TIMESTAMPTZ,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb, source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, amazon_order_id)
);

CREATE TABLE amazon_order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, amazon_order_id VARCHAR(40) NOT NULL,
    amazon_order_item_id VARCHAR(80) NOT NULL, seller_sku VARCHAR(240), asin VARCHAR(20), title TEXT,
    quantity_ordered INTEGER NOT NULL DEFAULT 0, quantity_shipped INTEGER NOT NULL DEFAULT 0,
    item_price NUMERIC(19,4), item_tax NUMERIC(19,4), shipping_price NUMERIC(19,4), shipping_tax NUMERIC(19,4),
    promotion_discount NUMERIC(19,4), currency CHAR(3), condition_id VARCHAR(60),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id, amazon_order_id)
        REFERENCES amazon_orders(tenant_id, marketplace_connection_id, amazon_order_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, amazon_order_item_id)
);

CREATE TABLE amazon_inventory_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, marketplace_id VARCHAR(30) NOT NULL,
    seller_sku VARCHAR(240) NOT NULL, fnsku VARCHAR(40), asin VARCHAR(20), condition_type VARCHAR(60),
    fulfillable_quantity INTEGER NOT NULL DEFAULT 0, inbound_working_quantity INTEGER NOT NULL DEFAULT 0,
    inbound_shipped_quantity INTEGER NOT NULL DEFAULT 0, inbound_receiving_quantity INTEGER NOT NULL DEFAULT 0,
    reserved_quantity INTEGER NOT NULL DEFAULT 0, unfulfillable_quantity INTEGER NOT NULL DEFAULT 0,
    researching_quantity INTEGER NOT NULL DEFAULT 0, total_quantity INTEGER NOT NULL DEFAULT 0,
    snapshot_at TIMESTAMPTZ NOT NULL, raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, marketplace_id, seller_sku, snapshot_at)
);

CREATE TABLE amazon_inventory_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, marketplace_id VARCHAR(30) NOT NULL,
    event_key VARCHAR(240) NOT NULL, event_date TIMESTAMPTZ NOT NULL, event_type VARCHAR(100) NOT NULL,
    seller_sku VARCHAR(240), fnsku VARCHAR(40), asin VARCHAR(20), fulfillment_center VARCHAR(40),
    disposition VARCHAR(80), reference_id VARCHAR(160), quantity INTEGER NOT NULL,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb, source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, event_key)
);

CREATE TABLE amazon_fba_shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, shipment_id VARCHAR(80) NOT NULL, shipment_name VARCHAR(240),
    shipment_status VARCHAR(60), destination_fulfillment_center VARCHAR(40), shipment_type VARCHAR(60),
    label_prep_type VARCHAR(60), created_date TIMESTAMPTZ, updated_date TIMESTAMPTZ,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, shipment_id)
);

CREATE TABLE amazon_fba_shipment_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, shipment_id VARCHAR(80) NOT NULL,
    seller_sku VARCHAR(240) NOT NULL, fnsku VARCHAR(40), quantity_shipped INTEGER NOT NULL DEFAULT 0,
    quantity_received INTEGER NOT NULL DEFAULT 0, quantity_in_case INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id, shipment_id)
        REFERENCES amazon_fba_shipments(tenant_id, marketplace_connection_id, shipment_id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, shipment_id, seller_sku)
);

CREATE TABLE amazon_returns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, return_key VARCHAR(240) NOT NULL,
    amazon_order_id VARCHAR(40), amazon_order_item_id VARCHAR(80), seller_sku VARCHAR(240), fnsku VARCHAR(40), asin VARCHAR(20),
    return_date TIMESTAMPTZ, quantity INTEGER NOT NULL DEFAULT 0, fulfillment_center VARCHAR(40),
    detailed_disposition VARCHAR(120), reason VARCHAR(240), status VARCHAR(80), license_plate_number VARCHAR(120),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb, source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, return_key)
);

CREATE TABLE amazon_financial_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, transaction_key VARCHAR(240) NOT NULL,
    transaction_type VARCHAR(100) NOT NULL, posted_date TIMESTAMPTZ, amazon_order_id VARCHAR(40),
    shipment_id VARCHAR(100), seller_sku VARCHAR(240), description TEXT, quantity INTEGER,
    amount NUMERIC(19,4), currency CHAR(3), marketplace_id VARCHAR(30),
    breakdown JSONB NOT NULL DEFAULT '{}'::jsonb, raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, transaction_key)
);

CREATE TABLE amazon_settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, settlement_id VARCHAR(120) NOT NULL,
    settlement_start TIMESTAMPTZ, settlement_end TIMESTAMPTZ, deposit_date TIMESTAMPTZ,
    total_amount NUMERIC(19,4), currency CHAR(3), status VARCHAR(60),
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, settlement_id)
);

CREATE TABLE amazon_reimbursements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, reimbursement_key VARCHAR(240) NOT NULL,
    reimbursement_id VARCHAR(120), approval_date TIMESTAMPTZ, case_id VARCHAR(120), amazon_order_id VARCHAR(40),
    reason VARCHAR(160), seller_sku VARCHAR(240), fnsku VARCHAR(40), asin VARCHAR(20), condition_type VARCHAR(60),
    quantity_cash INTEGER NOT NULL DEFAULT 0, quantity_inventory INTEGER NOT NULL DEFAULT 0,
    amount_per_unit NUMERIC(19,4), amount_total NUMERIC(19,4), currency CHAR(3),
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, reimbursement_key)
);

CREATE TABLE reimbursement_cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, case_type VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'CANDIDATE', seller_sku VARCHAR(240), fnsku VARCHAR(40), asin VARCHAR(20),
    amazon_order_id VARCHAR(40), shipment_id VARCHAR(100), detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    eligible_at TIMESTAMPTZ, expected_amount NUMERIC(19,4), recovered_amount NUMERIC(19,4), currency CHAR(3),
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb, amazon_case_id VARCHAR(120), resolution_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

CREATE TABLE amazon_oos_periods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, marketplace_id VARCHAR(30) NOT NULL, seller_sku VARCHAR(240) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ, source VARCHAR(40) NOT NULL DEFAULT 'SYNC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    CHECK (ended_at IS NULL OR ended_at > started_at)
);
CREATE UNIQUE INDEX amazon_oos_open_period_idx ON amazon_oos_periods
    (tenant_id, marketplace_connection_id, marketplace_id, seller_sku) WHERE ended_at IS NULL;

CREATE TABLE amazon_sync_watermarks (
    tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL, dataset VARCHAR(80) NOT NULL,
    high_watermark TIMESTAMPTZ, next_token TEXT, last_success_at TIMESTAMPTZ,
    last_reconciliation_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, marketplace_connection_id, dataset),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

CREATE INDEX amazon_listings_asin_idx ON amazon_listings(tenant_id, marketplace_connection_id, asin);
CREATE INDEX amazon_orders_purchase_idx ON amazon_orders(tenant_id, marketplace_connection_id, purchase_date DESC);
CREATE INDEX amazon_order_items_sku_idx ON amazon_order_items(tenant_id, marketplace_connection_id, seller_sku);
CREATE INDEX amazon_inventory_events_lookup_idx ON amazon_inventory_events(tenant_id, marketplace_connection_id, seller_sku, event_date DESC);
CREATE INDEX amazon_financial_order_idx ON amazon_financial_transactions(tenant_id, marketplace_connection_id, amazon_order_id);
CREATE INDEX reimbursement_cases_status_idx ON reimbursement_cases(tenant_id, marketplace_connection_id, status, detected_at);

DO $$ DECLARE table_name TEXT; BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'amazon_report_requests','amazon_source_documents','amazon_listings','amazon_orders','amazon_order_items',
    'amazon_inventory_snapshots','amazon_inventory_events','amazon_fba_shipments','amazon_fba_shipment_items',
    'amazon_returns','amazon_financial_transactions','amazon_settlements','amazon_reimbursements',
    'reimbursement_cases','amazon_oos_periods','amazon_sync_watermarks'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name || '_isolation', table_name);
  END LOOP;
END $$;
