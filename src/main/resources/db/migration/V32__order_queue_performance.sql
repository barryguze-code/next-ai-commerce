-- Supports paged order rendering and one batch lookup for the visible order items.
CREATE INDEX amazon_order_items_order_queue_idx
    ON amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,created_at);

CREATE INDEX order_inventory_reservations_order_queue_idx
    ON order_inventory_reservations(tenant_id,marketplace_connection_id,amazon_order_id,status);
