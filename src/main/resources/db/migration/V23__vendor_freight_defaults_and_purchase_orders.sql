-- A vendor freight default is a currency amount used only to prefill a new receiving session.
-- Actual landed costs remain immutable on the receiving session/document.
ALTER TABLE vendors
    ADD COLUMN default_freight_amount NUMERIC(19,4) NOT NULL DEFAULT 0
        CHECK (default_freight_amount >= 0);

ALTER TABLE receiving_document_lines
    ADD COLUMN source_row_number INTEGER,
    ADD COLUMN source_identifier VARCHAR(100);

CREATE TABLE purchase_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    receiving_session_id UUID NOT NULL,
    receiving_document_id UUID NOT NULL,
    vendor_id UUID NOT NULL,
    po_number VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','APPROVED','RECEIVING','RECEIVED','CANCELLED')),
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    merchandise_total NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (merchandise_total >= 0),
    approved_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, receiving_session_id) REFERENCES receiving_sessions(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, receiving_document_id) REFERENCES receiving_documents(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES vendors(tenant_id, id),
    UNIQUE (tenant_id, po_number),
    UNIQUE (tenant_id, receiving_document_id),
    UNIQUE (tenant_id, id)
);

CREATE TABLE purchase_order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    purchase_order_id UUID NOT NULL,
    receiving_line_id UUID NOT NULL,
    account_catalog_item_id UUID,
    vendor_item_code VARCHAR(160),
    source_identifier VARCHAR(100),
    description TEXT NOT NULL,
    ordered_quantity NUMERIC(16,4) NOT NULL CHECK (ordered_quantity > 0),
    received_quantity NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    unit_cost NUMERIC(19,4) NOT NULL CHECK (unit_cost >= 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, receiving_line_id) REFERENCES receiving_document_lines(tenant_id, id),
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    UNIQUE (tenant_id, receiving_line_id),
    UNIQUE (tenant_id, id)
);

CREATE TRIGGER purchase_orders_set_updated_at BEFORE UPDATE ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER purchase_order_items_set_updated_at BEFORE UPDATE ON purchase_order_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE purchase_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_items FORCE ROW LEVEL SECURITY;
CREATE POLICY purchase_orders_isolation ON purchase_orders
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY purchase_order_items_isolation ON purchase_order_items
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE INDEX purchase_orders_session_idx ON purchase_orders (tenant_id, receiving_session_id, created_at);
CREATE INDEX purchase_order_items_order_idx ON purchase_order_items (tenant_id, purchase_order_id);
