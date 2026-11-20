-- Current display/cost routing is separate from imported order/label history.
CREATE TABLE packing_package_aliases (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 source_key TEXT NOT NULL,
 display_name VARCHAR(120) NOT NULL CHECK (length(btrim(display_name))>0),
 cost_routed BOOLEAN NOT NULL DEFAULT false,
 cost_name VARCHAR(120) NOT NULL CHECK (length(btrim(cost_name))>0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,source_key)
);
CREATE TABLE packing_package_name_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 source_name VARCHAR(120) NOT NULL,
 destination_name VARCHAR(120) NOT NULL,
 operation TEXT NOT NULL CHECK(operation IN ('RENAME','MERGE')),
 affected_assignments INTEGER NOT NULL CHECK(affected_assignments>=0),
 actor TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX packing_package_alias_display_idx ON packing_package_aliases(tenant_id,lower(display_name));
CREATE INDEX packing_package_name_events_tenant_idx ON packing_package_name_events(tenant_id,created_at);
ALTER TABLE packing_package_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE packing_package_aliases FORCE ROW LEVEL SECURITY;
CREATE POLICY packing_package_alias_isolation ON packing_package_aliases USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE packing_package_name_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE packing_package_name_events FORCE ROW LEVEL SECURITY;
CREATE POLICY packing_package_event_isolation ON packing_package_name_events USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE FUNCTION packing_package_key(text) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$SELECT lower(btrim(regexp_replace($1,'[[:space:]]+',' ','g')))$$;
CREATE FUNCTION packing_package_display(uuid,text) RETURNS text LANGUAGE sql STABLE AS $$SELECT coalesce((SELECT display_name FROM packing_package_aliases WHERE tenant_id=$1 AND source_key=packing_package_key($2)),$2)$$;
CREATE FUNCTION packing_package_cost(uuid,text) RETURNS text LANGUAGE sql STABLE AS $$SELECT coalesce((SELECT cost_name FROM packing_package_aliases WHERE tenant_id=$1 AND source_key=packing_package_key($2)),$2)$$;

CREATE FUNCTION packing_package_merged(uuid,text) RETURNS boolean LANGUAGE sql STABLE AS $$SELECT coalesce((SELECT cost_routed FROM packing_package_aliases WHERE tenant_id=$1 AND source_key=packing_package_key($2)),false)$$;
