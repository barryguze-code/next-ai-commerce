-- Exact message acknowledgements avoid timestamp races with concurrent replies.
CREATE TABLE collaboration_read_receipts (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    message_id UUID NOT NULL REFERENCES collaboration_messages(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, message_id)
);
ALTER TABLE collaboration_read_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_read_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY collaboration_read_receipts_isolation ON collaboration_read_receipts
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
       AND user_id IN (SELECT id FROM app_users WHERE lower(email)=lower(nullif(current_setting('app.user_email',true),''))))
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
       AND user_id IN (SELECT id FROM app_users WHERE lower(email)=lower(nullif(current_setting('app.user_email',true),''))));
CREATE INDEX collaboration_mentions_unread_lookup_idx
    ON collaboration_mentions(tenant_id,review_id,mentioned_user_id,message_id)
    WHERE notification_kind='MENTION';
