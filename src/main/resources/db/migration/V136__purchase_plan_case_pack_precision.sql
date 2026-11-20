-- Match existing catalogue case-pack precision; no inventory quantities are changed.
ALTER TABLE replenishment_purchase_plan_lines
 ALTER COLUMN units_per_case TYPE numeric(12,4) USING units_per_case::numeric(12,4);
