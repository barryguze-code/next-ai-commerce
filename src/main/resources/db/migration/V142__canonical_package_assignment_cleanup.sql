-- Use each account's ordinary RLS context, including restricted schema-owner deployments.
DO $$ DECLARE account_id UUID; previous_scope TEXT:=current_setting('app.tenant_id',true); BEGIN
FOR account_id IN SELECT id FROM tenants LOOP
PERFORM set_config('app.tenant_id',account_id::text,true);
-- One-time display cleanup. Amounts, dated rates, source orders and printed snapshots are untouched.
-- Flatten previous rename chains using their existing account-owned links; never guess by prefix.
WITH RECURSIVE path AS (
 SELECT tenant_id,source_key,display_name,ARRAY[source_key] seen,0 depth FROM packing_package_aliases WHERE tenant_id=account_id
 UNION ALL
 SELECT p.tenant_id,p.source_key,a.display_name,p.seen||a.source_key,p.depth+1
 FROM path p JOIN packing_package_aliases a ON a.tenant_id=p.tenant_id AND a.source_key=packing_package_key(p.display_name)
 WHERE p.depth<32 AND NOT a.source_key=ANY(p.seen) AND a.display_name<>p.display_name
), final AS (
 SELECT DISTINCT ON(tenant_id,source_key) tenant_id,source_key,display_name FROM path ORDER BY tenant_id,source_key,depth DESC
)
UPDATE packing_package_aliases a SET display_name=f.display_name,updated_at=now()
FROM final f WHERE a.tenant_id=f.tenant_id AND a.source_key=f.source_key AND a.display_name<>f.display_name;

-- Internal FedEx labels inherit only an already-established mapping in that account.
-- Generic UPS is deliberately not guessed: actual packing evidence identifies the specific UPS package.
INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name,cost_routed,independent_rate_only)
SELECT a.tenant_id,packing_package_key(v.code),a.display_name,a.cost_name,a.cost_routed,a.independent_rate_only
FROM (VALUES ('FEDEX_XSMALL','xsmall'),('FEDEX_PAK','pak'),('FEDEX_SMALL','small'),('FEDEX_MEDIUM','medium'),('FEDEX_LARGE','large'),('FEDEX_XLARGE','xlarge')) v(code,seed)
JOIN packing_package_aliases a ON a.source_key=v.seed
WHERE a.tenant_id=account_id AND a.display_name<>v.code
ON CONFLICT(tenant_id,source_key) DO NOTHING;

UPDATE profit_sku_packages p SET description=a.display_name
FROM packing_package_aliases a WHERE p.tenant_id=account_id AND a.tenant_id=p.tenant_id AND a.source_key=packing_package_key(p.description)
 AND p.description IN ('FEDEX_XSMALL','FEDEX_PAK','FEDEX_SMALL','FEDEX_MEDIUM','FEDEX_LARGE','FEDEX_XLARGE');

-- Resolve a generic UPS label only when a single-package SKU has explicit profile/packing evidence.
WITH evidence AS (
 SELECT p.tenant_id,p.marketplace_connection_id,p.seller_sku,p.sequence,
 packing_package_display(p.tenant_id,coalesce(profile.name,packing.packaging)) destination
 FROM profit_sku_packages p
 JOIN amazon_listings l ON l.tenant_id=p.tenant_id AND l.marketplace_connection_id=p.marketplace_connection_id AND l.seller_sku=p.seller_sku
 LEFT JOIN marketplace_sku_package_defaults d ON d.tenant_id=p.tenant_id AND d.marketplace_connection_id=p.marketplace_connection_id AND d.seller_sku=p.seller_sku
 LEFT JOIN shipping_package_profiles profile ON profile.tenant_id=d.tenant_id AND profile.id=d.package_profile_id AND profile.status='ACTIVE'
 LEFT JOIN LATERAL (
  SELECT t.packaging FROM temporary_order_packaging_lookup t WHERE t.tenant_id=p.tenant_id AND t.marketplace_connection_id=p.marketplace_connection_id
   AND (t.order_sku_qty_list=p.seller_sku||'-1' OR (nullif(l.asin,'') IS NOT NULL AND t.order_item_summary=l.asin||'-1'))
  ORDER BY CASE WHEN t.order_sku_qty_list=p.seller_sku||'-1' THEN 0 ELSE 1 END,t.imported_at DESC,t.amazon_order_id LIMIT 1
 ) packing ON true
 WHERE p.tenant_id=account_id AND p.description='UPS'
 AND (SELECT count(*) FROM profit_sku_packages other WHERE other.tenant_id=p.tenant_id AND other.marketplace_connection_id=p.marketplace_connection_id AND other.seller_sku=p.seller_sku)=1
)
UPDATE profit_sku_packages p SET description=e.destination FROM evidence e
WHERE p.tenant_id=e.tenant_id AND p.marketplace_connection_id=e.marketplace_connection_id AND p.seller_sku=e.seller_sku AND p.sequence=e.sequence
 AND nullif(e.destination,'') IS NOT NULL AND e.destination<>'UPS'
 AND EXISTS (SELECT 1 FROM packing_package_aliases a WHERE a.tenant_id=p.tenant_id AND a.display_name=e.destination);

-- Saved package rows preserve the complete shipping-cost override, so their obsolete enum is redundant.
UPDATE amazon_listings l SET profit_package_type=NULL
WHERE l.tenant_id=account_id AND l.profit_package_type IS NOT NULL AND EXISTS (
 SELECT 1 FROM profit_sku_packages p
 WHERE p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id AND p.seller_sku=l.seller_sku
);

END LOOP;
PERFORM set_config('app.tenant_id',coalesce(previous_scope,''),true);
END $$;
