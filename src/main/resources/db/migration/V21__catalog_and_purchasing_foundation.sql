-- Product identity is global; commercial terms and inventory are always tenant-owned.
CREATE TABLE global_catalog_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name VARCHAR(500) NOT NULL,
    brand VARCHAR(200),
    description TEXT,
    manufacturer_part_number VARCHAR(160),
    category VARCHAR(200),
    unit_of_measure VARCHAR(30) NOT NULL DEFAULT 'EA',
    units_per_case NUMERIC(12,4) NOT NULL DEFAULT 1 CHECK (units_per_case > 0),
    net_weight NUMERIC(14,4),
    weight_unit VARCHAR(12),
    length NUMERIC(14,4),
    width NUMERIC(14,4),
    height NUMERIC(14,4),
    dimension_unit VARCHAR(12),
    requires_expiration_date BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    source_tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    created_by UUID REFERENCES app_users(id),
    verified_by UUID REFERENCES app_users(id),
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE global_product_identifiers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    global_product_id UUID NOT NULL REFERENCES global_catalog_products(id) ON DELETE CASCADE,
    identifier_type VARCHAR(20) NOT NULL CHECK (identifier_type IN ('UPC','EAN','GTIN','ISBN','MPN')),
    identifier_value VARCHAR(80) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (identifier_type, identifier_value)
);

CREATE UNIQUE INDEX global_product_one_primary_identifier_idx
    ON global_product_identifiers (global_product_id) WHERE is_primary;
CREATE INDEX global_catalog_products_name_idx ON global_catalog_products (lower(canonical_name));
CREATE INDEX global_product_identifiers_product_idx ON global_product_identifiers (global_product_id);

CREATE TABLE account_catalog_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    global_product_id UUID NOT NULL REFERENCES global_catalog_products(id),
    account_sku VARCHAR(160),
    display_name VARCHAR(500),
    preferred_vendor_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','DISCONTINUED')),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, global_product_id),
    UNIQUE (tenant_id, account_sku),
    UNIQUE (tenant_id, id)
);

CREATE TABLE vendors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(240) NOT NULL,
    vendor_code VARCHAR(80),
    contact_email VARCHAR(320),
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    default_discount_rate NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (default_discount_rate BETWEEN 0 AND 100),
    default_freight_rate NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (default_freight_rate BETWEEN 0 AND 100),
    default_duty_rate NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (default_duty_rate BETWEEN 0 AND 100),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, vendor_code),
    UNIQUE (tenant_id, id)
);

ALTER TABLE account_catalog_items ADD CONSTRAINT account_catalog_preferred_vendor_fk
    FOREIGN KEY (tenant_id, preferred_vendor_id) REFERENCES vendors(tenant_id, id);

CREATE TABLE vendor_catalog_offers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    vendor_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    vendor_item_code VARCHAR(160),
    list_cost NUMERIC(19,4) NOT NULL CHECK (list_cost >= 0),
    discount_rate NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (discount_rate BETWEEN 0 AND 100),
    freight_cost NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (freight_cost >= 0),
    duty_cost NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (duty_cost >= 0),
    other_landed_cost NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (other_landed_cost >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    minimum_order_quantity NUMERIC(14,4) NOT NULL DEFAULT 1 CHECK (minimum_order_quantity > 0),
    effective_from DATE NOT NULL DEFAULT current_date,
    effective_to DATE,
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES vendors(tenant_id, id),
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, vendor_id, account_catalog_item_id, effective_from),
    UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX vendor_catalog_one_default_offer_idx
    ON vendor_catalog_offers (tenant_id, account_catalog_item_id) WHERE is_default AND effective_to IS NULL;

CREATE TABLE vendor_cost_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    vendor_offer_id UUID NOT NULL,
    source_type VARCHAR(30) NOT NULL CHECK (source_type IN ('MANUAL','CATALOG_IMPORT','INVOICE','PACKING_LIST','RECEIVING')),
    source_reference VARCHAR(240),
    list_cost NUMERIC(19,4) NOT NULL,
    discount_rate NUMERIC(7,4) NOT NULL DEFAULT 0,
    landed_unit_cost NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, vendor_offer_id) REFERENCES vendor_catalog_offers(tenant_id, id)
);

CREATE TABLE marketplace_sku_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    marketplace_sku VARCHAR(240) NOT NULL,
    asin VARCHAR(20),
    quantity_per_marketplace_unit NUMERIC(14,4) NOT NULL DEFAULT 1 CHECK (quantity_per_marketplace_unit > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id, id),
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    UNIQUE (tenant_id, marketplace_connection_id, marketplace_sku)
);

CREATE TABLE catalog_imports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    vendor_id UUID,
    original_filename VARCHAR(500) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED','MAPPED','VALIDATED','APPROVED','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    column_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    total_rows INTEGER NOT NULL DEFAULT 0,
    valid_rows INTEGER NOT NULL DEFAULT 0,
    rejected_rows INTEGER NOT NULL DEFAULT 0,
    uploaded_by UUID NOT NULL REFERENCES app_users(id),
    approved_by UUID REFERENCES app_users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, file_sha256),
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES vendors(tenant_id, id)
);

ALTER TABLE catalog_imports ADD CONSTRAINT catalog_imports_tenant_id_id_uk UNIQUE (tenant_id, id);

CREATE TABLE catalog_import_rows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    catalog_import_id UUID NOT NULL,
    row_number INTEGER NOT NULL,
    source_data JSONB NOT NULL,
    normalized_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (validation_status IN ('PENDING','VALID','WARNING','REJECTED','IMPORTED')),
    validation_messages JSONB NOT NULL DEFAULT '[]'::jsonb,
    global_product_id UUID REFERENCES global_catalog_products(id),
    account_catalog_item_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, catalog_import_id) REFERENCES catalog_imports(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    UNIQUE (tenant_id, catalog_import_id, row_number)
);

CREATE TABLE inventory_ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    marketplace_connection_id UUID,
    entry_type VARCHAR(40) NOT NULL,
    quantity NUMERIC(16,4) NOT NULL CHECK (quantity <> 0),
    lot_number VARCHAR(120),
    expiration_date DATE,
    unit_cost NUMERIC(19,4),
    currency CHAR(3),
    source_type VARCHAR(40) NOT NULL,
    source_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(240) NOT NULL,
    notes VARCHAR(500),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, account_catalog_item_id) REFERENCES account_catalog_items(tenant_id, id),
    FOREIGN KEY (tenant_id, marketplace_connection_id) REFERENCES marketplace_connections(tenant_id, id),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TRIGGER global_catalog_products_set_updated_at BEFORE UPDATE ON global_catalog_products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER account_catalog_items_set_updated_at BEFORE UPDATE ON account_catalog_items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER vendors_set_updated_at BEFORE UPDATE ON vendors
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER vendor_catalog_offers_set_updated_at BEFORE UPDATE ON vendor_catalog_offers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER marketplace_sku_mappings_set_updated_at BEFORE UPDATE ON marketplace_sku_mappings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER catalog_imports_set_updated_at BEFORE UPDATE ON catalog_imports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE account_catalog_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendors ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_catalog_offers ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_cost_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sku_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_import_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_catalog_items FORCE ROW LEVEL SECURITY;
ALTER TABLE vendors FORCE ROW LEVEL SECURITY;
ALTER TABLE vendor_catalog_offers FORCE ROW LEVEL SECURITY;
ALTER TABLE vendor_cost_history FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sku_mappings FORCE ROW LEVEL SECURITY;
ALTER TABLE catalog_imports FORCE ROW LEVEL SECURITY;
ALTER TABLE catalog_import_rows FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_ledger_entries FORCE ROW LEVEL SECURITY;

CREATE POLICY account_catalog_items_isolation ON account_catalog_items USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY vendors_isolation ON vendors USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY vendor_catalog_offers_isolation ON vendor_catalog_offers USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY vendor_cost_history_isolation ON vendor_cost_history USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY marketplace_sku_mappings_isolation ON marketplace_sku_mappings USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY catalog_imports_isolation ON catalog_imports USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY catalog_import_rows_isolation ON catalog_import_rows USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY inventory_ledger_entries_isolation ON inventory_ledger_entries USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE INDEX account_catalog_items_tenant_idx ON account_catalog_items (tenant_id, status);
CREATE INDEX vendors_tenant_idx ON vendors (tenant_id, status);
CREATE INDEX vendor_catalog_offers_item_idx ON vendor_catalog_offers (tenant_id, account_catalog_item_id);
CREATE INDEX vendor_cost_history_offer_idx ON vendor_cost_history (tenant_id, vendor_offer_id, effective_at DESC);
CREATE INDEX marketplace_sku_mappings_item_idx ON marketplace_sku_mappings (tenant_id, account_catalog_item_id);
CREATE INDEX catalog_imports_tenant_idx ON catalog_imports (tenant_id, created_at DESC);
CREATE INDEX catalog_import_rows_import_idx ON catalog_import_rows (tenant_id, catalog_import_id, row_number);
CREATE INDEX inventory_ledger_item_time_idx ON inventory_ledger_entries (tenant_id, account_catalog_item_id, occurred_at);
