ALTER TABLE purchase_order_items
    ADD COLUMN invoice_unit VARCHAR(12) NOT NULL DEFAULT 'CASE'
        CHECK (invoice_unit IN ('CASE','EACH')),
    ADD COLUMN units_per_case NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (units_per_case > 0),
    ADD COLUMN discrepancy_status VARCHAR(30) NOT NULL DEFAULT 'NONE'
        CHECK (discrepancy_status IN ('NONE','SHORT_SHIPPED','DAMAGED','SOON_EXPIRED','EXPIRED','OTHER')),
    ADD COLUMN discrepancy_quantity NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (discrepancy_quantity >= 0),
    ADD COLUMN discrepancy_notes VARCHAR(500),
    ADD COLUMN match_source VARCHAR(30) NOT NULL DEFAULT 'NEW_PRODUCT'
        CHECK (match_source IN ('VENDOR_ITEM','IDENTIFIER','ACCOUNT_SKU','NEW_PRODUCT','MANUAL'));

ALTER TABLE receiving_document_lines
    ADD COLUMN source_units_per_case NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (source_units_per_case > 0),
    ADD COLUMN source_invoice_unit VARCHAR(12) NOT NULL DEFAULT 'CASE'
        CHECK (source_invoice_unit IN ('CASE','EACH'));

CREATE TABLE receiving_line_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    purchase_order_item_id UUID NOT NULL,
    case_quantity NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (case_quantity >= 0),
    each_quantity NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (each_quantity >= 0),
    units_per_case NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (units_per_case > 0),
    total_each_quantity NUMERIC(16,4) GENERATED ALWAYS AS
        ((case_quantity * units_per_case) + each_quantity) STORED,
    expiration_date DATE,
    disposition VARCHAR(24) NOT NULL DEFAULT 'SELLABLE'
        CHECK (disposition IN ('SELLABLE','DAMAGED','SOON_EXPIRED','EXPIRED')),
    notes VARCHAR(500),
    received_by UUID NOT NULL REFERENCES app_users(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, purchase_order_item_id) REFERENCES purchase_order_items(tenant_id, id) ON DELETE CASCADE,
    UNIQUE (tenant_id, id),
    CHECK (total_each_quantity > 0)
);

CREATE TABLE vendor_credit_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    receiving_session_id UUID NOT NULL,
    purchase_order_item_id UUID NOT NULL,
    vendor_id UUID NOT NULL,
    reason VARCHAR(30) NOT NULL
        CHECK (reason IN ('SHORT_SHIPPED','DAMAGED','SOON_EXPIRED','EXPIRED','OTHER')),
    quantity NUMERIC(16,4) NOT NULL CHECK (quantity > 0),
    unit_cost NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','SUBMITTED','CREDITED','DENIED','CANCELLED')),
    notes VARCHAR(500),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, receiving_session_id) REFERENCES receiving_sessions(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, purchase_order_item_id) REFERENCES purchase_order_items(tenant_id, id),
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES vendors(tenant_id, id),
    UNIQUE (tenant_id, purchase_order_item_id),
    UNIQUE (tenant_id, id)
);

CREATE TRIGGER vendor_credit_requests_set_updated_at BEFORE UPDATE ON vendor_credit_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE receiving_line_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_credit_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_line_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE vendor_credit_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY receiving_line_receipts_isolation ON receiving_line_receipts
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY vendor_credit_requests_isolation ON vendor_credit_requests
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE INDEX receiving_line_receipts_item_idx ON receiving_line_receipts (tenant_id, purchase_order_item_id, received_at);
CREATE INDEX vendor_credit_requests_session_idx ON vendor_credit_requests (tenant_id, receiving_session_id, status);
