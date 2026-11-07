-- Session-local evidence helpers only; this is not a database migration.
CREATE OR REPLACE FUNCTION pg_temp.stock_layers(t uuid,item uuid) RETURNS jsonb LANGUAGE SQL STABLE AS $layers$
WITH positive AS (
 SELECT l.*,coalesce(sum(quantity) OVER(PARTITION BY location_id,expiration_date ORDER BY occurred_at,id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior_received
 FROM inventory_ledger_entries l WHERE tenant_id=t AND account_catalog_item_id=item AND quantity>0
), depleted AS (
 SELECT location_id,expiration_date,-coalesce(sum(quantity) FILTER(WHERE quantity<0),0) qty
 FROM inventory_ledger_entries WHERE tenant_id=t AND account_catalog_item_id=item GROUP BY location_id,expiration_date
), remaining AS (
 SELECT p.id,p.location_id,p.expiration_date,p.unit_cost,p.currency,p.cost_status,p.lot_number,p.deposit_fee_per_unit,p.other_fee_per_unit,p.occurred_at,
 p.quantity-least(p.quantity,greatest(coalesce(d.qty,0)-p.prior_received,0)) quantity
 FROM positive p LEFT JOIN depleted d ON d.location_id=p.location_id AND d.expiration_date IS NOT DISTINCT FROM p.expiration_date
)
SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY expiration_date NULLS LAST,occurred_at,id),'[]') FROM remaining r WHERE quantity>0
$layers$;
CREATE OR REPLACE FUNCTION pg_temp.stock_invoice(t uuid,item uuid) RETURNS jsonb LANGUAGE SQL STABLE AS $invoice$
SELECT coalesce(jsonb_agg(jsonb_build_object('line',to_jsonb(l),'document',to_jsonb(d)) ORDER BY l.id),'[]') FROM receiving_document_lines l JOIN receiving_documents d ON d.tenant_id=l.tenant_id AND d.id=l.receiving_document_id WHERE l.tenant_id=t AND l.account_catalog_item_id=item AND EXISTS(SELECT 1 FROM jsonb_each_text(l.raw_data)kv WHERE lower(kv.key) IN('upc','ean','gtin','barcode') AND nullif(trim(kv.value),'') IS NOT NULL);
$invoice$;
-- Caller owns the transaction. Set app.catalogue_repair to the reviewed JSON manifest.
-- Approved stocked-identity consolidation. Original stock/history is never rewritten.
SET LOCAL lock_timeout='3s';
SET LOCAL statement_timeout='15s';
DO $$
DECLARE
 m jsonb:=current_setting('app.catalogue_repair')::jsonb;
 t uuid:=(m->>'tenant')::uuid; old_item uuid:=(m->>'source')::uuid; new_item uuid:=(m->>'target')::uuid;
 old_global uuid; new_global uuid; actor uuid; before_maps jsonb; after_maps jsonb;
 ledger_before jsonb; reservations_before jsonb; legacy_sku text; n integer; before_parents jsonb; after_parents jsonb; prior jsonb; is_admin boolean; history_before jsonb; frozen_after jsonb; history_row jsonb; work_before jsonb; layers_before jsonb; target_layers_before jsonb; offers_before jsonb; offers_after jsonb; invoice_before jsonb; source_product jsonb; target_product jsonb; inserted_rows jsonb; lr jsonb; transfer_time timestamptz:=clock_timestamp(); seq integer:=0; transfer_qty numeric; checksum integer;
BEGIN
 IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) THEN RAISE EXCEPTION 'Privileged maintenance role required for global sharing proof'; END IF;
 IF EXISTS(SELECT 1 FROM unnest(ARRAY['tenant','source','target','sourceGlobal','targetGlobal','vendor','actor','barcode','itemCode','name','brand','reason','request','mappingCount','mappingRevision','parentRevision','ledgerRevision','reservationRevision'])k WHERE nullif(trim(m->>k),'') IS NULL) THEN RAISE EXCEPTION 'Complete reviewed manifest required'; END IF;
 IF m->>'request' !~ '^[A-Za-z0-9-]{10,100}$' THEN RAISE EXCEPTION 'Simple unique repair reference required'; END IF;
 IF old_item=new_item OR length(coalesce(m->>'reason',''))<15 OR length(coalesce(m->>'request',''))<10 THEN
  RAISE EXCEPTION 'Explicit distinct identities, repair reference and reason required'; END IF;
 PERFORM set_config('app.tenant_id',t::text,true);
 -- Match receiving lock order; fail closed rather than retry partial work.
 PERFORM pg_advisory_xact_lock(hashtextextended(t::text,0));
 PERFORM pg_advisory_xact_lock(193641,1);
 PERFORM id FROM account_catalog_items WHERE tenant_id=t AND id IN(old_item,new_item) ORDER BY id FOR UPDATE;
 IF (SELECT count(*) FROM account_catalog_items WHERE tenant_id=t AND id IN(old_item,new_item))<>2 THEN
  RAISE EXCEPTION 'Both items must belong to the reviewed account'; END IF;
 SELECT global_product_id,account_sku INTO old_global,legacy_sku FROM account_catalog_items WHERE tenant_id=t AND id=old_item;
 SELECT global_product_id INTO new_global FROM account_catalog_items WHERE tenant_id=t AND id=new_item;
 IF old_global<>(m->>'sourceGlobal')::uuid OR new_global<>(m->>'targetGlobal')::uuid THEN
  RAISE EXCEPTION 'Global identity drift'; END IF;
 PERFORM id FROM global_catalog_products WHERE id IN(old_global,new_global) ORDER BY id FOR UPDATE;
 SELECT u.id INTO actor FROM app_users u WHERE lower(u.email)=lower(m->>'actor') AND u.status='ACTIVE' AND (EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=u.id AND p.active) OR EXISTS(SELECT 1 FROM tenant_memberships x WHERE x.tenant_id=t AND x.user_id=u.id AND x.role IN('OWNER','ADMIN')));
 SELECT EXISTS(SELECT 1 FROM platform_administrators WHERE user_id=actor AND active) INTO is_admin;
 IF actor IS NULL THEN RAISE EXCEPTION 'Audit actor not found'; END IF;
 -- Repeated application is a verified no-op, not another mapping mutation.
 IF EXISTS(SELECT 1 FROM audit_events WHERE tenant_id=t AND action='CATALOGUE_STOCKED_RECONCILIATION' AND target_id=m->>'request') THEN
  IF NOT EXISTS(SELECT 1 FROM audit_events WHERE tenant_id=t AND action='CATALOGUE_STOCKED_RECONCILIATION' AND target_id=m->>'request' AND details->'manifest'=m) THEN RAISE EXCEPTION 'Repair reference reused with different manifest'; END IF;
  IF EXISTS(SELECT 1 FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item)
    OR (SELECT status FROM account_catalog_items WHERE tenant_id=t AND id=old_item)<>'INACTIVE'
    OR (SELECT status FROM global_catalog_products WHERE id=old_global)<>'ARCHIVED' THEN
   RAISE EXCEPTION 'Previously completed repair has drifted'; END IF;
  SELECT details INTO prior FROM audit_events WHERE tenant_id=t AND action='CATALOGUE_STOCKED_RECONCILIATION' AND target_id=m->>'request';
  IF (SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM marketplace_sku_mapping_components c WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(prior->'afterMappings')x)) IS DISTINCT FROM prior->'afterMappings'
    OR (SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM marketplace_sku_mappings p WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(prior->'afterParents')x)) IS DISTINCT FROM prior->'afterParents' THEN RAISE EXCEPTION 'Previously repaired mappings changed'; END IF;
  IF (SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.order_line_id,f.account_catalog_item_id),'[]') FROM shopify_order_components f WHERE f.tenant_id=t AND f.order_line_id IN(SELECT (r#>>'{line,id}')::uuid FROM jsonb_array_elements(prior->'shopifyHistoryBefore')r)) IS DISTINCT FROM prior->'shopifyFrozenAfter' THEN RAISE EXCEPTION 'Previously preserved Shopify history changed'; END IF;
  IF (SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id),'[]') FROM inventory_ledger_entries l WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(prior->'insertedLedger')x)) IS DISTINCT FROM prior->'insertedLedger'
   OR EXISTS(SELECT 1 FROM inventory_ledger_entries WHERE tenant_id=t AND account_catalog_item_id=old_item GROUP BY location_id,expiration_date HAVING sum(quantity)<>0) THEN RAISE EXCEPTION 'Previously transferred stock changed'; END IF;
  RETURN;
 END IF;
 IF EXISTS(SELECT 1 FROM global_product_identifiers WHERE global_product_id=old_global)
  OR NOT EXISTS(SELECT 1 FROM global_product_identifiers WHERE global_product_id=new_global
    AND identifier_type IN('UPC','EAN','GTIN') AND identifier_value=m->>'barcode')
  OR legacy_sku IS DISTINCT FROM m->>'itemCode'
  OR NOT (m ?& ARRAY['sourceUnit','targetUnit','sourceSize','targetSize'])
  OR EXISTS(SELECT 1 FROM global_catalog_products a JOIN global_catalog_products b ON b.id=new_global WHERE a.id=old_global
     AND (a.unit_of_measure IS DISTINCT FROM m->>'sourceUnit' OR b.unit_of_measure IS DISTINCT FROM m->>'targetUnit'
       OR a.package_size IS DISTINCT FROM m->>'sourceSize' OR b.package_size IS DISTINCT FROM m->>'targetSize'))
  OR NOT EXISTS(SELECT 1 FROM vendor_catalog_offers WHERE tenant_id=t AND account_catalog_item_id=new_item
    AND vendor_id=(m->>'vendor')::uuid AND vendor_item_code=m->>'itemCode' AND effective_to IS NULL)
  OR EXISTS(SELECT 1 FROM account_catalog_items WHERE global_product_id=old_global AND id<>old_item)
  OR EXISTS(SELECT 1 FROM global_product_vendor_codes WHERE global_product_id=old_global)
  OR EXISTS(SELECT 1 FROM global_product_packaging_versions WHERE global_product_id=old_global AND status='ACTIVE')
  OR (SELECT count(*) FROM global_catalog_products WHERE id IN(old_global,new_global)
     AND canonical_name=m->>'name' AND brand=m->>'brand' AND status='ACTIVE')<>2
  OR (SELECT count(*) FROM account_catalog_items WHERE tenant_id=t AND id IN(old_item,new_item) AND status='ACTIVE')<>2 THEN
  RAISE EXCEPTION 'Reviewed barcode, vendor, product or account identity mismatch'; END IF;
 -- Include BOTH providers: Shopify reservations share this table.
 IF EXISTS(SELECT 1 FROM order_inventory_reservations WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item) AND status='ACTIVE')
  OR EXISTS(SELECT 1 FROM amazon_orders o JOIN amazon_order_items i ON i.tenant_id=o.tenant_id AND i.marketplace_connection_id=o.marketplace_connection_id AND i.amazon_order_id=o.amazon_order_id
    JOIN marketplace_sku_mappings p ON p.tenant_id=i.tenant_id AND p.marketplace_connection_id=i.marketplace_connection_id AND p.marketplace_sku=i.seller_sku
    JOIN marketplace_sku_mapping_components c ON c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id
    WHERE c.tenant_id=t AND c.account_catalog_item_id IN(old_item,new_item) AND upper(coalesce(o.fulfillment_channel,'')) NOT IN('AFN','AMAZON','FBA')
      AND regexp_replace(upper(coalesce(o.order_status,'')),'[^A-Z]','','g') NOT IN('SHIPPED','SHIPPEDDELIVEREDTOBUYER','SHIPPEDPICKEDUP','CANCELED','CANCELLED'))
  OR EXISTS(SELECT 1 FROM order_inventory_reservations r JOIN inventory_ledger_entries l ON l.tenant_id=r.tenant_id AND l.id=r.cost_layer_id
    WHERE l.tenant_id=t AND l.account_catalog_item_id=old_item AND r.status='ACTIVE')
  OR EXISTS(SELECT 1 FROM shopify_order_components WHERE tenant_id=t AND account_catalog_item_id=old_item)
  OR EXISTS(SELECT 1 FROM purchase_order_items WHERE tenant_id=t AND account_catalog_item_id=old_item AND status IN('OPEN','PARTIALLY_RECEIVED'))
  OR EXISTS(SELECT 1 FROM receiving_document_lines l JOIN receiving_documents d ON d.tenant_id=l.tenant_id AND d.id=l.receiving_document_id
     WHERE l.tenant_id=t AND l.account_catalog_item_id=old_item AND d.status NOT IN('RECEIVED','REJECTED')
       AND coalesce(l.ordered_quantity,0)>l.received_quantity)
 THEN
  RAISE EXCEPTION 'Stock, active commitments or unfinished receiving require a separate consolidation'; END IF;
 -- Pin explicit invoice, policy, supplier and cost-layer evidence before any mutation.
 SELECT jsonb_build_object('sourceInvoices',pg_temp.stock_invoice(t,old_item),'targetInvoices',pg_temp.stock_invoice(t,new_item),'purchases',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM purchase_order_items r WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item))) INTO work_before;
 IF work_before IS DISTINCT FROM m->'workRows' THEN RAISE EXCEPTION 'Reviewed purchase or invoice history changed'; END IF;
 SELECT to_jsonb(p) INTO source_product FROM global_catalog_products p WHERE id=old_global;
 SELECT to_jsonb(p) INTO target_product FROM global_catalog_products p WHERE id=new_global;
 IF (SELECT to_jsonb(a) FROM account_catalog_items a WHERE tenant_id=t AND id=old_item) IS DISTINCT FROM m->'sourceAccount' OR (SELECT to_jsonb(a) FROM account_catalog_items a WHERE tenant_id=t AND id=new_item) IS DISTINCT FROM m->'targetAccount' THEN RAISE EXCEPTION 'Reviewed account metadata changed'; END IF;
 IF source_product IS DISTINCT FROM m->'sourceProduct' OR target_product IS DISTINCT FROM m->'targetProduct'
 OR source_product->'requires_expiration_date' IS DISTINCT FROM target_product->'requires_expiration_date'
 OR m->>'mode' IS DISTINCT FROM 'FINAL_LAYER_TRANSFER' THEN RAISE EXCEPTION 'Reviewed stocked identity or expiration policy changed'; END IF;
 IF m->>'barcode' !~ '^([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})$' THEN RAISE EXCEPTION 'Valid invoice GTIN required'; END IF;
 SELECT sum(substring(m->>'barcode' FROM pos FOR 1)::integer*CASE WHEN (length(m->>'barcode')-pos)%2=1 THEN 3 ELSE 1 END) INTO checksum FROM generate_series(1,length(m->>'barcode'))pos;
 IF checksum%10<>0 THEN RAISE EXCEPTION 'Invoice GTIN checksum mismatch'; END IF;
 SELECT jsonb_build_object('source',pg_temp.stock_invoice(t,old_item),'target',pg_temp.stock_invoice(t,new_item)) INTO invoice_before;
 IF invoice_before IS DISTINCT FROM m->'invoiceRows' OR jsonb_array_length(invoice_before->'source')=0 OR jsonb_array_length(invoice_before->'target')=0 THEN RAISE EXCEPTION 'Both reviewed invoice identities required'; END IF;
 IF EXISTS(SELECT 1 FROM (SELECT value r FROM jsonb_array_elements(invoice_before->'source') UNION ALL SELECT value FROM jsonb_array_elements(invoice_before->'target'))docs CROSS JOIN LATERAL jsonb_each_text(r#>'{line,raw_data}')kv
 WHERE r#>>'{document,document_type}' IS DISTINCT FROM 'INVOICE' OR r#>>'{document,status}'='REJECTED' OR r#>>'{document,vendor_id}' IS DISTINCT FROM m->>'vendor' OR r#>>'{line,vendor_item_code}' IS DISTINCT FROM m->>'itemCode'
 OR (lower(kv.key) IN('upc','ean','gtin','barcode') AND nullif(trim(kv.value),'') IS NOT NULL AND (translate(kv.value,'=" '||chr(9)||chr(10)||chr(13),'') !~ '^[0-9]+$' OR length(regexp_replace(kv.value,'[^0-9]','','g')) NOT IN(8,12,13,14) OR lpad(regexp_replace(kv.value,'[^0-9]','','g'),14,'0')<>lpad(m->>'barcode',14,'0')))) THEN RAISE EXCEPTION 'Conflicting stocked invoice identity'; END IF;
 PERFORM id FROM vendor_catalog_offers WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item) ORDER BY id FOR UPDATE;
 SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]') INTO offers_before FROM vendor_catalog_offers o WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item);
 IF offers_before IS DISTINCT FROM m->'offers' OR EXISTS(SELECT 1 FROM vendor_catalog_offers WHERE tenant_id=t AND account_catalog_item_id=old_item AND (vendor_id<>(m->>'vendor')::uuid OR vendor_item_code IS DISTINCT FROM m->>'itemCode' OR packaging_version_id IS NOT NULL OR (effective_to IS NULL AND effective_from>=current_date))) THEN RAISE EXCEPTION 'Reviewed supplier offers changed'; END IF;
 PERFORM id FROM inventory_ledger_entries WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item) ORDER BY id FOR UPDATE;
 layers_before:=pg_temp.stock_layers(t,old_item);target_layers_before:=pg_temp.stock_layers(t,new_item);
 IF layers_before IS DISTINCT FROM m->'sourceLayers' OR target_layers_before IS DISTINCT FROM m->'targetLayers' OR jsonb_array_length(layers_before)=0
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(layers_before)x WHERE x->>'cost_status'<>'FINAL' OR x->>'unit_cost' IS NULL OR x->>'currency' IS NULL)
 THEN RAISE EXCEPTION 'Final known-cost layers required; provisional costs need a separate propagation contract'; END IF;
 IF EXISTS(SELECT 1 FROM inventory_ledger_entries WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item) GROUP BY account_catalog_item_id,location_id,expiration_date HAVING sum(quantity)<0)
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(layers_before)a JOIN jsonb_array_elements(target_layers_before)b ON a->'location_id'=b->'location_id' AND a->'expiration_date'=b->'expiration_date') THEN RAISE EXCEPTION 'Negative stock or overlapping FIFO layers require separate review'; END IF;
 IF EXISTS(SELECT 1 FROM inventory_ledger_entries WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item) AND occurred_at>=transfer_time) THEN RAISE EXCEPTION 'Future inventory evidence cannot be reordered'; END IF;
 PERFORM id FROM marketplace_sku_mappings WHERE tenant_id=t AND id IN
   (SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item)
   ORDER BY id FOR UPDATE;
 PERFORM id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item) ORDER BY id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM marketplace_sku_mappings p WHERE p.tenant_id=t AND p.account_catalog_item_id=old_item AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=t AND c.marketplace_sku_mapping_id=p.id AND c.account_catalog_item_id=old_item)) THEN RAISE EXCEPTION 'Parent-only mapping requires separate review'; END IF;
 SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) INTO before_parents FROM marketplace_sku_mappings p WHERE tenant_id=t AND id IN(SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item);
 IF md5(coalesce(before_parents,'[]')::text) IS DISTINCT FROM m->>'parentRevision' THEN RAISE EXCEPTION 'Reviewed parent mapping revision changed'; END IF;
 SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) INTO before_maps FROM marketplace_sku_mapping_components c WHERE tenant_id=t AND account_catalog_item_id=old_item;
 IF jsonb_array_length(coalesce(before_maps,'[]'))<>(m->>'mappingCount')::integer
  OR EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c JOIN marketplace_sku_mappings p ON p.tenant_id=c.tenant_id AND p.id=c.marketplace_sku_mapping_id
    JOIN marketplace_connections x ON x.tenant_id=p.tenant_id AND x.id=p.marketplace_connection_id
    WHERE c.tenant_id=t AND c.account_catalog_item_id=old_item AND (p.status<>'ACTIVE' OR x.channel NOT IN('AMAZON','SHOPIFY')))
  OR EXISTS(SELECT 1 FROM marketplace_sku_mapping_components a JOIN marketplace_sku_mapping_components b ON b.tenant_id=a.tenant_id AND b.marketplace_sku_mapping_id=a.marketplace_sku_mapping_id
    WHERE a.tenant_id=t AND a.account_catalog_item_id=old_item AND b.account_catalog_item_id=new_item) THEN
  RAISE EXCEPTION 'Mapping count/provider/status/component conflict'; END IF;
 IF (SELECT jsonb_object_agg(counts.channel,counts.provider_count) FROM (SELECT x.channel,count(*) provider_count FROM marketplace_sku_mapping_components c
    JOIN marketplace_sku_mappings p ON p.tenant_id=c.tenant_id AND p.id=c.marketplace_sku_mapping_id
    JOIN marketplace_connections x ON x.tenant_id=p.tenant_id AND x.id=p.marketplace_connection_id
    WHERE c.tenant_id=t AND c.account_catalog_item_id=old_item GROUP BY x.channel) counts)
    IS DISTINCT FROM m->'providerCounts' THEN RAISE EXCEPTION 'Reviewed provider counts changed'; END IF;
 -- Lock every affected historical order/line/variant before capturing its basis.
 PERFORM o.id FROM shopify_orders o WHERE o.tenant_id=t AND o.id IN(
   SELECT l.order_id FROM shopify_order_lines l JOIN marketplace_sku_mappings p ON p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id AND p.shopify_variant_id=l.variant_id
   JOIN marketplace_sku_mapping_components c ON c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id WHERE c.tenant_id=t AND c.account_catalog_item_id=old_item) ORDER BY o.id FOR UPDATE;
 PERFORM l.id FROM shopify_order_lines l WHERE l.tenant_id=t AND l.order_id IN(SELECT o.id FROM shopify_orders o WHERE o.tenant_id=t AND o.id IN(
   SELECT z.order_id FROM shopify_order_lines z JOIN marketplace_sku_mappings p ON p.tenant_id=z.tenant_id AND p.marketplace_connection_id=z.marketplace_connection_id AND p.shopify_variant_id=z.variant_id
   JOIN marketplace_sku_mapping_components c ON c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id WHERE c.tenant_id=t AND c.account_catalog_item_id=old_item)) ORDER BY l.id FOR UPDATE;
 PERFORM v.id FROM shopify_variants v JOIN marketplace_sku_mappings p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.shopify_variant_id=v.id
   WHERE p.tenant_id=t AND p.id IN(SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item) ORDER BY v.id FOR UPDATE OF v;
 SELECT coalesce(jsonb_agg(jsonb_build_object('order',to_jsonb(o),'line',to_jsonb(l),'variant',to_jsonb(v),'mapping',to_jsonb(p),
    'parts',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM marketplace_sku_mapping_components c WHERE c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id),
    'frozen',(SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.account_catalog_item_id),'[]') FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id)) ORDER BY l.id),'[]')
 INTO history_before FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id
 JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
 JOIN marketplace_sku_mappings p ON p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id AND p.shopify_variant_id=l.variant_id
 WHERE p.tenant_id=t AND EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id AND c.account_catalog_item_id=old_item);
 IF history_before IS DISTINCT FROM m->'shopifyHistory' THEN RAISE EXCEPTION 'Reviewed Shopify history changed'; END IF;
 FOR history_row IN SELECT value FROM jsonb_array_elements(history_before) LOOP
   IF history_row#>>'{order,fulfillment_status}' IS DISTINCT FROM 'FULFILLED'
     OR history_row#>'{order,cancelled_at}' IS DISTINCT FROM 'null'::jsonb
     OR history_row#>>'{order,evidence_complete}' IS DISTINCT FROM 'true'
     OR history_row#>>'{line,inventory_state}' IS DISTINCT FROM 'REPORTING_ONLY'
     OR history_row#>>'{line,stock_scope}' IS DISTINCT FROM 'REPORTING_ONLY'
     OR history_row#>>'{line,unfulfilled_quantity}' IS DISTINCT FROM '0'
     OR history_row#>'{frozen}' IS DISTINCT FROM '[]'::jsonb
     OR history_row#>>'{mapping,status}' IS DISTINCT FROM 'ACTIVE'
     OR history_row#>>'{mapping,shopify_mapping_hash}' IS DISTINCT FROM history_row#>>'{variant,mapping_hash}'
     OR jsonb_array_length(coalesce(history_row->'parts','[]'))=0 THEN
       RAISE EXCEPTION 'Shopify order dependency is not complete fulfilled reporting-only history'; END IF;
 END LOOP;
 IF md5(coalesce(before_maps,'[]')::text) IS DISTINCT FROM m->>'mappingRevision' THEN
  RAISE EXCEPTION 'Reviewed mapping revision changed'; END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id),'[]') INTO ledger_before FROM inventory_ledger_entries l WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item);
 SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') INTO reservations_before FROM order_inventory_reservations r WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item);
 IF md5(ledger_before::text) IS DISTINCT FROM m->>'ledgerRevision' OR md5(reservations_before::text) IS DISTINCT FROM m->>'reservationRevision' THEN
  RAISE EXCEPTION 'Reviewed stock/history revision changed'; END IF;
 -- Append paired current-time corrections, preserving each remaining layer exactly.
 FOR lr IN SELECT value FROM jsonb_array_elements(layers_before) LOOP
   seq:=seq+1;transfer_qty:=(lr->>'quantity')::numeric;
   INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,lot_number,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status,deposit_fee_per_unit,other_fee_per_unit)
   SELECT t,v.item,(lr->>'location_id')::uuid,'TRANSFER',v.q,lr->>'lot_number',(lr->>'expiration_date')::date,(lr->>'unit_cost')::numeric,lr->>'currency','CATALOGUE_RECONCILIATION',(lr->>'id')::uuid,transfer_time+seq*interval '1 microsecond',m->>'request'||':'||v.direction||':'||(lr->>'id'),'Verified catalogue identity consolidation; original layer retained',actor,lr->>'cost_status',(lr->>'deposit_fee_per_unit')::numeric,(lr->>'other_fee_per_unit')::numeric
   FROM (VALUES(old_item,-transfer_qty,'OUT'),(new_item,transfer_qty,'IN'))v(item,q,direction);
 END LOOP;
 SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id),'[]') INTO inserted_rows FROM inventory_ledger_entries l WHERE tenant_id=t AND source_type='CATALOGUE_RECONCILIATION' AND idempotency_key LIKE (m->>'request')||':%';
 IF jsonb_array_length(inserted_rows)<>2*jsonb_array_length(layers_before)
 OR EXISTS(SELECT 1 FROM inventory_ledger_entries WHERE tenant_id=t AND account_catalog_item_id=old_item GROUP BY location_id,expiration_date HAVING sum(quantity)<>0)
 OR EXISTS(SELECT 1 FROM inventory_ledger_entries WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(inserted_rows)x) GROUP BY location_id,expiration_date,unit_cost,currency,cost_status,lot_number,deposit_fee_per_unit,other_fee_per_unit HAVING sum(quantity)<>0) THEN RAISE EXCEPTION 'Batch or valuation conservation failed'; END IF;
 -- End only the obsolete current offer versions; retain IDs, cost and receipt history.
 UPDATE vendor_catalog_offers SET effective_to=current_date-1 WHERE tenant_id=t AND account_catalog_item_id=old_item AND effective_to IS NULL;
 SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]') INTO offers_after FROM vendor_catalog_offers o WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item);
 FOR history_row IN SELECT value FROM jsonb_array_elements(history_before) LOOP
   INSERT INTO shopify_order_components(tenant_id,marketplace_connection_id,order_line_id,account_catalog_item_id,quantity)
   SELECT t,(history_row#>>'{line,marketplace_connection_id}')::uuid,(history_row#>>'{line,id}')::uuid,
     (part->>'account_catalog_item_id')::uuid,sum((part->>'quantity')::numeric)
   FROM jsonb_array_elements(history_row->'parts') part GROUP BY part->>'account_catalog_item_id';
 END LOOP;
 SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.order_line_id,f.account_catalog_item_id),'[]') INTO frozen_after FROM shopify_order_components f
 WHERE f.tenant_id=t AND f.order_line_id IN(SELECT (r#>>'{line,id}')::uuid FROM jsonb_array_elements(history_before)r);
 IF (SELECT coalesce(jsonb_agg(jsonb_build_object('line',line_id,'item',item_id,'quantity',qty) ORDER BY line_id,item_id),'[]') FROM (
     SELECT (r#>>'{line,id}')::uuid line_id,(part->>'account_catalog_item_id')::uuid item_id,sum((part->>'quantity')::numeric) qty
     FROM jsonb_array_elements(history_before)r CROSS JOIN LATERAL jsonb_array_elements(r->'parts')part GROUP BY line_id,item_id) expected)
   IS DISTINCT FROM (SELECT coalesce(jsonb_agg(jsonb_build_object('line',f.order_line_id,'item',f.account_catalog_item_id,'quantity',f.quantity) ORDER BY f.order_line_id,f.account_catalog_item_id),'[]') FROM shopify_order_components f WHERE f.tenant_id=t AND f.order_line_id IN(SELECT (r#>>'{line,id}')::uuid FROM jsonb_array_elements(history_before)r)) THEN RAISE EXCEPTION 'Historical composition quantity preservation failed'; END IF;
 -- Raw order/line/variant observations are never rewritten by this operation.
 FOR history_row IN SELECT value FROM jsonb_array_elements(history_before) LOOP
   IF history_row->'order' IS DISTINCT FROM (SELECT to_jsonb(o) FROM shopify_orders o WHERE o.tenant_id=t AND o.id=(history_row#>>'{order,id}')::uuid)
     OR history_row->'line' IS DISTINCT FROM (SELECT to_jsonb(l) FROM shopify_order_lines l WHERE l.tenant_id=t AND l.id=(history_row#>>'{line,id}')::uuid)
     OR history_row->'variant' IS DISTINCT FROM (SELECT to_jsonb(v) FROM shopify_variants v WHERE v.tenant_id=t AND v.id=(history_row#>>'{variant,id}')::uuid) THEN
       RAISE EXCEPTION 'Shopify source history mutation'; END IF;
 END LOOP;
 UPDATE marketplace_sku_mappings SET account_catalog_item_id=new_item WHERE tenant_id=t AND account_catalog_item_id=old_item
   AND id IN(SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item);
 UPDATE marketplace_sku_mapping_components SET account_catalog_item_id=new_item WHERE tenant_id=t AND account_catalog_item_id=old_item;
 GET DIAGNOSTICS n=ROW_COUNT;
 IF n<>(m->>'mappingCount')::integer THEN RAISE EXCEPTION 'Unexpected mapping write count'; END IF;
 SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) INTO after_maps FROM marketplace_sku_mapping_components c
   WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(before_maps)x);
 IF (SELECT jsonb_agg(x-'account_catalog_item_id'-'updated_at' ORDER BY x->>'id') FROM jsonb_array_elements(before_maps)x)
   IS DISTINCT FROM (SELECT jsonb_agg(x-'account_catalog_item_id'-'updated_at' ORDER BY x->>'id') FROM jsonb_array_elements(after_maps)x) THEN
  RAISE EXCEPTION 'Mapping quantities or metadata changed'; END IF;
 SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) INTO after_parents FROM marketplace_sku_mappings p WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(before_parents)x);
 IF (SELECT jsonb_agg(x-'account_catalog_item_id'-'updated_at' ORDER BY x->>'id') FROM jsonb_array_elements(before_parents)x) IS DISTINCT FROM (SELECT jsonb_agg(x-'account_catalog_item_id'-'updated_at' ORDER BY x->>'id') FROM jsonb_array_elements(after_parents)x)
   OR EXISTS(SELECT 1 FROM marketplace_sku_mappings WHERE tenant_id=t AND account_catalog_item_id=old_item) THEN RAISE EXCEPTION 'Parent mapping preservation failed'; END IF;
 UPDATE account_catalog_items SET status='INACTIVE' WHERE tenant_id=t AND id=old_item;
 UPDATE global_catalog_products SET status='ARCHIVED' WHERE id=old_global;
 IF ledger_before IS DISTINCT FROM (SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id),'[]') FROM inventory_ledger_entries l WHERE tenant_id=t AND id IN(SELECT (x->>'id')::uuid FROM jsonb_array_elements(ledger_before)x))
 OR reservations_before IS DISTINCT FROM (SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM order_inventory_reservations r WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item)) THEN
  RAISE EXCEPTION 'Historical stock/reservation mutation'; END IF;
 INSERT INTO audit_events(tenant_id,actor_user_id,actor_is_super_admin,action,target_type,target_id,reason,details)
 VALUES(t,actor,is_admin,'CATALOGUE_STOCKED_RECONCILIATION','ACCOUNT_CATALOG_ITEM',m->>'request',m->>'reason',
 jsonb_build_object('manifest',m,'beforeMappings',before_maps,'afterMappings',after_maps,'beforeParents',before_parents,'afterParents',after_parents,
  'workBefore',work_before,'sourceLayers',layers_before,'targetLayers',target_layers_before,'insertedLedger',inserted_rows,'offersBefore',offers_before,'offersAfter',offers_after,'invoiceRows',invoice_before,'ledgerRevision',md5(ledger_before::text),'reservationRevision',md5(reservations_before::text),
  'shopifyHistoryBefore',history_before,'shopifyFrozenAfter',frozen_after,'historyUnchanged',true,'quantitiesUnchanged',true,'publicationInvalidationRetained',true));
END $$;
