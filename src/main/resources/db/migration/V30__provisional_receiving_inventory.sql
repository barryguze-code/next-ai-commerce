-- Physical sellable receipts are available immediately; receiving completion finalizes landed cost.
ALTER TABLE inventory_ledger_entries
    ADD COLUMN cost_status VARCHAR(20) NOT NULL DEFAULT 'FINAL'
        CHECK (cost_status IN ('PROVISIONAL','FINAL'));

CREATE INDEX inventory_ledger_receiving_cost_status_idx
    ON inventory_ledger_entries (tenant_id, source_type, cost_status)
    WHERE source_type='RECEIVING';

