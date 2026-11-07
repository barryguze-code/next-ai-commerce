SELECT coalesce(jsonb_agg(jsonb_build_object('order',to_jsonb(o),'line',to_jsonb(l),'variant',to_jsonb(v),'mapping',to_jsonb(p),
    'parts',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM marketplace_sku_mapping_components c WHERE c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id),
    'frozen',(SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.account_catalog_item_id),'[]') FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id)) ORDER BY l.id),'[]')
 FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id
 JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
 JOIN marketplace_sku_mappings p ON p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id AND p.shopify_variant_id=l.variant_id
 WHERE p.tenant_id=?::uuid AND EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=p.tenant_id AND c.marketplace_sku_mapping_id=p.id AND c.account_catalog_item_id=?::uuid);
