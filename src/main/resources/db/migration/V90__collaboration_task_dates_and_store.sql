ALTER TABLE collaboration_reviews ADD COLUMN due_date DATE;
ALTER TABLE collaboration_reviews ADD COLUMN due_time_zone VARCHAR(64);
ALTER TABLE collaboration_reviews ADD COLUMN marketplace_connection_id UUID;
ALTER TABLE collaboration_reviews ADD CONSTRAINT collaboration_task_store_fk
    FOREIGN KEY (tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id);
-- Preserve existing deadlines. New date-only deadlines expire after the whole local day.
UPDATE collaboration_reviews SET due_date=(due_at AT TIME ZONE 'UTC')::date,
    due_time_zone='UTC' WHERE due_at IS NOT NULL;
CREATE INDEX collaboration_active_due_idx ON collaboration_reviews(tenant_id,due_at)
    WHERE status='ACTIVE' AND due_at IS NOT NULL;
