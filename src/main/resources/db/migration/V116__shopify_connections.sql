-- Local Shopify connection foundation only. No backfill, jobs or inventory changes.
ALTER TABLE marketplace_connections DROP CONSTRAINT marketplace_connections_channel_check;
ALTER TABLE marketplace_connections ADD CONSTRAINT marketplace_connections_channel_check
    CHECK(channel IN ('AMAZON','WALMART','SHOPIFY'));
ALTER TABLE marketplace_connections ADD CONSTRAINT marketplace_connections_shopify_identity_check
    CHECK(channel <> 'SHOPIFY' OR (
        seller_identifier = marketplace_identifier
        AND marketplace_identifier ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.myshopify\.com$'
    ));
-- Existing (tenant,channel,seller,marketplace) uniqueness, composite credential FK
-- and forced RLS remain in force. UTC is provisional until verified store timezone.
