-- Genuine Amazon-issued documents only. No synthetic order/shipment is created to
-- reuse buy-shipping's order-bound artifact FK. Existing V145 remains unchanged.
CREATE TABLE fba_replenishment_documents (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 marketplace_connection_id uuid NOT NULL,
 plan_id uuid NOT NULL,
 warehouse_id uuid NOT NULL,
 amazon_shipment_id varchar(100) NOT NULL CHECK(length(btrim(amazon_shipment_id))>0),
 packing_version bigint NOT NULL CHECK(packing_version>0),
 document_type varchar(32) NOT NULL CHECK(document_type IN ('ITEM_LABEL','CARTON_LABEL','SHIPPING_LABEL','BILL_OF_LADING')),
 sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 encrypted_payload bytea NOT NULL CHECK(octet_length(encrypted_payload) BETWEEN 17 AND 10485776),
 encryption_nonce bytea NOT NULL CHECK(octet_length(encryption_nonce)=12),
 mime_type varchar(80) NOT NULL DEFAULT 'application/pdf' CHECK(mime_type='application/pdf'),
 page_count integer NOT NULL CHECK(page_count BETWEEN 1 AND 200),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,marketplace_connection_id,plan_id,warehouse_id,amazon_shipment_id,packing_version,document_type,sha256),
 FOREIGN KEY(tenant_id,marketplace_connection_id,plan_id) REFERENCES fba_replenishment_plans(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES warehouses(tenant_id,id)
);
CREATE INDEX fba_replenishment_documents_plan ON fba_replenishment_documents(tenant_id,marketplace_connection_id,plan_id,warehouse_id,created_at DESC);
ALTER TABLE fba_replenishment_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE fba_replenishment_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fba_replenishment_documents
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
