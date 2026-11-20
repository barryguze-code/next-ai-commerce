-- Preserve receipt provenance: corrections append ledger movements, never erase receipts.
ALTER TABLE receiving_line_receipts
    ADD COLUMN voided_at timestamptz,
    ADD COLUMN voided_by uuid REFERENCES app_users(id),
    ADD COLUMN void_reason varchar(500);
ALTER TABLE receiving_documents
    ADD COLUMN closed_at timestamptz,
    ADD COLUMN closed_by uuid REFERENCES app_users(id),
    ADD COLUMN close_reason varchar(500),
    ADD COLUMN closed_partial boolean NOT NULL DEFAULT false,
    ADD COLUMN removed_at timestamptz,
    ADD COLUMN removed_by uuid REFERENCES app_users(id);

-- Archived, never-received documents retain provenance without blocking a corrected upload.
DROP INDEX receiving_documents_vendor_number_idx;
DROP INDEX receiving_documents_vendor_file_idx;
CREATE UNIQUE INDEX receiving_documents_vendor_number_idx
    ON receiving_documents(tenant_id,vendor_id,document_type,document_number)
    WHERE document_number IS NOT NULL AND removed_at IS NULL;
CREATE UNIQUE INDEX receiving_documents_vendor_file_idx
    ON receiving_documents(tenant_id,vendor_id,file_sha256) WHERE removed_at IS NULL;
DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint
        WHERE conrelid='receiving_documents'::regclass AND contype='u'
          AND pg_get_constraintdef(oid)='UNIQUE (tenant_id, receiving_session_id, file_sha256)'
    LOOP
        EXECUTE format('ALTER TABLE receiving_documents DROP CONSTRAINT %I',constraint_name);
    END LOOP;
END $$;
CREATE UNIQUE INDEX receiving_document_session_file_active_idx
    ON receiving_documents(tenant_id,receiving_session_id,file_sha256) WHERE removed_at IS NULL;

CREATE TABLE receiving_audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    document_id uuid NOT NULL,
    receipt_id uuid,
    action varchar(40) NOT NULL,
    reason varchar(500) NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, document_id) REFERENCES receiving_documents(tenant_id,id)
);
ALTER TABLE receiving_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY receiving_audit_isolation ON receiving_audit_events
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE INDEX receiving_audit_document_idx ON receiving_audit_events(tenant_id,document_id,created_at DESC);
CREATE INDEX receiving_receipt_active_idx ON receiving_line_receipts(tenant_id,purchase_order_item_id,received_at DESC) WHERE voided_at IS NULL;
CREATE INDEX receiving_po_document_idx ON purchase_orders(tenant_id,receiving_document_id);
CREATE INDEX receiving_po_session_idx ON purchase_orders(tenant_id,receiving_session_id);
CREATE INDEX receiving_ledger_source_idx ON inventory_ledger_entries(tenant_id,source_type,source_id);
CREATE INDEX receiving_document_work_idx ON receiving_documents(tenant_id,created_at DESC) WHERE removed_at IS NULL;
CREATE INDEX receiving_reservation_history_idx ON order_inventory_reservations
    (tenant_id,account_catalog_item_id,location_id,expiration_date,status);

-- Retried clicks return the original successful outcome instead of adding stock twice.
CREATE TABLE receiving_commands (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    request_id uuid NOT NULL,
    operation varchar(40) NOT NULL,
    target_id uuid NOT NULL,
    fingerprint varchar(64) NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,request_id)
);
ALTER TABLE receiving_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_commands FORCE ROW LEVEL SECURITY;
CREATE POLICY receiving_commands_isolation ON receiving_commands
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
