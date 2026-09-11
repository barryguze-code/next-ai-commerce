ALTER TABLE tenants ADD COLUMN logo_bytes BYTEA;
ALTER TABLE tenants ADD COLUMN logo_content_type VARCHAR(80);
ALTER TABLE tenants ADD COLUMN logo_updated_at TIMESTAMPTZ;
