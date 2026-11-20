CREATE TABLE collaboration_mentions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    review_id UUID NOT NULL REFERENCES collaboration_reviews(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES collaboration_messages(id) ON DELETE CASCADE,
    mentioned_user_id UUID NOT NULL REFERENCES app_users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SENDING','SENT','FAILED','SKIPPED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(500),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, mentioned_user_id)
);

CREATE INDEX collaboration_mentions_delivery_idx
    ON collaboration_mentions(tenant_id, status, next_attempt_at, created_at);

ALTER TABLE collaboration_mentions ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_mentions FORCE ROW LEVEL SECURITY;
CREATE POLICY collaboration_mentions_isolation ON collaboration_mentions
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
