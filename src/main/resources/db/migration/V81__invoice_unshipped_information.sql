-- Informational source data only; never creates receiving balances or inventory.
ALTER TABLE receiving_documents ADD COLUMN unshipped_rows jsonb;
