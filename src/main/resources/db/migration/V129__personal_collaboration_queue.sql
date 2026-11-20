-- Personal ordering only. Source records remain authoritative for access and assignment.
CREATE TABLE collaboration_personal_queues (
    user_id uuid PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

CREATE TABLE collaboration_personal_queue_entries (
    user_id uuid NOT NULL REFERENCES collaboration_personal_queues(user_id) ON DELETE CASCADE,
    task_id uuid REFERENCES collaboration_tasks(id) ON DELETE CASCADE,
    review_id uuid REFERENCES collaboration_reviews(id) ON DELETE CASCADE,
    source_type text GENERATED ALWAYS AS
        (CASE WHEN task_id IS NULL THEN 'REVIEW' ELSE 'TASK' END) STORED,
    source_id uuid GENERATED ALWAYS AS (coalesce(task_id, review_id)) STORED,
    position bigint NOT NULL CHECK (position > 0),
    CHECK (num_nonnulls(task_id, review_id) = 1),
    PRIMARY KEY (user_id, source_type, source_id),
    UNIQUE (user_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX collaboration_personal_task_owners ON collaboration_personal_queue_entries(task_id, user_id)
    WHERE task_id IS NOT NULL;
CREATE INDEX collaboration_personal_review_owners ON collaboration_personal_queue_entries(review_id, user_id)
    WHERE review_id IS NOT NULL;

DO $$
DECLARE rel text;
BEGIN
    FOREACH rel IN ARRAY ARRAY['collaboration_personal_queues','collaboration_personal_queue_entries'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', rel);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', rel);
        EXECUTE format('CREATE POLICY personal_owner ON %I USING (EXISTS (
            SELECT 1 FROM app_users u WHERE u.id = %I.user_id AND u.status = ''ACTIVE''
            AND lower(u.email) = lower(nullif(current_setting(''app.user_email'', true), ''''))
        ))', rel, rel);
    END LOOP;
END $$;

-- A new activation token survives a close/reopen between two visits to the queue.
ALTER TABLE collaboration_tasks ADD COLUMN personal_reopened_at timestamptz;
ALTER TABLE collaboration_reviews ADD COLUMN personal_reopened_at timestamptz;
ALTER TABLE collaboration_personal_queue_entries ADD COLUMN observed_reopen timestamptz;
CREATE FUNCTION collaboration_mark_personal_reopen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_jsonb(OLD)->>TG_ARGV[0] = TG_ARGV[1] AND to_jsonb(NEW)->>TG_ARGV[0] <> TG_ARGV[1] THEN
        NEW.personal_reopened_at := clock_timestamp();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER personal_task_reopen BEFORE UPDATE OF state ON collaboration_tasks
    FOR EACH ROW EXECUTE FUNCTION collaboration_mark_personal_reopen('state','DONE');
CREATE TRIGGER personal_review_reopen BEFORE UPDATE OF status ON collaboration_reviews
    FOR EACH ROW EXECUTE FUNCTION collaboration_mark_personal_reopen('status','CLOSED');
