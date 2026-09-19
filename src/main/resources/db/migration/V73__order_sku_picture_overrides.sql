-- Pictures belong to a store SKU, not to one of a bundle's catalogue components.
CREATE TABLE order_sku_pictures (
 tenant_id UUID NOT NULL,
 marketplace_connection_id UUID NOT NULL,
 seller_sku TEXT NOT NULL,
 image_bytes BYTEA,
 content_type VARCHAR(80),
 source_url TEXT,
 updated_by TEXT NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id,marketplace_connection_id,seller_sku),
 FOREIGN KEY (tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id),
 CHECK ((image_bytes IS NOT NULL AND content_type IN ('image/png','image/jpeg') AND source_url IS NULL)
     OR (image_bytes IS NULL AND content_type IS NULL AND source_url IS NOT NULL))
);
ALTER TABLE order_sku_pictures ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_sku_pictures FORCE ROW LEVEL SECURITY;
CREATE POLICY order_sku_pictures_isolation ON order_sku_pictures
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
