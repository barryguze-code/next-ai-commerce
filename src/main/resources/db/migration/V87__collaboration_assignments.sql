ALTER TABLE collaboration_reviews ADD CONSTRAINT collaboration_reviews_tenant_id_unique UNIQUE(tenant_id,id);
CREATE TABLE collaboration_assignments (
 tenant_id uuid NOT NULL,
 review_id uuid NOT NULL,
 user_id uuid NOT NULL REFERENCES app_users(id),
 assigned_by text NOT NULL,
 assigned_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,review_id,user_id),
 FOREIGN KEY(tenant_id,review_id) REFERENCES collaboration_reviews(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX collaboration_assignments_user_idx ON collaboration_assignments(tenant_id,user_id,review_id);
ALTER TABLE collaboration_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY collaboration_assignments_isolation ON collaboration_assignments
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE collaboration_mentions DROP CONSTRAINT collaboration_mentions_notification_kind_check;
ALTER TABLE collaboration_mentions ADD CONSTRAINT collaboration_mentions_notification_kind_check CHECK(notification_kind IN ('MENTION','COMPLETED','ASSIGNED'));
