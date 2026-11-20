-- Local packing metadata only. No stock updates, backfill, or provider writes.
ALTER TABLE shopify_orders
    ADD COLUMN platform_waiting_for_pickup BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN local_handoff_id UUID,
    ADD COLUMN local_handoff_at TIMESTAMPTZ,
    ADD COLUMN local_handoff_revision TIMESTAMPTZ,
    ADD COLUMN local_handoff_actor TEXT,
    ADD COLUMN package_name TEXT CHECK(length(package_name)<=120),
    ADD CONSTRAINT shopify_local_handoff_identity CHECK(
      NOT platform_waiting_for_pickup OR (local_handoff_id IS NOT NULL
        AND local_handoff_at IS NOT NULL AND local_handoff_revision IS NOT NULL));
ALTER TABLE shopify_order_lines
    ADD COLUMN local_handoff_units INTEGER NOT NULL DEFAULT 0 CHECK(local_handoff_units>=0),
    ADD COLUMN local_pending_units INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT shopify_local_pending_bounds CHECK(
      local_pending_units>=0 AND local_pending_units<=local_handoff_units);
