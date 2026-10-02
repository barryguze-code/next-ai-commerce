ALTER TABLE inventory_ledger_entries DROP CONSTRAINT inventory_ledger_entries_quantity_check;
ALTER TABLE inventory_ledger_entries ADD CONSTRAINT inventory_ledger_entries_quantity_check
 CHECK(quantity<>0 OR source_type='PHYSICAL_COUNT' OR entry_type IN
 ('SHIPMENT_UNRECORDED','SHARED_STOCK_SALES','RESERVATION_RELEASED','ORDER_STATUS_REVIEW','INVENTORY_PLAN'));
