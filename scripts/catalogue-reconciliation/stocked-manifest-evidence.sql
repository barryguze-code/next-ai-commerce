WITH ids AS(SELECT ?::uuid t,?::uuid old_item,?::uuid new_item)
SELECT jsonb_build_object('tenant',t,'source',old_item,'target',new_item,
 'sourceGlobal',a.global_product_id,'targetGlobal',b.global_product_id,
 'sourceAccount',to_jsonb(a),'targetAccount',to_jsonb(b),
 'sourceProduct',to_jsonb(p),'targetProduct',to_jsonb(q),'name',p.canonical_name,'brand',p.brand,
 'sourceUnit',p.unit_of_measure,'targetUnit',q.unit_of_measure,'sourceSize',p.package_size,'targetSize',q.package_size,
 'sourceLayers',pg_temp.stock_layers(t,old_item),'targetLayers',pg_temp.stock_layers(t,new_item),
 'invoiceRows',jsonb_build_object('source',pg_temp.stock_invoice(t,old_item),'target',pg_temp.stock_invoice(t,new_item)),
 'workRows',jsonb_build_object('sourceInvoices',pg_temp.stock_invoice(t,old_item),'targetInvoices',pg_temp.stock_invoice(t,new_item),'purchases',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM purchase_order_items r WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item))),
 'offers',(SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]') FROM vendor_catalog_offers o WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item)),
 'mappingCount',(SELECT count(*) FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item),
 'providerCounts',(SELECT jsonb_object_agg(channel,n) FROM(SELECT x.channel,count(*) n FROM marketplace_sku_mapping_components c JOIN marketplace_sku_mappings m ON m.tenant_id=c.tenant_id AND m.id=c.marketplace_sku_mapping_id JOIN marketplace_connections x ON x.tenant_id=m.tenant_id AND x.id=m.marketplace_connection_id WHERE c.tenant_id=t AND c.account_catalog_item_id=old_item GROUP BY x.channel)counts),
 'mappingRevision',(SELECT md5(coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id),'[]')::text) FROM marketplace_sku_mapping_components c WHERE tenant_id=t AND account_catalog_item_id=old_item),
 'parentRevision',(SELECT md5(coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.id),'[]')::text) FROM marketplace_sku_mappings m WHERE tenant_id=t AND id IN(SELECT marketplace_sku_mapping_id FROM marketplace_sku_mapping_components WHERE tenant_id=t AND account_catalog_item_id=old_item)),
 'ledgerRevision',(SELECT md5(coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.id),'[]')::text) FROM inventory_ledger_entries l WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item)),
 'reservationRevision',(SELECT md5(coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]')::text) FROM order_inventory_reservations r WHERE tenant_id=t AND account_catalog_item_id IN(old_item,new_item)),
 'shopifyHistory',(SELECT coalesce(jsonb_agg(jsonb_build_object('order',to_jsonb(o),'line',to_jsonb(l),'variant',to_jsonb(v),'mapping',to_jsonb(p),
    'parts',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM marketplace_sku_mapping_components c WHERE c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id),
    'frozen',(SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.account_catalog_item_id),'[]') FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id)) ORDER BY l.id),'[]')
 FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id
 JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
 JOIN marketplace_sku_mappings p ON p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id AND p.shopify_variant_id=l.variant_id
 WHERE p.tenant_id=t AND EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id AND c.account_catalog_item_id=old_item))) FROM ids JOIN account_catalog_items a ON a.tenant_id=t AND a.id=old_item JOIN account_catalog_items b ON b.tenant_id=t AND b.id=new_item JOIN global_catalog_products p ON p.id=a.global_product_id JOIN global_catalog_products q ON q.id=b.global_product_id;
