ALTER TABLE purchase_order_items
    ADD COLUMN deposit_fee_per_unit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (deposit_fee_per_unit >= 0),
    ADD COLUMN other_fee_per_unit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (other_fee_per_unit >= 0);

ALTER TABLE inventory_ledger_entries
    ADD COLUMN deposit_fee_per_unit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (deposit_fee_per_unit >= 0),
    ADD COLUMN other_fee_per_unit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (other_fee_per_unit >= 0);

COMMENT ON COLUMN purchase_order_items.deposit_fee_per_unit IS 'Invoice-level bottle or recycling deposit charged per received each.';
COMMENT ON COLUMN purchase_order_items.other_fee_per_unit IS 'Other invoice line fee charged per received each.';
