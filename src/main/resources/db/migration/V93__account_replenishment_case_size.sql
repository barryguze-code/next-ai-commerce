ALTER TABLE account_catalog_items ADD COLUMN replenishment_units_per_case INTEGER
 CHECK (replenishment_units_per_case BETWEEN 1 AND 100000);
COMMENT ON COLUMN account_catalog_items.replenishment_units_per_case IS
 'Account purchasing case-size override. Does not change master packaging or mapped SKU component quantities.';
