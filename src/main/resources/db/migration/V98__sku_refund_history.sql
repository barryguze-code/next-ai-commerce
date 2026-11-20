-- Read-optimized projection. The source transaction remains the audit record.
CREATE TABLE amazon_sku_refunds (
 tenant_id UUID NOT NULL,
 marketplace_connection_id UUID NOT NULL,
 transaction_id UUID NOT NULL REFERENCES amazon_financial_transactions(id) ON DELETE CASCADE,
 item_index INTEGER NOT NULL,
 seller_sku TEXT,
 posted_date TIMESTAMPTZ,
 currency TEXT,
 item_refund_amount NUMERIC(19,4),
 refunded_units INTEGER,
 PRIMARY KEY(transaction_id,item_index),
 FOREIGN KEY(tenant_id,marketplace_connection_id) REFERENCES marketplace_connections(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX amazon_sku_refunds_history_idx ON amazon_sku_refunds
 (tenant_id,marketplace_connection_id,seller_sku,posted_date) INCLUDE(currency,item_refund_amount,refunded_units);

CREATE FUNCTION project_amazon_sku_refunds(source amazon_financial_transactions) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 DELETE FROM amazon_sku_refunds WHERE tenant_id=source.tenant_id AND transaction_id=source.id;
 IF upper(source.transaction_type) <> 'REFUND' AND lower(source.raw_payload->>'description') IS DISTINCT FROM 'refund order' THEN RETURN; END IF;
 INSERT INTO amazon_sku_refunds
 SELECT source.tenant_id,source.marketplace_connection_id,source.id,item.ordinality::integer,
   coalesce(nullif(item.value->>'sellerSku',''),nullif(item.value->>'sku',''),
      (SELECT nullif(context->>'sku','') FROM jsonb_array_elements(CASE WHEN jsonb_typeof(item.value->'contexts')='array' THEN item.value->'contexts' ELSE '[]'::jsonb END) context WHERE context->>'contextType'='ProductContext' LIMIT 1)),
   source.posted_date,amount.currency,amount.amount,
   CASE WHEN item.value->>'quantityRefunded' ~ '^[0-9]{1,9}$' THEN (item.value->>'quantityRefunded')::integer END
 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(source.raw_payload->'items')='array' THEN source.raw_payload->'items' ELSE '[]'::jsonb END) WITH ORDINALITY item
 LEFT JOIN LATERAL (
   WITH RECURSIVE components AS (
     SELECT value FROM jsonb_array_elements(CASE WHEN jsonb_typeof(item.value->'breakdowns')='array' THEN item.value->'breakdowns' ELSE '[]'::jsonb END)
     UNION ALL
     SELECT child.value FROM components parent CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(parent.value->'breakdowns')='array' THEN parent.value->'breakdowns' ELSE '[]'::jsonb END) child
     WHERE parent.value->>'breakdownType' NOT IN ('Principal','OurPricePrincipal')
   )
   SELECT min(value->'breakdownAmount'->>'currencyCode') currency,
     CASE WHEN count(DISTINCT value->'breakdownAmount'->>'currencyCode')=1
       THEN -sum((value->'breakdownAmount'->>'currencyAmount')::numeric) END amount
   FROM components WHERE value->>'breakdownType' IN ('Principal','OurPricePrincipal')
     AND value->'breakdownAmount'->>'currencyAmount' ~ '^-?[0-9]+(\.[0-9]+)?$'
 ) amount ON true;
END $$;
CREATE FUNCTION refresh_amazon_sku_refunds() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM project_amazon_sku_refunds(NEW); RETURN NEW; END $$;
CREATE TRIGGER amazon_sku_refund_projection AFTER INSERT OR UPDATE OF raw_payload,posted_date,transaction_type
 ON amazon_financial_transactions FOR EACH ROW EXECUTE FUNCTION refresh_amazon_sku_refunds();
-- Reuse retained evidence; no API backfill or per-order calls required.
DO $$ DECLARE tenant UUID; previous_tenant TEXT:=current_setting('app.tenant_id',true); BEGIN
 FOR tenant IN SELECT id FROM tenants LOOP
  PERFORM set_config('app.tenant_id',tenant::text,true);
  PERFORM project_amazon_sku_refunds(t) FROM amazon_financial_transactions t
   WHERE t.tenant_id=tenant AND (upper(transaction_type)='REFUND' OR lower(raw_payload->>'description')='refund order');
 END LOOP;
 PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END $$;
ALTER TABLE amazon_sku_refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE amazon_sku_refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON amazon_sku_refunds
 USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
