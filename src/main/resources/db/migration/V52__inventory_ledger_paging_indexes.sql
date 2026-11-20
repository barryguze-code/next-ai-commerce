-- Keep ledger reads proportional to the visible page as the permanent audit history grows.

CREATE INDEX IF NOT EXISTS inventory_ledger_tenant_time_idx
    ON inventory_ledger_entries (tenant_id, occurred_at DESC, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS inventory_ledger_tenant_entry_type_idx
    ON inventory_ledger_entries (tenant_id, entry_type);
