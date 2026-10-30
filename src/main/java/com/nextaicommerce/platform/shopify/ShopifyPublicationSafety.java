package com.nextaicommerce.platform.shopify;

/** Shared warehouse holds for positive Amazon FBM and Shopify publications. SQL aliases are code-owned. */
public final class ShopifyPublicationSafety {
 private ShopifyPublicationSafety(){}
 private static final String PARTS="""
   SELECT p.account_catalog_item_id FROM shopify_order_components p
   JOIN shopify_order_lines prior ON prior.tenant_id=p.tenant_id AND prior.marketplace_connection_id=p.marketplace_connection_id AND prior.id=p.order_line_id
   WHERE prior.tenant_id=line.tenant_id AND prior.marketplace_connection_id=line.marketplace_connection_id
     AND (prior.id=line.id OR prior.variant_id=line.variant_id)
   UNION
   SELECT p.account_catalog_item_id FROM marketplace_sku_mappings saved
   JOIN marketplace_sku_mapping_components p ON p.tenant_id=saved.tenant_id AND p.marketplace_sku_mapping_id=saved.id
   WHERE saved.tenant_id=line.tenant_id AND saved.marketplace_connection_id=line.marketplace_connection_id AND saved.shopify_variant_id=line.variant_id
   """;
 /** Unknown identity/evidence holds the account; known unresolved components hold every overlapping offer. */
 public static String allowsPositive(String tenant,String mapping){return """
  NOT EXISTS (
   SELECT 1 FROM shopify_orders o
   JOIN marketplace_connections store ON store.tenant_id=o.tenant_id AND store.id=o.marketplace_connection_id AND store.channel='SHOPIFY' AND store.status='ACTIVE'
   WHERE o.tenant_id=
  """+tenant+"""
    AND NOT o.is_test AND o.cancelled_at IS NULL AND o.payment_status<>'VOIDED'
    AND (NOT o.evidence_complete OR o.review_reason IS NOT NULL OR EXISTS(
     SELECT 1 FROM shopify_order_lines line
     LEFT JOIN shopify_variants variant ON variant.tenant_id=line.tenant_id AND variant.marketplace_connection_id=line.marketplace_connection_id AND variant.id=line.variant_id AND variant.variant_gid=line.variant_gid
     WHERE line.tenant_id=o.tenant_id AND line.marketplace_connection_id=o.marketplace_connection_id AND line.order_id=o.id
      AND line.requires_shipping AND line.unfulfilled_quantity>0 AND line.ownership<>'EXTERNAL'
      AND (line.ownership<>'MERCHANT' OR variant.id IS NULL OR (
       (line.stock_scope<>'LIVE' OR line.inventory_state<>'READY_TO_SHIP' OR line.reserve_units<>line.unfulfilled_quantity
         OR line.review_reason IS NOT NULL OR line.inventory_revision IS DISTINCT FROM line.source_updated_at
         OR line.inventory_hash IS DISTINCT FROM line.observation_hash
         OR NOT EXISTS(SELECT 1 FROM shopify_order_components p WHERE p.tenant_id=line.tenant_id AND p.marketplace_connection_id=line.marketplace_connection_id AND p.order_line_id=line.id)
         OR EXISTS(SELECT 1 FROM shopify_order_components p WHERE p.tenant_id=line.tenant_id AND p.marketplace_connection_id=line.marketplace_connection_id AND p.order_line_id=line.id
            AND p.quantity*greatest(line.reserve_units-line.local_pending_units,0)<>(SELECT coalesce(sum(r.quantity),0) FROM order_inventory_reservations r WHERE r.tenant_id=line.tenant_id AND r.marketplace_connection_id=line.marketplace_connection_id AND r.shopify_order_line_id=line.id AND r.account_catalog_item_id=p.account_catalog_item_id AND r.status='ACTIVE')))
       AND (
        EXISTS(SELECT 1 FROM (
  """+PARTS+"""
        ) held JOIN marketplace_sku_mapping_components target ON target.account_catalog_item_id=held.account_catalog_item_id
          WHERE target.tenant_id=line.tenant_id AND target.marketplace_sku_mapping_id=
  """+mapping+"""
        )
        OR (NOT EXISTS(
  """+PARTS+"""
        ) AND (
          line.inventory_revision IS DISTINCT FROM line.source_updated_at OR line.inventory_hash IS DISTINCT FROM line.observation_hash
          OR line.stock_scope<>'REPORTING_ONLY' OR line.reserve_units<>0 OR line.local_handoff_units<>0 OR line.local_pending_units<>0 OR line.review_reason IS NOT NULL
          OR EXISTS(SELECT 1 FROM marketplace_sku_mappings saved WHERE saved.tenant_id=line.tenant_id AND saved.marketplace_connection_id=line.marketplace_connection_id AND saved.shopify_variant_id=line.variant_id)
          OR EXISTS(SELECT 1 FROM shopify_inventory_publications managed WHERE managed.tenant_id=line.tenant_id AND managed.marketplace_connection_id=line.marketplace_connection_id AND (managed.variant_id=line.variant_id OR managed.inventory_item_gid=variant.inventory_item_gid))
          OR EXISTS(SELECT 1 FROM shopify_order_cutovers cut JOIN shopify_order_lines prior ON prior.tenant_id=cut.tenant_id AND prior.marketplace_connection_id=cut.marketplace_connection_id AND prior.id=cut.order_line_id WHERE prior.tenant_id=line.tenant_id AND prior.marketplace_connection_id=line.marketplace_connection_id AND prior.variant_id=line.variant_id)
          OR EXISTS(SELECT 1 FROM order_inventory_reservations r JOIN shopify_order_lines prior ON prior.tenant_id=r.tenant_id AND prior.marketplace_connection_id=r.marketplace_connection_id AND prior.id=r.shopify_order_line_id WHERE prior.tenant_id=line.tenant_id AND prior.marketplace_connection_id=line.marketplace_connection_id AND prior.variant_id=line.variant_id)
        ))
       )
      ))
    ))
  )
  """;}
}
