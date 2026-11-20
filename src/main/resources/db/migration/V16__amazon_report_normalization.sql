-- Report-specific persistence and version metadata for normalized Amazon imports.
ALTER TABLE amazon_source_documents
    ADD COLUMN normalized_at TIMESTAMPTZ,
    ADD COLUMN normalization_attempt_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN normalization_error VARCHAR(500),
    ADD COLUMN normalization_lease_owner VARCHAR(120),
    ADD COLUMN normalization_lease_expires_at TIMESTAMPTZ;

ALTER TABLE amazon_inventory_events
    ADD COLUMN row_fingerprint CHAR(64),
    ADD COLUMN occurrence_number INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN report_window_start DATE,
    ADD COLUMN report_window_end DATE,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN superseded_at TIMESTAMPTZ;

CREATE INDEX amazon_inventory_events_active_window_idx
    ON amazon_inventory_events(tenant_id, marketplace_connection_id, event_marketplace_date, active);

CREATE TABLE amazon_customer_shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    shipment_key VARCHAR(240) NOT NULL,
    shipment_id VARCHAR(120),
    shipment_item_id VARCHAR(120),
    amazon_order_id VARCHAR(40),
    amazon_order_item_id VARCHAR(80),
    seller_sku VARCHAR(240),
    asin VARCHAR(20),
    shipment_date TIMESTAMPTZ,
    shipment_marketplace_date DATE,
    quantity INTEGER NOT NULL DEFAULT 0,
    fulfillment_center VARCHAR(40),
    fulfillment_channel VARCHAR(40),
    sales_channel VARCHAR(100),
    currency CHAR(3),
    item_price NUMERIC(19,4),
    item_tax NUMERIC(19,4),
    shipping_price NUMERIC(19,4),
    shipping_tax NUMERIC(19,4),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, shipment_key)
);

CREATE TABLE amazon_fee_estimates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    marketplace_id VARCHAR(30) NOT NULL,
    seller_sku VARCHAR(240) NOT NULL,
    fnsku VARCHAR(40),
    asin VARCHAR(20),
    currency CHAR(3),
    estimated_fee_total NUMERIC(19,4),
    referral_fee NUMERIC(19,4),
    fulfillment_fee NUMERIC(19,4),
    effective_at TIMESTAMPTZ NOT NULL,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE,
    UNIQUE (tenant_id, marketplace_connection_id, marketplace_id, seller_sku, effective_at)
);

CREATE TABLE amazon_import_rejections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    source_document_id UUID REFERENCES amazon_source_documents(id) ON DELETE CASCADE,
    dataset VARCHAR(80) NOT NULL,
    row_number INTEGER,
    reason VARCHAR(500) NOT NULL,
    raw_row TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id)
        REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);

DO $$ DECLARE table_name TEXT; BEGIN
  FOREACH table_name IN ARRAY ARRAY['amazon_customer_shipments','amazon_fee_estimates','amazon_import_rejections']
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name || '_isolation', table_name);
  END LOOP;
END $$;
