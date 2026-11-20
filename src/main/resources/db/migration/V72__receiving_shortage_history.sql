-- Shortages are receipt decisions with no physical stock movement.
ALTER TABLE receiving_line_receipts DROP CONSTRAINT receiving_line_receipts_disposition_check;
ALTER TABLE receiving_line_receipts ADD CONSTRAINT receiving_line_receipts_disposition_check
  CHECK (disposition IN ('SELLABLE','DAMAGED','MISPICKED','SOON_EXPIRED','EXPIRED','OVER_SHIPPED','SHORT_SHIPPED'));
