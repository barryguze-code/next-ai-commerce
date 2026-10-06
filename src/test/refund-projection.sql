-- Run after V98 inside a transaction and ROLLBACK. Uses one existing local store.
CREATE TEMP TABLE refund_test_source AS
SELECT tenant_id,id AS connection_id FROM marketplace_connections LIMIT 1;
INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,posted_date,raw_payload)
SELECT tenant_id,connection_id,'codex-refund-projection-test','Refund',now(),
'{"items":[{"contexts":[{"contextType":"ProductContext","sku":"REFUND-TEST","quantityShipped":4}],"breakdowns":[{"breakdownType":"ProductCharges","breakdownAmount":{"currencyCode":"USD","currencyAmount":-43.97},"breakdowns":[{"breakdownType":"Principal","breakdownAmount":{"currencyCode":"USD","currencyAmount":-43.97}}]},{"breakdownType":"Commission","breakdownAmount":{"currencyCode":"USD","currencyAmount":6.60}}]}]}'::jsonb
FROM refund_test_source;
DO $$ BEGIN
 IF (SELECT item_refund_amount FROM amazon_sku_refunds WHERE seller_sku='REFUND-TEST')<>43.97 THEN RAISE EXCEPTION 'Principal extraction failed'; END IF;
 IF (SELECT refunded_units FROM amazon_sku_refunds WHERE seller_sku='REFUND-TEST') IS NOT NULL THEN RAISE EXCEPTION 'Shipped units must not become refunded units'; END IF;
END $$;
UPDATE amazon_financial_transactions SET raw_payload=raw_payload WHERE transaction_key='codex-refund-projection-test';
DO $$ BEGIN
 IF (SELECT count(*) FROM amazon_sku_refunds WHERE seller_sku='REFUND-TEST')<>1 THEN RAISE EXCEPTION 'Replay duplicated refund'; END IF;
END $$;
UPDATE amazon_financial_transactions SET transaction_type='Shipment' WHERE transaction_key='codex-refund-projection-test';
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM amazon_sku_refunds WHERE seller_sku='REFUND-TEST') THEN RAISE EXCEPTION 'Corrected source retained refund'; END IF;
END $$;
