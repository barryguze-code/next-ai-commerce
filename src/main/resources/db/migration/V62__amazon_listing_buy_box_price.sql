ALTER TABLE amazon_listings
    ADD COLUMN buy_box_price NUMERIC(19,4),
    ADD COLUMN buy_box_currency CHAR(3),
    ADD COLUMN buy_box_updated_at TIMESTAMPTZ;

