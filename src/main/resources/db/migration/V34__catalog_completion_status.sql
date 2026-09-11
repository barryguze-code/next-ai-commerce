ALTER TABLE account_catalog_items
    ADD COLUMN completion_status VARCHAR(24) NOT NULL DEFAULT 'COMPLETE'
        CHECK (completion_status IN ('COMPLETE','NEEDS_COMPLETION')),
    ADD COLUMN missing_fields TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX account_catalog_completion_idx
    ON account_catalog_items (tenant_id, completion_status, updated_at DESC);

COMMENT ON COLUMN account_catalog_items.completion_status IS 'Non-blocking data-quality status for products created during operational flows.';
