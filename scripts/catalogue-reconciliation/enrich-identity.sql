CREATE OR REPLACE FUNCTION pg_temp.metadata_protected(t uuid,item uuid) RETURNS jsonb LANGUAGE SQL STABLE AS $protected$
SELECT jsonb_build_object('ledger',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM inventory_ledger_entries r WHERE tenant_id=t AND account_catalog_item_id=item),
 'reservations',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM order_inventory_reservations r WHERE tenant_id=t AND account_catalog_item_id=item),
 'parents',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM marketplace_sku_mappings r WHERE tenant_id=t AND (account_catalog_item_id=item OR id IN(SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=item))),
 'components',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM marketplace_sku_mapping_components r WHERE tenant_id=t AND account_catalog_item_id=item),
 'offers',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM vendor_catalog_offers r WHERE tenant_id=t AND account_catalog_item_id=item),
 'receiving',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM receiving_document_lines r WHERE tenant_id=t AND account_catalog_item_id=item),
 'purchases',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM purchase_order_items r WHERE tenant_id=t AND account_catalog_item_id=item))
$protected$;
CREATE OR REPLACE FUNCTION pg_temp.metadata_invoice(t uuid,item uuid) RETURNS jsonb LANGUAGE SQL STABLE AS $invoice$
SELECT coalesce(jsonb_agg(jsonb_build_object('line',to_jsonb(l),'document',to_jsonb(d)) ORDER BY l.id),'[]') FROM receiving_document_lines l JOIN receiving_documents d ON d.tenant_id=l.tenant_id AND d.id=l.receiving_document_id WHERE l.tenant_id=t AND l.account_catalog_item_id=item AND EXISTS(SELECT 1 FROM jsonb_each_text(l.raw_data)kv WHERE lower(kv.key) IN('upc','ean','gtin','barcode') AND nullif(trim(kv.value),'') IS NOT NULL)
$invoice$;
SET LOCAL lock_timeout='3s';
SET LOCAL statement_timeout='15s';
DO $$
DECLARE m jsonb:=current_setting('app.catalogue_repair')::jsonb;
 t uuid:=(m->>'tenant')::uuid; item uuid:=(m->>'source')::uuid; old_global uuid:=(m#>>'{sourceProduct,id}')::uuid;
 target_global uuid:=(m#>>'{targetProduct,id}')::uuid; actor uuid; before_data jsonb; docs jsonb; prior jsonb; checksum integer; aliases_before jsonb; aliases_after jsonb; vendor_scope text; vendor_key_value text;
BEGIN
 IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) THEN RAISE EXCEPTION 'Privileged maintenance role required'; END IF;
 IF m->>'mode' NOT IN('ADD_IDENTIFIER','LINK_GLOBAL') OR length(coalesce(m->>'request',''))<10 OR length(coalesce(m->>'reason',''))<15 THEN RAISE EXCEPTION 'Explicit reviewed metadata operation required'; END IF;
 IF m->>'barcode' !~ '^([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})$' THEN RAISE EXCEPTION 'Valid GTIN required'; END IF;
 SELECT sum(substring(m->>'barcode' FROM pos FOR 1)::integer*CASE WHEN (length(m->>'barcode')-pos)%2=1 THEN 3 ELSE 1 END) INTO checksum FROM generate_series(1,length(m->>'barcode'))pos;
 IF checksum%10<>0 THEN RAISE EXCEPTION 'GTIN checksum mismatch'; END IF;
 PERFORM set_config('app.tenant_id',t::text,true);
 PERFORM pg_advisory_xact_lock(hashtextextended(t::text,0));PERFORM pg_advisory_xact_lock(193641,1);
 PERFORM id FROM account_catalog_items WHERE tenant_id=t AND id=item FOR UPDATE;
 PERFORM id FROM global_catalog_products WHERE id IN(old_global,target_global) ORDER BY id FOR UPDATE;
 SELECT u.id INTO actor FROM app_users u WHERE lower(u.email)=lower(m->>'actor') AND u.status='ACTIVE' AND (EXISTS(SELECT 1 FROM platform_administrators WHERE user_id=u.id AND active) OR EXISTS(SELECT 1 FROM tenant_memberships WHERE tenant_id=t AND user_id=u.id AND role IN('OWNER','ADMIN')));
 IF actor IS NULL THEN RAISE EXCEPTION 'Authorized audit actor required'; END IF;
 SELECT details INTO prior FROM audit_events WHERE tenant_id=t AND action='CATALOGUE_IDENTITY_ENRICHMENT' AND target_id=m->>'request';
 IF prior IS NOT NULL THEN
   IF prior->'manifest' IS DISTINCT FROM m OR prior->'accountAfter' IS DISTINCT FROM (SELECT to_jsonb(r) FROM account_catalog_items r WHERE tenant_id=t AND id=item)
      OR NOT EXISTS(SELECT 1 FROM global_product_identifiers WHERE global_product_id=coalesce(target_global,old_global) AND identity_key=catalog_identifier_key('UPC',m->>'barcode')) THEN RAISE EXCEPTION 'Completed metadata repair changed'; END IF;
   RETURN;
 END IF;
 IF m->'sourceAccount' IS DISTINCT FROM (SELECT to_jsonb(r) FROM account_catalog_items r WHERE tenant_id=t AND id=item)
    OR m->'sourceProduct' IS DISTINCT FROM (SELECT to_jsonb(r) FROM global_catalog_products r WHERE id=old_global)
    OR m#>>'{sourceAccount,status}' IS DISTINCT FROM 'ACTIVE' OR m#>>'{sourceProduct,status}' IS DISTINCT FROM 'ACTIVE'
    OR m#>>'{sourceAccount,global_product_id}' IS DISTINCT FROM old_global::text
    OR (SELECT count(*) FROM account_catalog_items WHERE global_product_id=old_global)<>1
    OR EXISTS(SELECT 1 FROM global_product_identifiers WHERE global_product_id=old_global) THEN RAISE EXCEPTION 'Reviewed source identity changed'; END IF;
 PERFORM id FROM vendors WHERE tenant_id=t AND id=(m->>'vendor')::uuid FOR SHARE;
 SELECT catalog_vendor_scope(tenant_id,distribution_center),upper(regexp_replace(coalesce(vendor_code,name),'[^A-Za-z0-9]','','g')) INTO vendor_scope,vendor_key_value FROM vendors WHERE tenant_id=t AND id=(m->>'vendor')::uuid;
 PERFORM id FROM global_product_vendor_codes WHERE global_product_id=old_global ORDER BY id FOR UPDATE;
 SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id),'[]') INTO aliases_before FROM global_product_vendor_codes c WHERE global_product_id=old_global;
 IF aliases_before IS DISTINCT FROM m->'supplierAliases' OR EXISTS(SELECT 1 FROM global_product_vendor_codes WHERE global_product_id=old_global AND (source_tenant_id IS DISTINCT FROM t OR catalog_scope IS DISTINCT FROM vendor_scope OR vendor_key IS DISTINCT FROM vendor_key_value OR vendor_item_code IS DISTINCT FROM m->>'itemCode')) THEN RAISE EXCEPTION 'Supplier alias ownership or evidence conflict'; END IF;
 docs:=pg_temp.metadata_invoice(t,item);before_data:=pg_temp.metadata_protected(t,item);
 IF docs IS DISTINCT FROM m->'invoiceRows' OR jsonb_array_length(docs)=0 OR md5(before_data::text) IS DISTINCT FROM m->>'protectedRevision' THEN RAISE EXCEPTION 'Reviewed invoice or operational evidence changed'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(docs)r CROSS JOIN LATERAL jsonb_each_text(r#>'{line,raw_data}')kv
     WHERE (r#>>'{document,document_type}' IS DISTINCT FROM 'INVOICE' OR r#>>'{document,status}'='REJECTED'
       OR r#>>'{document,vendor_id}' IS DISTINCT FROM m->>'vendor' OR r#>>'{line,vendor_item_code}' IS DISTINCT FROM m->>'itemCode'
       OR r#>>'{line,vendor_item_code}' IS DISTINCT FROM m#>>'{sourceAccount,account_sku}'
       OR (lower(kv.key) IN('upc','ean','gtin','barcode') AND nullif(trim(kv.value),'') IS NOT NULL
           AND (translate(kv.value,'=" '||chr(9)||chr(10)||chr(13),'') !~ '^[0-9]+$' OR length(regexp_replace(kv.value,'[^0-9]','','g')) NOT IN(8,12,13,14) OR lpad(regexp_replace(kv.value,'[^0-9]','','g'),14,'0')<>lpad(m->>'barcode',14,'0'))))) THEN RAISE EXCEPTION 'Conflicting invoice identity'; END IF;
 IF NOT EXISTS(SELECT 1 FROM vendor_catalog_offers WHERE tenant_id=t AND account_catalog_item_id=item AND vendor_id=(m->>'vendor')::uuid AND vendor_item_code=m->>'itemCode' AND effective_to IS NULL) THEN RAISE EXCEPTION 'Current account supplier identity required'; END IF;
 IF m->>'mode'='ADD_IDENTIFIER' THEN
   IF target_global IS NOT NULL OR EXISTS(SELECT 1 FROM global_product_identifiers WHERE identity_key=catalog_identifier_key('UPC',m->>'barcode')) THEN RAISE EXCEPTION 'Barcode already belongs to another identity'; END IF;
   INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) VALUES(old_global,'UPC',m->>'barcode',true);
   UPDATE global_catalog_products SET updated_at=now() WHERE id=old_global;
 ELSE
   IF target_global IS NULL OR target_global=old_global OR m->'targetProduct' IS DISTINCT FROM (SELECT to_jsonb(r) FROM global_catalog_products r WHERE id=target_global)
     OR m#>>'{targetProduct,status}' IS DISTINCT FROM 'ACTIVE' OR m#>>'{sourceProduct,canonical_name}' IS DISTINCT FROM m#>>'{targetProduct,canonical_name}'
     OR m#>>'{sourceProduct,brand}' IS DISTINCT FROM m#>>'{targetProduct,brand}' OR m#>'{sourceProduct,requires_expiration_date}' IS DISTINCT FROM m#>'{targetProduct,requires_expiration_date}'
     OR NOT EXISTS(SELECT 1 FROM global_product_identifiers WHERE global_product_id=target_global AND identity_key=catalog_identifier_key('UPC',m->>'barcode'))
     OR EXISTS(SELECT 1 FROM account_catalog_items WHERE tenant_id=t AND global_product_id=target_global)
     OR EXISTS(SELECT 1 FROM global_product_packaging_versions WHERE global_product_id=old_global AND status='ACTIVE') THEN RAISE EXCEPTION 'Canonical identity or operational semantics conflict'; END IF;
   UPDATE global_product_vendor_codes SET global_product_id=target_global WHERE global_product_id=old_global;
   UPDATE account_catalog_items SET global_product_id=target_global WHERE tenant_id=t AND id=item;
   UPDATE global_catalog_products SET status='ARCHIVED' WHERE id=old_global;
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id),'[]') INTO aliases_after FROM global_product_vendor_codes c WHERE c.id IN(SELECT (r->>'id')::uuid FROM jsonb_array_elements(aliases_before)r);
 IF (SELECT jsonb_agg(r-'global_product_id' ORDER BY r->>'id') FROM jsonb_array_elements(aliases_before)r) IS DISTINCT FROM (SELECT jsonb_agg(r-'global_product_id' ORDER BY r->>'id') FROM jsonb_array_elements(aliases_after)r) THEN RAISE EXCEPTION 'Supplier branch metadata changed'; END IF;
 IF before_data IS DISTINCT FROM pg_temp.metadata_protected(t,item) OR (SELECT to_jsonb(r)-'global_product_id'-'updated_at' FROM account_catalog_items r WHERE tenant_id=t AND id=item) IS DISTINCT FROM (m->'sourceAccount')-'global_product_id'-'updated_at' THEN RAISE EXCEPTION 'Operational history mutation'; END IF;
 INSERT INTO audit_events(tenant_id,actor_user_id,actor_is_super_admin,action,target_type,target_id,reason,details)
 VALUES(t,actor,EXISTS(SELECT 1 FROM platform_administrators WHERE user_id=actor AND active),'CATALOGUE_IDENTITY_ENRICHMENT','ACCOUNT_CATALOG_ITEM',m->>'request',m->>'reason',jsonb_build_object('manifest',m,'accountAfter',(SELECT to_jsonb(r) FROM account_catalog_items r WHERE tenant_id=t AND id=item),'protectedRevision',md5(before_data::text),'supplierAliasesBefore',aliases_before,'supplierAliasesAfter',aliases_after,'historyUnchanged',true,'inventoryIdsUnchanged',true));
END $$;
