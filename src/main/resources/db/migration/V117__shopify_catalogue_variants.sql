-- Catalogue observations only. No jobs/backfill or inventory ledger/quantity changes.
ALTER TABLE marketplace_connections ADD CONSTRAINT marketplace_connection_provider_identity
    UNIQUE(tenant_id,id,channel);
ALTER TABLE marketplace_sync_runs ADD CONSTRAINT marketplace_run_scoped_identity
    UNIQUE(tenant_id,marketplace_connection_id,id);

CREATE TABLE shopify_source_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL, channel TEXT NOT NULL DEFAULT 'SHOPIFY' CHECK(channel='SHOPIFY'),
    sync_job_id UUID NOT NULL, api_version TEXT NOT NULL, query_revision TEXT NOT NULL,
    request_cursor TEXT, sha256 CHAR(64) NOT NULL, response_body TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,marketplace_connection_id,id),
    UNIQUE(tenant_id,marketplace_connection_id,sync_job_id,sha256),
    FOREIGN KEY(tenant_id,marketplace_connection_id,channel) REFERENCES marketplace_connections(tenant_id,id,channel),
    FOREIGN KEY(tenant_id,marketplace_connection_id,sync_job_id) REFERENCES marketplace_sync_jobs(tenant_id,marketplace_connection_id,id)
);
CREATE TABLE shopify_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL,
    channel TEXT NOT NULL DEFAULT 'SHOPIFY' CHECK(channel='SHOPIFY'),
    product_gid TEXT NOT NULL CHECK(product_gid ~ '^gid://shopify/Product/[0-9]+$'),
    title TEXT NOT NULL, vendor TEXT, product_status TEXT NOT NULL,
    storefront_url TEXT, image_url TEXT, source_updated_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), source_document_id UUID NOT NULL, observation_hash CHAR(64) NOT NULL,
    UNIQUE(tenant_id,marketplace_connection_id,id), UNIQUE(tenant_id,marketplace_connection_id,product_gid),
    FOREIGN KEY(tenant_id,marketplace_connection_id,channel) REFERENCES marketplace_connections(tenant_id,id,channel),
    FOREIGN KEY(tenant_id,marketplace_connection_id,source_document_id) REFERENCES shopify_source_documents(tenant_id,marketplace_connection_id,id)
);
CREATE TABLE shopify_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, marketplace_connection_id UUID NOT NULL,
    product_id UUID NOT NULL, variant_gid TEXT NOT NULL CHECK(variant_gid ~ '^gid://shopify/ProductVariant/[0-9]+$'),
    inventory_item_gid TEXT NOT NULL CHECK(inventory_item_gid ~ '^gid://shopify/InventoryItem/[0-9]+$'),
    seller_sku TEXT, title TEXT NOT NULL, selected_options JSONB NOT NULL DEFAULT '[]'::jsonb,
    barcodes JSONB NOT NULL DEFAULT '[]'::jsonb, barcodes_complete BOOLEAN NOT NULL,
    price NUMERIC NOT NULL CHECK(price>=0), currency CHAR(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    image_url TEXT, source_updated_at TIMESTAMPTZ NOT NULL, observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source_document_id UUID NOT NULL, last_seen_run UUID NOT NULL, observation_hash CHAR(64) NOT NULL, mapping_hash CHAR(64) NOT NULL,
    UNIQUE(tenant_id,marketplace_connection_id,id), UNIQUE(tenant_id,marketplace_connection_id,variant_gid),
    FOREIGN KEY(tenant_id,marketplace_connection_id,product_id) REFERENCES shopify_products(tenant_id,marketplace_connection_id,id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,source_document_id) REFERENCES shopify_source_documents(tenant_id,marketplace_connection_id,id),
    FOREIGN KEY(tenant_id,marketplace_connection_id,last_seen_run) REFERENCES marketplace_sync_runs(tenant_id,marketplace_connection_id,id),
    CHECK(jsonb_typeof(selected_options)='array' AND jsonb_typeof(barcodes)='array')
);
-- Keep existing Amazon UNIQUE/ON CONFLICT semantics. Shopify raw SKU stays in its source row.
ALTER TABLE marketplace_sku_mappings ADD COLUMN shopify_variant_id UUID;
ALTER TABLE marketplace_sku_mappings ADD COLUMN shopify_mapping_hash CHAR(64);
ALTER TABLE marketplace_sku_mappings ALTER COLUMN marketplace_sku DROP NOT NULL;
ALTER TABLE marketplace_sku_mappings ADD CONSTRAINT mapping_provider_identity CHECK(
    (shopify_variant_id IS NULL AND marketplace_sku IS NOT NULL AND shopify_mapping_hash IS NULL) OR
    (shopify_variant_id IS NOT NULL AND marketplace_sku IS NULL AND asin IS NULL));
ALTER TABLE marketplace_sku_mappings ADD CONSTRAINT mapping_shopify_variant_scope
    FOREIGN KEY(tenant_id,marketplace_connection_id,shopify_variant_id) REFERENCES shopify_variants(tenant_id,marketplace_connection_id,id);
CREATE UNIQUE INDEX mapping_shopify_variant_identity ON marketplace_sku_mappings(tenant_id,marketplace_connection_id,shopify_variant_id)
    WHERE shopify_variant_id IS NOT NULL;

CREATE FUNCTION validate_mapping_provider() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.shopify_variant_id IS NULL AND EXISTS(SELECT 1 FROM marketplace_connections
        WHERE tenant_id=NEW.tenant_id AND id=NEW.marketplace_connection_id AND channel='SHOPIFY') THEN
        RAISE EXCEPTION 'Shopify mapping requires a scoped variant identity';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER mapping_provider_validation BEFORE INSERT OR UPDATE ON marketplace_sku_mappings
    FOR EACH ROW EXECUTE FUNCTION validate_mapping_provider();

CREATE INDEX shopify_variant_sku ON shopify_variants(tenant_id,marketplace_connection_id,seller_sku);
CREATE INDEX shopify_variant_product ON shopify_variants(tenant_id,marketplace_connection_id,product_id);
CREATE UNIQUE INDEX shopify_one_active_catalogue_job ON marketplace_sync_jobs(tenant_id,marketplace_connection_id)
    WHERE job_type='SHOPIFY_CATALOG_VARIANTS' AND status IN ('QUEUED','WAITING','RUNNING');
CREATE FUNCTION preserve_shopify_source_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Shopify source documents are append-only';
END;
$$;
CREATE TRIGGER shopify_source_append_only BEFORE UPDATE OR DELETE ON shopify_source_documents
    FOR EACH ROW EXECUTE FUNCTION preserve_shopify_source_document();

ALTER TABLE shopify_source_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_source_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY shopify_source_scope ON shopify_source_documents
    USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE shopify_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_products FORCE ROW LEVEL SECURITY;
CREATE POLICY shopify_product_scope ON shopify_products
    USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE shopify_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_variants FORCE ROW LEVEL SECURITY;
CREATE POLICY shopify_variant_scope ON shopify_variants
    USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
