-- Existing memberships keep their prior meaning until an administrator explicitly classifies them.
ALTER TABLE parent_tenant_memberships ADD COLUMN home_membership boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX one_active_home_tenant ON parent_tenant_memberships(user_id)
 WHERE active AND home_membership;

CREATE TABLE guest_account_grants (
 parent_id uuid NOT NULL,
 account_id uuid NOT NULL,
 user_id uuid NOT NULL,
 granted_by uuid NOT NULL REFERENCES app_users(id),
 granted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(account_id,user_id),
 FOREIGN KEY(parent_id,account_id) REFERENCES parent_tenant_accounts(parent_id,account_id),
 FOREIGN KEY(account_id,user_id) REFERENCES tenant_memberships(tenant_id,user_id) ON DELETE CASCADE
);
ALTER TABLE guest_account_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE guest_account_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY guest_account_scope ON guest_account_grants
 USING(account_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(account_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

-- Resolve inherited authority within the already selected account. These read
-- policies do not grant access to tasks, chat, messages or another account.
CREATE POLICY selected_account_parent ON parent_tenant_accounts FOR SELECT
 USING(account_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY selected_account_managers ON parent_tenant_memberships FOR SELECT
 USING(home_membership AND active AND role IN ('OWNER','ADMIN') AND EXISTS(
 SELECT 1 FROM parent_tenant_accounts a WHERE a.parent_id=parent_tenant_memberships.parent_id
 AND a.account_id=nullif(current_setting('app.tenant_id',true),'')::uuid));

CREATE VIEW effective_account_memberships WITH (security_invoker=true,security_barrier=true) AS
 WITH inherited AS (
 SELECT a.account_id AS tenant_id,m.user_id,m.role,false AS stores_restricted
 FROM parent_tenant_accounts a JOIN parent_tenant_memberships m ON m.parent_id=a.parent_id
 WHERE a.account_id=nullif(current_setting('app.tenant_id',true),'')::uuid
 AND m.active AND m.home_membership AND m.role IN ('OWNER','ADMIN')
 )
 SELECT i.tenant_id,i.user_id,
 CASE WHEN direct.role='OWNER' THEN direct.role ELSE i.role END AS role,
 i.stores_restricted,true AS tenant_managed
 FROM inherited i LEFT JOIN tenant_memberships direct USING(tenant_id,user_id)
 UNION ALL
 SELECT d.tenant_id,d.user_id,d.role,d.stores_restricted,false AS tenant_managed
 FROM tenant_memberships d WHERE NOT EXISTS(
 SELECT 1 FROM inherited i WHERE i.tenant_id=d.tenant_id AND i.user_id=d.user_id);

CREATE POLICY platform_tenant_directory ON parent_tenants FOR SELECT
 USING(EXISTS(SELECT 1 FROM platform_administrators a JOIN app_users u ON u.id=a.user_id
 WHERE a.active AND u.status='ACTIVE' AND lower(u.email)=lower(current_setting('app.user_email',true))));

ALTER TABLE tenant_invitations ADD COLUMN tenant_access_kind text NOT NULL DEFAULT 'ACCOUNT'
 CHECK(tenant_access_kind IN ('ACCOUNT','GUEST','MEMBER','ADMIN','OWNER'));
ALTER TABLE tenant_invitations ADD COLUMN home_or_guest_tenant uuid;
ALTER TABLE tenant_invitations ADD FOREIGN KEY(home_or_guest_tenant,tenant_id)
 REFERENCES parent_tenant_accounts(parent_id,account_id);
ALTER TABLE tenant_invitations ADD CHECK((tenant_access_kind='ACCOUNT')=(home_or_guest_tenant IS NULL));
