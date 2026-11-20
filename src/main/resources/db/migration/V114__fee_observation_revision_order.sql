-- Deterministic local receipt/revision order, independent of wall-clock adjustments.
-- These are new fee evidence tables only; Amazon source/inventory/order records are untouched.
ALTER TABLE amazon_order_fee_charge_observations ADD COLUMN observation_seq bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE amazon_fee_comparisons ADD COLUMN revision_seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX fee_charge_observation_seq ON amazon_order_fee_charge_observations(observation_seq);
CREATE UNIQUE INDEX fee_comparison_revision_seq ON amazon_fee_comparisons(revision_seq);
CREATE INDEX fee_charge_current_revision ON amazon_order_fee_charge_observations
 (tenant_id,marketplace_connection_id,marketplace_id,transaction_key,observation_seq DESC);
CREATE INDEX fee_comparison_current_revision ON amazon_fee_comparisons
 (tenant_id,marketplace_connection_id,order_item_id,fee_type,revision_seq DESC);
