-- Components are the canonical read model for marketplace mappings. Repair active
-- single-product mappings created by legacy or interrupted writes before V29.
INSERT INTO marketplace_sku_mapping_components
    (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order)
SELECT mapping.tenant_id,mapping.id,mapping.account_catalog_item_id,
       greatest(mapping.quantity_per_marketplace_unit,1),0
FROM marketplace_sku_mappings mapping
WHERE mapping.status='ACTIVE'
  AND mapping.account_catalog_item_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM marketplace_sku_mapping_components component
      WHERE component.tenant_id=mapping.tenant_id
        AND component.marketplace_sku_mapping_id=mapping.id
  )
ON CONFLICT (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id) DO NOTHING;
