-- Status evidence is independent of report-window membership; age never cancels an order.
CREATE TABLE amazon_order_status_checks (
 tenant_id uuid NOT NULL, connection_id uuid NOT NULL, amazon_order_id text NOT NULL,
 next_check_at timestamptz NOT NULL DEFAULT now(), checked_at timestamptz,
 failures integer NOT NULL DEFAULT 0, state text NOT NULL DEFAULT 'DUE', detail text,
 PRIMARY KEY(tenant_id,connection_id,amazon_order_id),
 FOREIGN KEY(tenant_id,connection_id,amazon_order_id)
   REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id) ON DELETE CASCADE
);
ALTER TABLE amazon_order_status_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE amazon_order_status_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON amazon_order_status_checks
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE INDEX order_status_checks_due ON amazon_order_status_checks(tenant_id,next_check_at);
ALTER TABLE inventory_ledger_entries DROP CONSTRAINT inventory_ledger_entries_quantity_check;
ALTER TABLE inventory_ledger_entries ADD CONSTRAINT inventory_ledger_entries_quantity_check
 CHECK(quantity<>0 OR source_type='PHYSICAL_COUNT' OR entry_type IN
 ('SHIPMENT_UNRECORDED','SHARED_STOCK_SALES','RESERVATION_RELEASED','ORDER_STATUS_REVIEW'));
