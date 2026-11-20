-- Make Amazon catalogue image enrichment finite, observable, and safe to retry.
ALTER TABLE amazon_listings
    ADD COLUMN image_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN image_source_url TEXT,
    ADD COLUMN image_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN image_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN image_failure_code VARCHAR(80),
    ADD COLUMN image_failure_message TEXT,
    ADD COLUMN amazon_removed_at TIMESTAMPTZ;

UPDATE amazon_listings
SET image_status='AVAILABLE',image_source_url=image_url,image_next_attempt_at=NULL
WHERE image_url IS NOT NULL;

ALTER TABLE amazon_listings
    ADD CONSTRAINT amazon_listings_image_status_check
    CHECK (image_status IN ('PENDING','RETRY','AVAILABLE','NO_IMAGE','NOT_FOUND'));

ALTER TABLE amazon_listings DROP CONSTRAINT IF EXISTS amazon_listings_platform_status_check;
ALTER TABLE amazon_listings
    ADD CONSTRAINT amazon_listings_platform_status_check
    CHECK (platform_status IN ('VISIBLE','DELETED','REMOVED'));

DROP INDEX IF EXISTS amazon_listings_missing_image_idx;
CREATE INDEX amazon_listings_missing_image_idx
    ON amazon_listings (tenant_id,image_next_attempt_at,last_seen_at DESC)
    WHERE image_url IS NULL AND asin IS NOT NULL AND image_status IN ('PENDING','RETRY');
