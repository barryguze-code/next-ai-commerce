CREATE TABLE carrier_package_defaults (
 tenant_id UUID NOT NULL REFERENCES tenants(id), marketplace_connection_id UUID NOT NULL REFERENCES marketplace_connections(id),
 composition_key VARCHAR(64) NOT NULL, dimensions JSONB NOT NULL,
 PRIMARY KEY(tenant_id,marketplace_connection_id,composition_key)
);
CREATE TABLE carrier_shipments (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
 marketplace_connection_id UUID NOT NULL REFERENCES marketplace_connections(id),
 carrier_connection_id UUID NOT NULL, carrier_revision BIGINT NOT NULL,
 amazon_order_id TEXT NOT NULL, environment TEXT NOT NULL CHECK(environment IN ('SANDBOX','PRODUCTION')),
 state TEXT NOT NULL CHECK(state IN ('RATED','PURCHASING','PURCHASED','UNKNOWN','FAILED')),
 request_payload BYTEA NOT NULL, request_nonce BYTEA NOT NULL,
 rates JSONB NOT NULL, packing_lines JSONB NOT NULL,
 selected_service TEXT, tracking_number TEXT,
 label_payload BYTEA, label_nonce BYTEA,
 error_message TEXT, actor TEXT NOT NULL,
 confirmation_payload JSONB,
 confirmation_state TEXT NOT NULL DEFAULT 'NOT_REQUIRED' CHECK(confirmation_state IN ('NOT_REQUIRED','PENDING','IN_PROGRESS','CONFIRMED','REVIEW')),
 confirmation_attempts INTEGER NOT NULL DEFAULT 0,
 confirmation_attempted_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '15 minutes',
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,carrier_connection_id) REFERENCES carrier_connections(tenant_id,id)
);
CREATE INDEX carrier_shipments_order_idx ON carrier_shipments(tenant_id,marketplace_connection_id,amazon_order_id,created_at DESC);
CREATE INDEX carrier_confirmation_pending_idx ON carrier_shipments(tenant_id,created_at) WHERE confirmation_state='PENDING';
-- A quote is claimed atomically before HTTP. Uncertain outcomes are never retried.
CREATE UNIQUE INDEX carrier_shipments_live_order_idx ON carrier_shipments(tenant_id,marketplace_connection_id,amazon_order_id)
 WHERE environment='PRODUCTION' AND state IN ('PURCHASING','PURCHASED','UNKNOWN');
ALTER TABLE carrier_package_defaults ENABLE ROW LEVEL SECURITY;
ALTER TABLE carrier_package_defaults FORCE ROW LEVEL SECURITY;
CREATE POLICY carrier_package_defaults_tenant ON carrier_package_defaults
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE carrier_shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE carrier_shipments FORCE ROW LEVEL SECURITY;
CREATE POLICY carrier_shipments_tenant ON carrier_shipments
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
