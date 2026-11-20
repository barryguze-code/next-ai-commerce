CREATE TABLE account_catalog_product_images (
    tenant_id UUID NOT NULL,
    account_catalog_item_id UUID NOT NULL,
    image_bytes BYTEA NOT NULL,
    image_content_type VARCHAR(80) NOT NULL,
    original_filename VARCHAR(500),
    uploaded_by UUID REFERENCES app_users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,account_catalog_item_id),
    FOREIGN KEY (tenant_id,account_catalog_item_id)
        REFERENCES account_catalog_items(tenant_id,id) ON DELETE CASCADE
);

ALTER TABLE account_catalog_product_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_catalog_product_images FORCE ROW LEVEL SECURITY;
CREATE POLICY account_catalog_product_images_isolation ON account_catalog_product_images
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
