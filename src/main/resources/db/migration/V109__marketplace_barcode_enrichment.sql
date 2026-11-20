ALTER TABLE amazon_listings
 ADD COLUMN catalog_barcodes jsonb NOT NULL DEFAULT '[]',
 ADD COLUMN catalog_barcodes_asin text,
 ADD COLUMN catalog_barcodes_checked_at timestamptz,
 ADD COLUMN catalog_barcodes_retry_at timestamptz;
