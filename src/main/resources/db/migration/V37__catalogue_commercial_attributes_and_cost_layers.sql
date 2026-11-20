ALTER TABLE global_catalog_products ADD COLUMN package_size VARCHAR(80);

ALTER TABLE vendor_catalog_offers
    ADD COLUMN units_of_sale NUMERIC(14,4),
    ADD COLUMN suggested_retail NUMERIC(19,4),
    ADD CONSTRAINT vendor_catalog_units_of_sale_positive CHECK (units_of_sale IS NULL OR units_of_sale > 0),
    ADD CONSTRAINT vendor_catalog_suggested_retail_nonnegative CHECK (suggested_retail IS NULL OR suggested_retail >= 0);

ALTER TABLE order_inventory_reservations
    ADD COLUMN cost_layer_id UUID,
    ADD COLUMN unit_cost NUMERIC(19,4),
    ADD COLUMN cost_currency CHAR(3);

ALTER TABLE inventory_ledger_entries ADD CONSTRAINT inventory_ledger_tenant_id_id_uk UNIQUE (tenant_id,id);
ALTER TABLE order_inventory_reservations ADD CONSTRAINT order_inventory_reservation_cost_layer_fk
    FOREIGN KEY (tenant_id,cost_layer_id) REFERENCES inventory_ledger_entries(tenant_id,id);

DROP INDEX order_inventory_reservations_one_active_layer_idx;
CREATE UNIQUE INDEX order_inventory_reservations_one_active_layer_idx
    ON order_inventory_reservations(tenant_id,amazon_order_item_id,account_catalog_item_id,
        coalesce(cost_layer_id,'00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(expiration_date,DATE 'infinity')) WHERE status='ACTIVE';
