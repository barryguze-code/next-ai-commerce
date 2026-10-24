-- Additive local-first fee evidence. No backfill, source updates, jobs or activation.
CREATE TABLE amazon_fee_observations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL,
 marketplace_connection_id uuid NOT NULL, marketplace_id text NOT NULL, seller_sku text COLLATE "C" NOT NULL,
 currency text NOT NULL CHECK(currency ~ '^[A-Z]{3}$'), fulfillment text NOT NULL CHECK(fulfillment='FBA'),
 fee_type text NOT NULL CHECK(fee_type IN ('REFERRAL','FBA')), unit_price numeric(19,4) NOT NULL CHECK(unit_price>0),
 shipping numeric(19,4) NOT NULL CHECK(shipping>=0), amount numeric(19,4) NOT NULL CHECK(amount>=0),
 observed_at timestamptz NOT NULL, boundary_at timestamptz NOT NULL CHECK(boundary_at<=observed_at),
 source_document_id uuid NOT NULL, evidence_key text NOT NULL, basis jsonb NOT NULL,
 UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,id),
 UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,evidence_key,fee_type),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id) REFERENCES marketplace_connections(tenant_id,id,marketplace_identifier)
);
CREATE UNIQUE INDEX fee_source_scoped_identity ON amazon_source_documents(tenant_id,marketplace_connection_id,id);
ALTER TABLE amazon_fee_observations ADD FOREIGN KEY(tenant_id,marketplace_connection_id,source_document_id)
 REFERENCES amazon_source_documents(tenant_id,marketplace_connection_id,id);
CREATE INDEX fee_observation_basis ON amazon_fee_observations
 (tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,fee_type,unit_price,shipping,observed_at DESC);

CREATE TABLE amazon_order_fee_charge_observations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL,
 marketplace_id text NOT NULL, transaction_key text NOT NULL, revision text NOT NULL, component_path text NOT NULL,
 order_id text, seller_sku text COLLATE "C", fee_type text NOT NULL CHECK(fee_type IN ('REFERRAL','FBA','UNKNOWN')),
 currency text, signed_amount numeric(19,4), expense_amount numeric(19,4), quantity integer CHECK(quantity>0),
 fulfillment text, posted_at timestamptz, observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 state text NOT NULL CHECK(state IN ('SUPPORTED','UNRESOLVED','ADJUSTMENT')), reason text NOT NULL,
 source_document_id uuid NOT NULL, raw_evidence jsonb NOT NULL,
 UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,id),
 UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,transaction_key,revision,component_path),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id) REFERENCES marketplace_connections(tenant_id,id,marketplace_identifier),
 FOREIGN KEY(tenant_id,marketplace_connection_id,source_document_id) REFERENCES amazon_source_documents(tenant_id,marketplace_connection_id,id),
 CHECK(state<>'SUPPORTED' OR (order_id IS NOT NULL AND seller_sku IS NOT NULL AND currency ~ '^[A-Z]{3}$'
   AND currency IS NOT NULL AND expense_amount IS NOT NULL AND expense_amount>=0 AND signed_amount IS NOT NULL
   AND quantity IS NOT NULL AND quantity>0 AND fulfillment IS NOT NULL AND fulfillment='FBA' AND posted_at IS NOT NULL))
);
CREATE INDEX fee_charge_order ON amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,order_id,seller_sku,fee_type,observed_at DESC);
CREATE INDEX fee_charge_revision ON amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,transaction_key,observed_at DESC);

CREATE TABLE amazon_fee_approvals (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL,
 marketplace_id text NOT NULL, seller_sku text COLLATE "C" NOT NULL, currency text NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 fulfillment text NOT NULL CHECK(fulfillment='FBA'), fee_type text NOT NULL CHECK(fee_type IN ('REFERRAL','FBA')),
 unit_price numeric(19,4) NOT NULL CHECK(unit_price>0), shipping numeric(19,4) NOT NULL CHECK(shipping>=0),
 amount numeric(19,4) NOT NULL CHECK(amount>=0), effective_from timestamptz NOT NULL DEFAULT clock_timestamp(),
 source_charge_id uuid NOT NULL, comparison_id uuid NOT NULL, actor text NOT NULL CHECK(length(actor)>0), request_key uuid NOT NULL,
 previous_id uuid, UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,id),
 UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,request_key,fee_type),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id) REFERENCES marketplace_connections(tenant_id,id,marketplace_identifier),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,source_charge_id)
 REFERENCES amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,previous_id)
 REFERENCES amazon_fee_approvals(tenant_id,marketplace_connection_id,marketplace_id,id)
);
CREATE INDEX fee_approval_basis ON amazon_fee_approvals(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,fee_type,unit_price,shipping,effective_from DESC);

CREATE UNIQUE INDEX fee_order_item_scoped_identity ON amazon_order_items(tenant_id,marketplace_connection_id,id);
CREATE TABLE amazon_fee_comparisons (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, marketplace_connection_id uuid NOT NULL,
 marketplace_id text NOT NULL, order_item_id uuid NOT NULL, order_id text NOT NULL, seller_sku text COLLATE "C" NOT NULL,
 fee_type text NOT NULL CHECK(fee_type IN ('REFERRAL','FBA')), currency text NOT NULL,
 quantity integer NOT NULL CHECK(quantity>0), unit_price numeric(19,4), shipping numeric(19,4),
 purchase_at timestamptz NOT NULL, boundary_at timestamptz, observation_id uuid, approval_id uuid,
 expected_amount numeric(19,4), actual_amount numeric(19,4), difference numeric(19,4),
 state text NOT NULL CHECK(state IN ('AWAITING','NOT_COMPARABLE','MATCHES','HIGHER','LOWER')),
 reason text NOT NULL, charge_id uuid, previous_id uuid,
 revision_key text NOT NULL, observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,marketplace_connection_id,marketplace_id,id),
 UNIQUE(tenant_id,marketplace_connection_id,order_item_id,fee_type,revision_key),
 FOREIGN KEY(tenant_id,marketplace_connection_id,order_item_id) REFERENCES amazon_order_items(tenant_id,marketplace_connection_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,observation_id) REFERENCES amazon_fee_observations(tenant_id,marketplace_connection_id,marketplace_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,approval_id) REFERENCES amazon_fee_approvals(tenant_id,marketplace_connection_id,marketplace_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,charge_id) REFERENCES amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,previous_id) REFERENCES amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,id),
 FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id) REFERENCES marketplace_connections(tenant_id,id,marketplace_identifier),
 CHECK(state NOT IN ('MATCHES','HIGHER','LOWER') OR
  (expected_amount IS NOT NULL AND actual_amount IS NOT NULL AND difference IS NOT NULL AND difference=actual_amount-expected_amount
   AND charge_id IS NOT NULL AND boundary_at IS NOT NULL AND boundary_at<purchase_at AND num_nonnulls(observation_id,approval_id)=1)),
 CHECK(state NOT IN ('MATCHES','HIGHER','LOWER') OR (expected_amount>=0 AND actual_amount>=0)),
 CHECK(state<>'MATCHES' OR difference=0), CHECK(state<>'HIGHER' OR difference>0), CHECK(state<>'LOWER' OR difference<0)
);
ALTER TABLE amazon_fee_approvals ADD FOREIGN KEY(tenant_id,marketplace_connection_id,marketplace_id,comparison_id)
 REFERENCES amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,id);
CREATE INDEX fee_comparison_order ON amazon_fee_comparisons(tenant_id,marketplace_connection_id,order_item_id,fee_type,observed_at DESC);
CREATE INDEX fee_comparison_sku ON amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,state,observed_at DESC);

CREATE FUNCTION preserve_fee_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Fee evidence is append-only'; END $$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['amazon_fee_observations','amazon_order_fee_charge_observations','amazon_fee_approvals','amazon_fee_comparisons'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  EXECUTE format('CREATE TRIGGER immutable_fee_evidence BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION preserve_fee_evidence()',tab);
 END LOOP;
END $$;
