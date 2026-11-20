-- Deferred and released transactions are two lifecycle records of one refund.
-- Preserve both audit records; suppress only an explicitly linked deferred item.
CREATE FUNCTION amazon_finance_identifier(payload jsonb, name text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT nullif(entry->>'relatedIdentifierValue','')
 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(payload->'relatedIdentifiers')='array'
   THEN payload->'relatedIdentifiers' ELSE '[]'::jsonb END) entry
 WHERE entry->>'relatedIdentifierName'=name LIMIT 1
$$;
CREATE FUNCTION amazon_refund_item_identifier(payload jsonb, item_index integer) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT nullif(entry->>'itemRelatedIdentifierValue','')
 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(payload->'items'->(item_index-1)->'relatedIdentifiers')='array'
  THEN payload->'items'->(item_index-1)->'relatedIdentifiers' ELSE '[]'::jsonb END) entry
 WHERE entry->>'itemRelatedIdentifierName'='ORDER_ADJUSTMENT_ITEM_ID' LIMIT 1
$$;
CREATE INDEX amazon_refund_deferred_reference_idx ON amazon_financial_transactions
 (tenant_id,marketplace_connection_id,amazon_finance_identifier(raw_payload,'DEFERRED_TRANSACTION_ID'))
 WHERE upper(transaction_type)='REFUND';
CREATE VIEW canonical_amazon_sku_refunds WITH (security_invoker=true) AS
 SELECT refund.* FROM amazon_sku_refunds refund
 JOIN amazon_financial_transactions source ON source.tenant_id=refund.tenant_id
  AND source.marketplace_connection_id=refund.marketplace_connection_id AND source.id=refund.transaction_id
 WHERE NOT EXISTS (
  SELECT 1 FROM amazon_financial_transactions released
  JOIN amazon_sku_refunds replacement ON replacement.tenant_id=released.tenant_id
   AND replacement.marketplace_connection_id=released.marketplace_connection_id AND replacement.transaction_id=released.id
  WHERE released.tenant_id=source.tenant_id AND released.marketplace_connection_id=source.marketplace_connection_id
   AND released.id<>source.id AND upper(released.transaction_type)='REFUND'
   AND released.raw_payload->>'transactionStatus'='RELEASED'
   AND (released.transaction_key=amazon_finance_identifier(source.raw_payload,'RELEASE_TRANSACTION_ID')
    OR amazon_finance_identifier(released.raw_payload,'DEFERRED_TRANSACTION_ID')=source.transaction_key)
   AND (amazon_refund_item_identifier(source.raw_payload,refund.item_index)=amazon_refund_item_identifier(released.raw_payload,replacement.item_index)
    OR (amazon_refund_item_identifier(source.raw_payload,refund.item_index) IS NULL
     AND amazon_refund_item_identifier(released.raw_payload,replacement.item_index) IS NULL
     AND replacement.item_index=refund.item_index))
   AND replacement.seller_sku IS NOT DISTINCT FROM refund.seller_sku
 );
