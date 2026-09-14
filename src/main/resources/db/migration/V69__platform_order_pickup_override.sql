-- Platform-only workflow; Amazon sync must not overwrite these fields.
ALTER TABLE amazon_orders
    ADD COLUMN platform_waiting_for_pickup BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN platform_pickup_changed_at TIMESTAMPTZ,
    ADD COLUMN platform_pickup_changed_by TEXT;
