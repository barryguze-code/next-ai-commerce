package com.nextaicommerce.platform.marketplace;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only product history. Current mappings never assert historical SKU ownership. */
@Repository
public class MarketplaceInventoryMovements {
 private final JdbcTemplate jdbc;
 public MarketplaceInventoryMovements(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Component(UUID id,String name,String code,BigDecimal quantity){}
 public record Movement(UUID id,UUID item,Instant occurredAt,String type,BigDecimal quantity,String shipment,String reference,String location,LocalDate expiration){}
 public record Result(List<Component> components,List<Movement> movements,boolean more){}
 @Transactional(readOnly=true)
 public Result read(UUID tenant,UUID connection,String sku,UUID component,boolean fba,int page){
  if(sku==null||sku.isBlank()||sku.length()>240||page<0||page>10000)throw new IllegalArgumentException("Choose a SKU and valid page.");
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  var components=jdbc.query("""
   SELECT c.account_catalog_item_id,coalesce(a.display_name,p.canonical_name),coalesce(offer.vendor_item_code,a.account_sku),sum(c.quantity)
   FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
   JOIN global_catalog_products p ON p.id=a.global_product_id
   LEFT JOIN LATERAL(SELECT vendor_item_code FROM vendor_catalog_offers o WHERE o.tenant_id=a.tenant_id AND o.account_catalog_item_id=a.id AND o.effective_to IS NULL ORDER BY o.is_default DESC,o.updated_at DESC LIMIT 1) offer ON true
   WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.marketplace_sku=? AND m.status='ACTIVE'
   GROUP BY c.account_catalog_item_id,a.display_name,p.canonical_name,a.account_sku,offer.vendor_item_code ORDER BY c.account_catalog_item_id
   """,(r,n)->new Component(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBigDecimal(4)),tenant,connection,sku);
  if(component!=null&&components.stream().noneMatch(c->c.id().equals(component)))throw new IllegalArgumentException("The mapped product changed. Reopen inventory movements.");
  if(components.isEmpty())return new Result(components,List.of(),false);
  var items=components.stream().filter(c->component==null||c.id().equals(component)).map(c->c.id().toString()).toArray(String[]::new);
  // Page on the existing tenant/product/time index before resolving source details.
  var rows=jdbc.query("""
   WITH entries AS (SELECT e.* FROM inventory_ledger_entries e
    WHERE e.tenant_id=? AND e.account_catalog_item_id=ANY(?::uuid[])
   """+(fba?" AND e.entry_type IN ('FBA_DEPLETION','FBA_DEPLETION_ADJUSTMENT') AND e.source_type='FBA_SHIPMENT' AND e.marketplace_connection_id=? ":"")+"""
    ORDER BY e.occurred_at DESC,e.created_at DESC,e.id DESC LIMIT 101 OFFSET ?)
   SELECT e.id,e.account_catalog_item_id,e.occurred_at,e.entry_type,e.quantity,d.shipment_id,coalesce(d.shipment_id,ao.amazon_order_id,so.order_name,po.po_number,doc.document_number),l.code,e.expiration_date
   FROM entries e LEFT JOIN fba_shipment_depletions d ON e.source_type='FBA_SHIPMENT'
    AND d.tenant_id=e.tenant_id AND d.marketplace_connection_id=e.marketplace_connection_id AND d.id=e.source_id
   LEFT JOIN amazon_orders ao ON e.source_type='AMAZON_ORDER' AND ao.tenant_id=e.tenant_id AND ao.id=e.source_id
   LEFT JOIN shopify_orders so ON e.source_type='SHOPIFY_ORDER' AND so.tenant_id=e.tenant_id AND so.marketplace_connection_id=e.marketplace_connection_id AND so.id=e.source_id
   LEFT JOIN receiving_line_receipts receipt ON e.source_type='RECEIVING' AND receipt.tenant_id=e.tenant_id AND receipt.id=e.source_id
   LEFT JOIN purchase_order_items poi ON poi.tenant_id=receipt.tenant_id AND poi.id=receipt.purchase_order_item_id
   LEFT JOIN purchase_orders po ON po.tenant_id=poi.tenant_id AND po.id=poi.purchase_order_id
   LEFT JOIN receiving_documents doc ON doc.tenant_id=po.tenant_id AND doc.id=po.receiving_document_id
   LEFT JOIN warehouse_locations l ON l.tenant_id=e.tenant_id AND l.id=e.location_id
   ORDER BY e.occurred_at DESC,e.created_at DESC,e.id DESC
   """,(r,n)->new Movement(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant(),r.getString(4),r.getBigDecimal(5),r.getString(6),r.getString(7),r.getString(8),r.getObject(9,LocalDate.class)),fba?new Object[]{tenant,items,connection,(long)page*100}:new Object[]{tenant,items,(long)page*100});
  return new Result(components,rows.stream().limit(100).toList(),rows.size()>100);
 }
}
