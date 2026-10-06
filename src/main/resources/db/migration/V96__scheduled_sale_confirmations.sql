ALTER TABLE profit_price_confirmations ADD COLUMN sale_start date, ADD COLUMN sale_end date;
ALTER TABLE profit_price_confirmations ADD CONSTRAINT sale_confirmation_dates
 CHECK ((sale_start IS NULL AND sale_end IS NULL) OR (sale_start IS NOT NULL AND sale_end IS NOT NULL AND sale_end>=sale_start AND new_price<old_price));
