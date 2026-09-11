ALTER TABLE inventory_shelf_life_policies
    RENAME COLUMN auto_remove_from_available TO auto_zero_marketplace_sellable;

COMMENT ON COLUMN inventory_shelf_life_policies.auto_zero_marketplace_sellable IS
    'When enabled, expiration batches at or below the sellable cutoff are excluded from calculated marketplace quantity. Physical inventory remains visible until a ledger movement removes it.';
