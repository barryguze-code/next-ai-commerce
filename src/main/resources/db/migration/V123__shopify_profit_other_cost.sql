-- Account-owned planning defaults, separate from imported Shopify observations.
-- NULL means use the USD 1 default; explicit zero is a saved override.
ALTER TABLE shopify_variants
    ADD COLUMN profit_other_cost NUMERIC(19,2),
    ADD COLUMN profit_other_currency CHAR(3),
    ADD COLUMN profit_other_updated_by TEXT,
    ADD COLUMN profit_other_updated_at TIMESTAMPTZ,
    ADD CONSTRAINT shopify_profit_other_valid CHECK (
        (profit_other_cost IS NULL AND profit_other_currency IS NULL) OR
        (profit_other_cost IS NOT NULL AND profit_other_cost >= 0 AND profit_other_cost <= 100000
         AND profit_other_currency IS NOT NULL AND profit_other_currency ~ '^[A-Z]{3}$'));
-- Existing forced tenant RLS and scoped variant/connection keys remain in force.
-- No inventory, source-document, order, or historical-cost changes.
