-- Evolve the existing collaboration tables in place so current conversations and links remain valid.
ALTER TABLE collaboration_reviews
    ADD COLUMN created_by UUID REFERENCES app_users(id),
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN context_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN parent_url VARCHAR(500);

ALTER TABLE collaboration_reviews DISABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_reviews DROP CONSTRAINT IF EXISTS collaboration_reviews_status_check;
UPDATE collaboration_reviews review
SET created_by=(SELECT user_account.id FROM app_users user_account
                WHERE lower(user_account.email)=lower(review.requested_by) LIMIT 1),
    closed_at=CASE WHEN review.status='RESOLVED' THEN review.updated_at ELSE NULL END,
    status=CASE WHEN review.status='RESOLVED' THEN 'CLOSED' ELSE 'ACTIVE' END;
ALTER TABLE collaboration_reviews ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE collaboration_reviews ADD CONSTRAINT collaboration_reviews_status_check CHECK (status IN ('ACTIVE','CLOSED'));
ALTER TABLE collaboration_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_reviews FORCE ROW LEVEL SECURITY;

ALTER TABLE collaboration_messages
    ADD COLUMN sender_id UUID REFERENCES app_users(id),
    ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'TEAM_CHAT'
        CHECK (message_type IN ('TEAM_CHAT','PRIVATE_NOTE'));

ALTER TABLE collaboration_messages DISABLE ROW LEVEL SECURITY;
UPDATE collaboration_messages message
SET sender_id=(SELECT user_account.id FROM app_users user_account
               WHERE lower(user_account.email)=lower(message.author_email) LIMIT 1);
ALTER TABLE collaboration_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_messages FORCE ROW LEVEL SECURITY;

CREATE TABLE collaboration_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    review_id UUID NOT NULL REFERENCES collaboration_reviews(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES collaboration_messages(id) ON DELETE CASCADE,
    uploaded_by UUID REFERENCES app_users(id),
    uploaded_by_email VARCHAR(320) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 5000000),
    content BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX collaboration_reviews_active_entity_idx
    ON collaboration_reviews(tenant_id,subject_type,subject_key,updated_at DESC)
    WHERE status='ACTIVE';
CREATE INDEX collaboration_messages_visibility_idx
    ON collaboration_messages(tenant_id,review_id,message_type,created_at);
CREATE INDEX collaboration_private_notes_owner_idx
    ON collaboration_messages(tenant_id,lower(author_email),created_at DESC)
    WHERE message_type='PRIVATE_NOTE';
CREATE INDEX collaboration_mentions_recipient_idx
    ON collaboration_mentions(tenant_id,mentioned_user_id,created_at DESC);
CREATE INDEX collaboration_attachments_message_idx
    ON collaboration_attachments(tenant_id,message_id,created_at);

-- Privacy belongs at the database boundary as well as the repository query boundary.
DROP POLICY collaboration_messages_isolation ON collaboration_messages;
CREATE POLICY collaboration_messages_visibility ON collaboration_messages
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
           AND (message_type='TEAM_CHAT'
                OR lower(author_email)=lower(nullif(current_setting('app.user_email',true),''))))
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
                AND (message_type='TEAM_CHAT'
                     OR lower(author_email)=lower(nullif(current_setting('app.user_email',true),''))));

ALTER TABLE collaboration_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY collaboration_attachments_isolation ON collaboration_attachments
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
           AND EXISTS (SELECT 1 FROM collaboration_messages message
                       WHERE message.id=collaboration_attachments.message_id
                         AND message.tenant_id=collaboration_attachments.tenant_id))
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
                AND EXISTS (SELECT 1 FROM collaboration_messages message
                            WHERE message.id=collaboration_attachments.message_id
                              AND message.tenant_id=collaboration_attachments.tenant_id));
