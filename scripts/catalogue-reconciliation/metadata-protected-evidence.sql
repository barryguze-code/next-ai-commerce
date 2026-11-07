SELECT jsonb_build_object('ledger',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM inventory_ledger_entries r WHERE tenant_id=t AND account_catalog_item_id=item),
 'reservations',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM order_inventory_reservations r WHERE tenant_id=t AND account_catalog_item_id=item),
 'parents',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM marketplace_sku_mappings r WHERE tenant_id=t AND (account_catalog_item_id=item OR id IN(SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=item))),
 'components',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM marketplace_sku_mapping_components r WHERE tenant_id=t AND account_catalog_item_id=item),
 'offers',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM vendor_catalog_offers r WHERE tenant_id=t AND account_catalog_item_id=item),
 'receiving',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM receiving_document_lines r WHERE tenant_id=t AND account_catalog_item_id=item),
 'purchases',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM purchase_order_items r WHERE tenant_id=t AND account_catalog_item_id=item)) FROM (SELECT ?::uuid t,?::uuid item) scoped;
