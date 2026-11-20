CREATE TABLE order_shipping_recipients (
 tenant_id UUID NOT NULL,
 marketplace_connection_id UUID NOT NULL,
 amazon_order_id VARCHAR(40) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','FETCHING','AVAILABLE','INCOMPLETE','DENIED','RETRY','EXPIRED','INELIGIBLE')),
 encrypted_payload BYTEA,
 encryption_nonce BYTEA,
 source_updated_at TIMESTAMPTZ,
 fetched_at TIMESTAMPTZ,
 expires_at TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_id UUID,
 attempts INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,marketplace_connection_id,amazon_order_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,amazon_order_id) REFERENCES amazon_orders(tenant_id,marketplace_connection_id,amazon_order_id) ON DELETE CASCADE,
 CHECK((encrypted_payload IS NULL)=(encryption_nonce IS NULL)),
 CHECK(encryption_nonce IS NULL OR octet_length(encryption_nonce)=12)
);
CREATE INDEX shipping_recipients_due ON order_shipping_recipients(tenant_id,next_attempt_at) WHERE status IN ('QUEUED','RETRY','FETCHING','DENIED');
CREATE INDEX shipping_recipients_expiry ON order_shipping_recipients(tenant_id,expires_at) WHERE encrypted_payload IS NOT NULL;
CREATE TABLE order_recipient_access (
 tenant_id UUID NOT NULL,
 marketplace_connection_id UUID NOT NULL,
 next_allowed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,marketplace_connection_id),
 FOREIGN KEY(tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);
ALTER TABLE order_shipping_recipients ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_shipping_recipients FORCE ROW LEVEL SECURITY;
CREATE POLICY order_shipping_recipients_tenant ON order_shipping_recipients USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE order_recipient_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_recipient_access FORCE ROW LEVEL SECURITY;
CREATE POLICY order_recipient_access_tenant ON order_recipient_access USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
