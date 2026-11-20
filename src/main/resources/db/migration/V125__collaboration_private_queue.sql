-- Additive parent collaboration: existing account tenants and all account RLS stay unchanged.
CREATE TABLE parent_tenants (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name varchar(160) NOT NULL CHECK(length(trim(name))>0),
 created_by uuid NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE parent_tenant_memberships (
 parent_id uuid NOT NULL REFERENCES parent_tenants(id),
 user_id uuid NOT NULL REFERENCES app_users(id),
 role text NOT NULL CHECK(role IN ('OWNER','ADMIN','MEMBER')),
 active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(parent_id,user_id)
);
CREATE TABLE parent_tenant_accounts (
 parent_id uuid NOT NULL REFERENCES parent_tenants(id),
 account_id uuid NOT NULL UNIQUE REFERENCES tenants(id),
 PRIMARY KEY(parent_id,account_id)
);
CREATE TABLE parent_tenant_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid NOT NULL REFERENCES parent_tenants(id),
 actor_id uuid NOT NULL REFERENCES app_users(id),
 event_kind text NOT NULL,
 details jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE collaboration_tasks (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid NOT NULL REFERENCES parent_tenants(id),
 account_id uuid,
 store_id uuid,
 title varchar(240) NOT NULL CHECK(length(trim(title))>0),
 created_by uuid NOT NULL REFERENCES app_users(id),
 state text NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','IN_PROGRESS','DONE')),
 queue_position integer,
 due_date date,
 due_time_zone varchar(64),
 subject_type varchar(40),
 subject_key varchar(240),
 source_review_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 closed_at timestamptz,
 UNIQUE(parent_id,id),
 FOREIGN KEY(parent_id,account_id) REFERENCES parent_tenant_accounts(parent_id,account_id),
 FOREIGN KEY(account_id,store_id) REFERENCES marketplace_connections(tenant_id,id),
 FOREIGN KEY(account_id,source_review_id) REFERENCES collaboration_reviews(tenant_id,id),
 CHECK(store_id IS NULL OR account_id IS NOT NULL),
 CHECK(source_review_id IS NULL OR account_id IS NOT NULL),
 CHECK(coalesce((state IN ('OPEN','IN_PROGRESS') AND queue_position>0 AND closed_at IS NULL)
   OR (state='DONE' AND queue_position IS NULL AND closed_at IS NOT NULL),false)),
 CONSTRAINT collaboration_task_queue_unique UNIQUE(parent_id,queue_position) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE collaboration_task_participants (
 parent_id uuid NOT NULL,
 task_id uuid NOT NULL,
 user_id uuid NOT NULL,
 assigned boolean NOT NULL DEFAULT false,
 added_by uuid NOT NULL REFERENCES app_users(id),
 added_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(parent_id,task_id,user_id),
 FOREIGN KEY(parent_id,task_id) REFERENCES collaboration_tasks(parent_id,id) ON DELETE CASCADE,
 FOREIGN KEY(parent_id,user_id) REFERENCES parent_tenant_memberships(parent_id,user_id)
);
CREATE INDEX collaboration_task_participant_user ON collaboration_task_participants(parent_id,user_id,assigned,task_id);
CREATE TABLE collaboration_queue_state (
 parent_id uuid PRIMARY KEY REFERENCES parent_tenants(id),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0)
);
CREATE TABLE collaboration_task_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid NOT NULL,
 task_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES app_users(id),
 event_kind text NOT NULL CHECK(event_kind IN ('CREATED','CONVERTED','STATE','POSITION','ASSIGNMENT','AUDIENCE')),
 details jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(parent_id,task_id) REFERENCES collaboration_tasks(parent_id,id) ON DELETE CASCADE
);
CREATE TABLE collaboration_task_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid NOT NULL,
 task_id uuid NOT NULL,
 author_id uuid NOT NULL REFERENCES app_users(id),
 body text NOT NULL CHECK(length(body)<=4000),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(parent_id,task_id,id),
 FOREIGN KEY(parent_id,task_id) REFERENCES collaboration_tasks(parent_id,id) ON DELETE CASCADE
);
CREATE TABLE collaboration_task_files (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid NOT NULL,
 task_id uuid NOT NULL,
 message_id uuid NOT NULL,
 file_name varchar(255) NOT NULL,
 content_type varchar(120) NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content)<=5000000),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(parent_id,task_id,message_id) REFERENCES collaboration_task_messages(parent_id,task_id,id) ON DELETE CASCADE
);
CREATE INDEX collaboration_task_board ON collaboration_tasks(parent_id,state,queue_position,id);
CREATE INDEX collaboration_task_done ON collaboration_tasks(parent_id,closed_at DESC,id DESC) WHERE state='DONE';
CREATE INDEX collaboration_task_messages_order ON collaboration_task_messages(parent_id,task_id,created_at,id);
CREATE INDEX collaboration_task_events_order ON collaboration_task_events(parent_id,task_id,created_at,id);

-- app.user_email and app.parent_id are set only from the authenticated server transaction.
-- Membership tables use scoped RLS without recursive audience policies. Content adds the audience gate.
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['parent_tenants','parent_tenant_memberships','parent_tenant_accounts','parent_tenant_events',
 'collaboration_tasks','collaboration_task_participants','collaboration_queue_state','collaboration_task_events',
 'collaboration_task_messages','collaboration_task_files'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY parent_scope ON %I USING (%I=nullif(current_setting(''app.parent_id'',true),'''')::uuid) WITH CHECK (%I=nullif(current_setting(''app.parent_id'',true),'''')::uuid)',t,CASE WHEN t='parent_tenants' THEN 'id' ELSE 'parent_id' END,CASE WHEN t='parent_tenants' THEN 'id' ELSE 'parent_id' END);
 END LOOP;
END $$;
CREATE POLICY parent_membership_directory ON parent_tenant_memberships FOR SELECT
 USING(user_id IN (SELECT id FROM app_users WHERE lower(email)=lower(nullif(current_setting('app.user_email',true),'')) AND status='ACTIVE'));
CREATE POLICY parent_directory ON parent_tenants FOR SELECT
 USING(EXISTS(SELECT 1 FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id
 WHERE m.parent_id=parent_tenants.id AND m.active AND u.status='ACTIVE'
 AND lower(u.email)=lower(nullif(current_setting('app.user_email',true),''))));
-- Empty defaults: no inferred grouping, account changes or legacy discussion audience changes.

-- Content requires current parent membership, even if a stale browser supplies a parent ID.
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['collaboration_tasks','collaboration_queue_state','collaboration_task_events','collaboration_task_messages','collaboration_task_files'] LOOP
  EXECUTE format('CREATE POLICY active_parent_member ON %I AS RESTRICTIVE USING (EXISTS(SELECT 1 FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.parent_id=%I.parent_id AND m.active AND u.status=''ACTIVE'' AND lower(u.email)=lower(nullif(current_setting(''app.user_email'',true),''''))))',t,t);
 END LOOP;
END $$;
-- Queue shifts must touch hidden positions but never return their content. Only the trusted
-- queue repository enables this transaction-local flag, after validating the actor/target.
DO $$ DECLARE t text; DECLARE task_column text; BEGIN
 FOREACH t IN ARRAY ARRAY['collaboration_tasks','collaboration_task_events','collaboration_task_messages','collaboration_task_files'] LOOP
  task_column:=CASE WHEN t='collaboration_tasks' THEN 'id' ELSE 'task_id' END;
  EXECUTE format('CREATE POLICY task_audience ON %I AS RESTRICTIVE FOR SELECT USING (current_setting(''app.collaboration_queue_mutation'',true)=''true'' OR EXISTS(SELECT 1 FROM collaboration_task_participants p JOIN app_users u ON u.id=p.user_id WHERE p.parent_id=%I.parent_id AND p.task_id=%I.%I AND u.status=''ACTIVE'' AND lower(u.email)=lower(nullif(current_setting(''app.user_email'',true),''''))))',t,t,t,task_column);
 END LOOP;
END $$;
