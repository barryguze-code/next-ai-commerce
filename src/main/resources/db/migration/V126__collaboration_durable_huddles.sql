-- Preserve the existing account-origin huddle boundary; saved tasks use explicit parent association.
CREATE TABLE collaboration_huddle_rooms (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 creator_id uuid NOT NULL REFERENCES app_users(id),
 payload jsonb NOT NULL,
 status text NOT NULL CHECK(status IN ('ACTIVE','ENDED','SAVED')),
 saved_parent_id uuid,
 saved_task_id uuid,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(saved_parent_id,saved_task_id) REFERENCES collaboration_tasks(parent_id,id),
 CHECK((saved_parent_id IS NULL)=(saved_task_id IS NULL))
);
CREATE TABLE collaboration_huddle_files (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 room_id uuid NOT NULL,
 file_name varchar(255) NOT NULL,
 content_type varchar(120) NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content)<=2097152),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,room_id) REFERENCES collaboration_huddle_rooms(tenant_id,id)
);
CREATE INDEX collaboration_huddle_active ON collaboration_huddle_rooms(tenant_id,created_at DESC) WHERE status='ACTIVE';
CREATE INDEX collaboration_huddle_files_room ON collaboration_huddle_files(tenant_id,room_id);
ALTER TABLE collaboration_huddle_rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_huddle_rooms FORCE ROW LEVEL SECURITY;
CREATE POLICY huddle_room_account ON collaboration_huddle_rooms
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE collaboration_huddle_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_huddle_files FORCE ROW LEVEL SECURITY;
CREATE POLICY huddle_file_account ON collaboration_huddle_files
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
