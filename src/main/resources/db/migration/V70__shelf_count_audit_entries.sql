-- Zero-value entries document observations, never manufacture stock or cost.
ALTER TABLE inventory_ledger_entries DROP CONSTRAINT inventory_ledger_entries_quantity_check;
ALTER TABLE inventory_ledger_entries ADD CONSTRAINT inventory_ledger_entries_quantity_check
    CHECK (quantity <> 0 OR source_type='PHYSICAL_COUNT' OR entry_type='SHIPMENT_UNRECORDED');
