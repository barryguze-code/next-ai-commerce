-- Preserve supplier document identity and allow more than one discrepancy reason per PO line.
ALTER TABLE vendor_credit_requests
    DROP CONSTRAINT IF EXISTS vendor_credit_requests_tenant_id_purchase_order_item_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS vendor_credit_requests_item_reason_idx
    ON vendor_credit_requests (tenant_id, purchase_order_item_id, reason);

CREATE UNIQUE INDEX IF NOT EXISTS receiving_documents_vendor_number_idx
    ON receiving_documents (tenant_id, vendor_id, document_type, document_number)
    WHERE document_number IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS receiving_documents_vendor_file_idx
    ON receiving_documents (tenant_id, vendor_id, file_sha256);
