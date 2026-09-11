-- Operational receiving states and throttled Amazon main-image enrichment.
ALTER TABLE purchase_order_items
    DROP CONSTRAINT IF EXISTS purchase_order_items_discrepancy_status_check;
ALTER TABLE purchase_order_items
    ADD CONSTRAINT purchase_order_items_discrepancy_status_check
    CHECK (discrepancy_status IN
        ('NONE','SHORT_SHIPPED','DAMAGED','MISPICKED','SOON_EXPIRED','EXPIRED','OVER_SHIPPED','OTHER'));

ALTER TABLE receiving_line_receipts
    DROP CONSTRAINT IF EXISTS receiving_line_receipts_disposition_check;
ALTER TABLE receiving_line_receipts
    ADD CONSTRAINT receiving_line_receipts_disposition_check
    CHECK (disposition IN ('SELLABLE','DAMAGED','MISPICKED','SOON_EXPIRED','EXPIRED','OVER_SHIPPED'));

ALTER TABLE vendor_credit_requests
    DROP CONSTRAINT IF EXISTS vendor_credit_requests_reason_check;
ALTER TABLE vendor_credit_requests
    ADD CONSTRAINT vendor_credit_requests_reason_check
    CHECK (reason IN ('SHORT_SHIPPED','DAMAGED','MISPICKED','SOON_EXPIRED','EXPIRED','OTHER'));

ALTER TABLE amazon_listings
    ADD COLUMN IF NOT EXISTS image_checked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS amazon_listings_missing_image_idx
    ON amazon_listings (tenant_id, image_checked_at, last_seen_at DESC)
    WHERE image_url IS NULL AND asin IS NOT NULL;

-- Repair untouched draft lines that previously inherited a catalogue pack when the document had none.
UPDATE purchase_order_items item
SET units_per_case=line.source_units_per_case,updated_at=now()
FROM receiving_document_lines line,purchase_orders po,receiving_sessions session
WHERE item.tenant_id=line.tenant_id AND item.receiving_line_id=line.id
  AND po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
  AND session.tenant_id=po.tenant_id AND session.id=po.receiving_session_id
  AND session.status NOT IN ('POSTED','CANCELLED')
  AND NOT EXISTS (SELECT 1 FROM receiving_line_receipts receipt
      WHERE receipt.tenant_id=item.tenant_id AND receipt.purchase_order_item_id=item.id);
