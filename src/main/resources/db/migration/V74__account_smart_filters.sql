CREATE TABLE user_table_filters (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 owner_key TEXT NOT NULL,
 widget VARCHAR(40) NOT NULL,
 name VARCHAR(40) NOT NULL,
 filters JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,owner_key,widget,name)
);
ALTER TABLE user_table_filters ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_table_filters FORCE ROW LEVEL SECURITY;
CREATE POLICY user_table_filters_isolation ON user_table_filters
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
