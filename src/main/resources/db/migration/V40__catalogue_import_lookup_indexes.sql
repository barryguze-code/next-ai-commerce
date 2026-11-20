-- Catalogue imports match a vendor item code for every incoming row. Without this
-- expression index, each row scans all earlier offers and a large import becomes
-- progressively slower (quadratic behavior).
CREATE INDEX vendor_catalog_offer_normalized_code_idx
    ON vendor_catalog_offers (
        tenant_id,
        vendor_id,
        (upper(regexp_replace(vendor_item_code, '[^A-Za-z0-9]', '', 'g')))
    )
    WHERE effective_to IS NULL;

CREATE INDEX vendor_catalog_offer_current_item_idx
    ON vendor_catalog_offers (tenant_id, vendor_id, account_catalog_item_id)
    WHERE effective_to IS NULL;
