CREATE TABLE global_catalog_product_images (
    global_product_id UUID PRIMARY KEY REFERENCES global_catalog_products(id),
    image_bytes BYTEA NOT NULL,
    image_content_type VARCHAR(80) NOT NULL,
    source_asin VARCHAR(32) NOT NULL,
    source_tenant_id UUID NOT NULL REFERENCES tenants(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
