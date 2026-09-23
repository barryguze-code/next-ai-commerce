CREATE TABLE record_picture_overrides (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 entity_type VARCHAR(20) NOT NULL CHECK (entity_type IN ('VENDOR','SHIPMENT')),
 entity_id UUID NOT NULL,
 content_type VARCHAR(40) NOT NULL CHECK (content_type IN ('image/png','image/jpeg')),
 image_bytes BYTEA NOT NULL CHECK (octet_length(image_bytes) BETWEEN 1 AND 5000000),
 updated_by TEXT NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,entity_type,entity_id)
);
ALTER TABLE record_picture_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE record_picture_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY record_picture_overrides_isolation ON record_picture_overrides
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
