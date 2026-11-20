-- One durable cooldown shared by scheduled and listing-import pricing refreshes.
ALTER TABLE marketplace_connections ADD COLUMN buy_box_refresh_after TIMESTAMPTZ;
