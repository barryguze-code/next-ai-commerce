ALTER TABLE catalog_import_progress DROP CONSTRAINT catalog_import_progress_state_check;
ALTER TABLE catalog_import_progress ADD CONSTRAINT catalog_import_progress_state_check
    CHECK (state IN ('QUEUED','PROCESSING','MAPPING','COMPLETED','FAILED'));
ALTER TABLE catalog_import_progress
    ADD COLUMN mapped_skus INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN unmapped_skus INTEGER NOT NULL DEFAULT 0;
