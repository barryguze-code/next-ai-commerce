CREATE TABLE collaboration_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    subject_type VARCHAR(40) NOT NULL,
    subject_key VARCHAR(240) NOT NULL,
    subject_label VARCHAR(300) NOT NULL,
    marketplace VARCHAR(30),
    action_kind VARCHAR(50) NOT NULL DEFAULT 'TEAM_REVIEW',
    title VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','WAITING','RESOLVED')),
    requested_by VARCHAR(320) NOT NULL,
    assigned_to UUID REFERENCES app_users(id),
    due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE collaboration_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    review_id UUID NOT NULL REFERENCES collaboration_reviews(id) ON DELETE CASCADE,
    author_email VARCHAR(320) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX collaboration_reviews_tenant_updated_idx ON collaboration_reviews(tenant_id, updated_at DESC);
CREATE INDEX collaboration_reviews_subject_idx ON collaboration_reviews(tenant_id, subject_type, subject_key, status);
CREATE INDEX collaboration_messages_review_idx ON collaboration_messages(tenant_id, review_id, created_at);

ALTER TABLE collaboration_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_reviews FORCE ROW LEVEL SECURITY;
ALTER TABLE collaboration_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY collaboration_reviews_isolation ON collaboration_reviews
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY collaboration_messages_isolation ON collaboration_messages
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
