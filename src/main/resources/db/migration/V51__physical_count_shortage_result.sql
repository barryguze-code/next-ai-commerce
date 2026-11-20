ALTER TABLE physical_count_import_progress
    ADD COLUMN shortage_orders INTEGER NOT NULL DEFAULT 0 CHECK (shortage_orders >= 0);
