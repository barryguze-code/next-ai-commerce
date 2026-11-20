-- Freight, duty, and other shared charges belong to a receiving event, not a vendor profile.
ALTER TABLE vendors DROP COLUMN default_freight_rate;
ALTER TABLE vendors DROP COLUMN default_duty_rate;
ALTER TABLE vendor_catalog_offers DROP COLUMN freight_cost;
ALTER TABLE vendor_catalog_offers DROP COLUMN duty_cost;
ALTER TABLE vendor_catalog_offers DROP COLUMN other_landed_cost;
ALTER TABLE vendor_cost_history RENAME COLUMN landed_unit_cost TO net_unit_cost;

CREATE TABLE receiving_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    reference VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','MATCHING','READY','POSTED','CANCELLED')),
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    freight_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (freight_amount >= 0),
    duty_import_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (duty_import_amount >= 0),
    other_shared_cost NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (other_shared_cost >= 0),
    allocation_method VARCHAR(20) NOT NULL DEFAULT 'VALUE'
        CHECK (allocation_method IN ('VALUE','QUANTITY','WEIGHT','MANUAL')),
    notes TEXT,
    posted_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES app_users(id),
    posted_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK ((status = 'POSTED') = (posted_at IS NOT NULL))
);

CREATE TABLE receiving_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    receiving_session_id UUID NOT NULL,
    vendor_id UUID NOT NULL,
    document_type VARCHAR(24) NOT NULL CHECK (document_type IN ('INVOICE','PACKING_LIST')),
    document_number VARCHAR(160),
    document_date DATE,
    original_filename VARCHAR(500) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    subtotal NUMERIC(19,4),
    currency CHAR(3),
    document_freight_override NUMERIC(19,4) CHECK (document_freight_override >= 0),
    document_duty_override NUMERIC(19,4) CHECK (document_duty_override >= 0),
    status VARCHAR(24) NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED','EXTRACTED','MATCHED','READY','RECEIVED','REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, receiving_session_id) REFERENCES receiving_sessions(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES vendors(tenant_id, id),
    UNIQUE (tenant_id, receiving_session_id, file_sha256),
    UNIQUE (tenant_id, id)
);

CREATE TABLE receiving_document_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    receiving_document_id UUID NOT NULL,
    account_catalog_item_id UUID,
    vendor_item_code VARCHAR(160),
    source_description TEXT,
    ordered_quantity NUMERIC(16,4),
    received_quantity NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    invoice_unit_cost NUMERIC(19,4),
    currency CHAR(3),
    lot_number VARCHAR(120),
    expiration_date DATE,
    cost_decision VARCHAR(30) NOT NULL DEFAULT 'USE_ONCE'
        CHECK (cost_decision IN ('USE_ONCE','UPDATE_VENDOR_DEFAULT')),
    match_status VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED'
        CHECK (match_status IN ('UNMATCHED','MATCHED','NEW_PRODUCT','IGNORED')),
    raw_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, receiving_document_id) REFERENCES receiving_documents(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    UNIQUE (tenant_id, id)
);

CREATE TABLE receiving_cost_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    receiving_line_id UUID NOT NULL,
    freight_allocated NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (freight_allocated >= 0),
    duty_allocated NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (duty_allocated >= 0),
    other_cost_allocated NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (other_cost_allocated >= 0),
    final_landed_unit_cost NUMERIC(19,4) NOT NULL CHECK (final_landed_unit_cost >= 0),
    allocation_basis NUMERIC(19,6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, receiving_line_id) REFERENCES receiving_document_lines(tenant_id, id) ON DELETE CASCADE,
    UNIQUE (tenant_id, receiving_line_id)
);

CREATE TRIGGER receiving_sessions_set_updated_at BEFORE UPDATE ON receiving_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER receiving_documents_set_updated_at BEFORE UPDATE ON receiving_documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER receiving_document_lines_set_updated_at BEFORE UPDATE ON receiving_document_lines
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE receiving_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_document_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_cost_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE receiving_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE receiving_document_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE receiving_cost_allocations FORCE ROW LEVEL SECURITY;

CREATE POLICY receiving_sessions_isolation ON receiving_sessions USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY receiving_documents_isolation ON receiving_documents USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY receiving_document_lines_isolation ON receiving_document_lines USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY receiving_cost_allocations_isolation ON receiving_cost_allocations USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE INDEX receiving_sessions_tenant_status_idx ON receiving_sessions (tenant_id, status, created_at DESC);
CREATE INDEX receiving_documents_session_idx ON receiving_documents (tenant_id, receiving_session_id);
CREATE INDEX receiving_document_lines_document_idx ON receiving_document_lines (tenant_id, receiving_document_id);
CREATE INDEX receiving_document_lines_catalog_idx ON receiving_document_lines (tenant_id, account_catalog_item_id);
