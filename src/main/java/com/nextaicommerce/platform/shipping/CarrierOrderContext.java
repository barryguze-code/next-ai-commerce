package com.nextaicommerce.platform.shipping;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Native Shopify read adapter into existing carrier DTOs. Never calls Amazon for a Shopify order. */
@Service
public class CarrierOrderContext {
 private final JdbcTemplate jdbc;private final BuyShippingService amazon;private final boolean enabled,labelTest;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private LocalShippingFixtures fixtures;
 public boolean simulatedFixture(UUID tenant,UUID store,String order){
  if(LocalShippingFixtures.identity(order)){if(fixtures==null)throw new IllegalStateException("Local shipping simulation is disabled.");fixtures.get(tenant,store,order);return true;}return false;
 }
 public CarrierOrderContext(JdbcTemplate jdbc,BuyShippingService amazon,boolean enabled){this(jdbc,amazon,enabled,false);}
 @org.springframework.beans.factory.annotation.Autowired
 public CarrierOrderContext(JdbcTemplate jdbc,BuyShippingService amazon,@org.springframework.beans.factory.annotation.Value("${app.shopify.shipping-sandbox-enabled:false}") boolean enabled,@org.springframework.beans.factory.annotation.Value("${app.fedex.sandbox-label-test-enabled:false}") boolean labelTest){this.jdbc=jdbc;this.amazon=amazon;this.enabled=enabled;this.labelTest=labelTest;}
 @Transactional(readOnly=true)
 public boolean shopify(UUID tenant,UUID store){scope(tenant);String channel=jdbc.query("SELECT channel FROM marketplace_connections WHERE tenant_id=? AND id=? AND status='ACTIVE'",rs->rs.next()?rs.getString(1):null,tenant,store);if(!Set.of("AMAZON","SHOPIFY").contains(channel==null?"":channel))throw new IllegalArgumentException("Choose an active Amazon or Shopify store in this account.");return "SHOPIFY".equals(channel);}
 @Transactional(readOnly=true)
 public BuyShippingService.Context context(UUID tenant,UUID store,String order){return readContext(tenant,store,order,false);}
 @Transactional(readOnly=true)
 public BuyShippingService.Context rateOnlyContext(UUID tenant,UUID store,String order){
  if(LocalShippingFixtures.identity(order))throw new IllegalArgumentException("Choose an imported order.");
  return readContext(tenant,store,order,true);
 }
 private BuyShippingService.Context readContext(UUID tenant,UUID store,String order,boolean rateOnly){
  boolean test=labelTest&&!rateOnly;
  if(simulatedFixture(tenant,store,order))return fixtures.context(tenant,store,order);
  if(!shopify(tenant,store)){if(rateOnly&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND platform_waiting_for_pickup)",Boolean.class,tenant,store,order)))throw new IllegalStateException("This order is already marked for pickup locally.");var source=amazon.context(tenant,store,order);if(rateOnly&&"Shipped".equals(source.order().status()))throw new IllegalStateException("Shipped orders cannot request quotes.");return test?testContext(source):source;}
  if(!enabled)throw new IllegalStateException("Shopify FedEx sandbox is disabled.");
  UUID id=UUID.fromString(order);
  var rows=jdbc.queryForList("SELECT * FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,id);
  if(rows.isEmpty())throw new IllegalArgumentException("Order is not available in this Shopify store.");var o=rows.getFirst();
  if(rateOnly&&("FULFILLED".equals(o.get("fulfillment_status"))||Boolean.TRUE.equals(o.get("platform_waiting_for_pickup"))))throw new IllegalStateException("Shipped orders cannot request quotes.");
  if(!Boolean.TRUE.equals(o.get("evidence_complete"))||o.get("review_reason")!=null||o.get("cancelled_at")!=null||Boolean.TRUE.equals(o.get("is_test"))
    ||Set.of("VOIDED","REFUNDED","EXPIRED").contains(Objects.toString(o.get("payment_status"),""))||(!test&&"FULFILLED".equals(o.get("fulfillment_status"))))throw new IllegalArgumentException("Complete, uncancelled merchant order evidence is required. Shipped records require explicit local sandbox label-test mode; refunded, voided or test orders are excluded.");
  var lines=jdbc.queryForList("SELECT * FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? ORDER BY line_gid",tenant,store,id);
  if(lines.stream().anyMatch(l->!"MERCHANT".equals(l.get("ownership"))||(!test&&!rateOnly&&l.get("review_reason")!=null)))throw new IllegalArgumentException("Verified merchant-managed order lines are required.");
  var items=lines.stream().map(l->new BuyShippingRepository.OrderItem(l.get("id").toString(),Objects.toString(l.get("seller_sku"),""),null,Objects.toString(l.get("title"),""),(Integer)l.get("ordered_quantity"),test?0:Math.max(0,(Integer)l.get("ordered_quantity")-(Integer)l.get("unfulfilled_quantity")))).toList();
  // Ship-from addresses belong to this account; never copy another account's sender.
  var senders=jdbc.query("SELECT * FROM merchant_ship_from_addresses WHERE tenant_id=? AND status='ACTIVE' ORDER BY is_default DESC,label,id",(r,n)->new BuyShippingRepository.Address(r.getObject("id",UUID.class),r.getString("label"),r.getString("contact_name"),r.getString("company_name"),r.getString("address_line_1"),r.getString("address_line_2"),r.getString("address_line_3"),r.getString("city"),r.getString("state_or_province_code"),r.getString("postal_code"),r.getString("country_code"),r.getString("phone"),r.getString("email"),r.getBoolean("is_default")),tenant);
  var info=new BuyShippingRepository.OrderInfo(order,test?"SandboxLabelTest":"Unshipped","MERCHANT",null,null,null,null,null);
  return new BuyShippingService.Context(new BuyShippingRepository.StoreSettings(null,"RATES_ONLY",true),info,items,senders,List.of(),null,List.of(),Map.of());
 }
 @Transactional(readOnly=true)
 public String shopifyOrderGid(UUID tenant,UUID store,String order){
  scope(tenant);return jdbc.query("SELECT order_gid FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",rs->rs.next()?rs.getString(1):null,tenant,store,UUID.fromString(order));
 }
 @Transactional(readOnly=true)
 public String reference(UUID tenant,UUID store,String order){
  if(!shopify(tenant,store))return order;
  return jdbc.queryForObject("SELECT order_name FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",String.class,tenant,store,UUID.fromString(order));
 }
 /** Freeze account identities and quantities from actual reserved stock, grouped by item/location. */
 @Transactional(readOnly=true)
 public List<CarrierLabelComposer.Line> accountPacking(UUID tenant,UUID store,String order){
  scope(tenant);if(!shopify(tenant,store))return amazonAccountPacking(tenant,store,order);
  UUID id=UUID.fromString(order);rateOnlyContext(tenant,store,order);
  var rows=jdbc.query("""
   SELECT CASE WHEN nullif(trim(item.account_sku),'') IS NOT NULL THEN item.account_sku ELSE code.item_code END,
     coalesce(g.brand,''),coalesce(nullif(item.display_name,''),g.canonical_name),loc.code,sum(r.quantity),item.id,loc.id
   FROM shopify_order_lines l
   JOIN shopify_order_components part ON part.tenant_id=l.tenant_id AND part.marketplace_connection_id=l.marketplace_connection_id AND part.order_line_id=l.id
   JOIN account_catalog_items item ON item.tenant_id=part.tenant_id AND item.id=part.account_catalog_item_id
   LEFT JOIN global_catalog_products g ON g.id=item.global_product_id
   LEFT JOIN LATERAL (
     SELECT CASE WHEN count(DISTINCT offer.vendor_item_code)=1 THEN min(offer.vendor_item_code) END item_code
     FROM vendor_catalog_offers offer JOIN vendors vendor ON vendor.tenant_id=offer.tenant_id AND vendor.id=offer.vendor_id
     WHERE offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id AND vendor.status='ACTIVE'
       AND offer.effective_from<=current_date AND (offer.effective_to IS NULL OR offer.effective_to>=current_date)
       AND nullif(trim(offer.vendor_item_code),'') IS NOT NULL
   ) code ON true
   JOIN order_inventory_reservations r ON r.tenant_id=l.tenant_id AND r.marketplace_connection_id=l.marketplace_connection_id AND r.shopify_order_line_id=l.id AND r.account_catalog_item_id=item.id AND r.status='ACTIVE'
   JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id
   WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.order_id=?
   GROUP BY item.id,item.account_sku,code.item_code,g.brand,item.display_name,g.canonical_name,loc.id,loc.code ORDER BY 1,loc.code,item.id,loc.id
   """,(r,n)->{
     String itemCode=r.getString(1),location=r.getString(4);
     return CarrierLabelComposer.Line.accountItem(r.getString(2),itemCode,r.getString(3),r.getBigDecimal(5),location,r.getObject(6,UUID.class),r.getObject(7,UUID.class));
   },tenant,store,id);
  if(rows.isEmpty())throw new IllegalStateException("Packing requires mapped items with reserved warehouse stock.");
  return rows;
 }
 private List<CarrierLabelComposer.Line> amazonAccountPacking(UUID tenant,UUID store,String order){
  rateOnlyContext(tenant,store,order);
  boolean incomplete=Boolean.TRUE.equals(jdbc.queryForObject("""
   WITH demand AS (
    SELECT l.id line_id,c.account_catalog_item_id,(l.quantity_ordered-l.quantity_shipped)*c.quantity quantity
    FROM amazon_order_items l JOIN marketplace_sku_mappings m ON m.tenant_id=l.tenant_id AND m.marketplace_connection_id=l.marketplace_connection_id AND m.marketplace_sku=l.seller_sku AND m.status='ACTIVE'
    JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
    WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.amazon_order_id=? AND l.quantity_ordered>l.quantity_shipped
   ), allocations AS (
    SELECT r.amazon_order_item_id line_id,r.account_catalog_item_id,sum(r.quantity) quantity FROM order_inventory_reservations r
    WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.amazon_order_id=? AND r.status='ACTIVE' GROUP BY r.amazon_order_item_id,r.account_catalog_item_id
   )
   SELECT EXISTS(SELECT 1 FROM amazon_order_items l WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.amazon_order_id=? AND l.quantity_ordered>l.quantity_shipped AND NOT EXISTS(SELECT 1 FROM demand d WHERE d.line_id=l.id))
    OR EXISTS(SELECT 1 FROM demand d FULL JOIN allocations a ON a.line_id=d.line_id AND a.account_catalog_item_id=d.account_catalog_item_id WHERE d.quantity IS DISTINCT FROM a.quantity)
   """,Boolean.class,tenant,store,order,tenant,store,order,tenant,store,order));
  if(incomplete)throw new IllegalStateException("Complete catalogue mapping and reserved warehouse stock for every remaining Amazon order item before buying shipping.");
  var rows=jdbc.query("""
   SELECT CASE WHEN nullif(trim(item.account_sku),'') IS NOT NULL THEN item.account_sku ELSE code.item_code END,
    coalesce(g.brand,''),coalesce(nullif(item.display_name,''),g.canonical_name),loc.code,sum(r.quantity),item.id,loc.id
   FROM order_inventory_reservations r JOIN amazon_order_items l ON l.tenant_id=r.tenant_id AND l.marketplace_connection_id=r.marketplace_connection_id AND l.id=r.amazon_order_item_id AND l.amazon_order_id=r.amazon_order_id
   JOIN account_catalog_items item ON item.tenant_id=r.tenant_id AND item.id=r.account_catalog_item_id
   LEFT JOIN global_catalog_products g ON g.id=item.global_product_id
   LEFT JOIN LATERAL (SELECT CASE WHEN count(DISTINCT offer.vendor_item_code)=1 THEN min(offer.vendor_item_code) END item_code
    FROM vendor_catalog_offers offer JOIN vendors v ON v.tenant_id=offer.tenant_id AND v.id=offer.vendor_id
    WHERE offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id AND v.status='ACTIVE'
      AND offer.effective_from<=current_date AND (offer.effective_to IS NULL OR offer.effective_to>=current_date) AND nullif(trim(offer.vendor_item_code),'') IS NOT NULL) code ON true
   JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id
   WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.amazon_order_id=? AND r.status='ACTIVE'
   GROUP BY item.id,item.account_sku,code.item_code,g.brand,item.display_name,g.canonical_name,loc.id,loc.code ORDER BY 1,loc.code,item.id,loc.id
   """,(r,n)->{String code=r.getString(1),location=r.getString(4);return CarrierLabelComposer.Line.accountItem(r.getString(2),code,r.getString(3),r.getBigDecimal(5),location,r.getObject(6,UUID.class),r.getObject(7,UUID.class));},tenant,store,order);
  if(rows.isEmpty())throw new IllegalStateException("This Amazon order has no reserved warehouse packing items.");return rows;
 }
 @Transactional(readOnly=true)
 public List<CarrierLabelComposer.Line> packing(UUID tenant,UUID store,String order){
  if(simulatedFixture(tenant,store,order))return fixtures.get(tenant,store,order).definition().packing();
  scope(tenant);UUID id=UUID.fromString(order);context(tenant,store,order);
  String packageName=jdbc.queryForObject("SELECT package_name FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",String.class,tenant,store,id);
  return jdbc.query("SELECT id,seller_sku,title,"+(labelTest?"ordered_quantity":"unfulfilled_quantity")+" units FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? AND "+(labelTest?"ordered_quantity":"unfulfilled_quantity")+">0 ORDER BY line_gid",(r,n)->{
   var allocations=jdbc.query("""
    SELECT coalesce(item.account_sku,'Item code unavailable'),r.quantity,r.expiration_date,loc.code
    FROM order_inventory_reservations r JOIN account_catalog_items item ON item.tenant_id=r.tenant_id AND item.id=r.account_catalog_item_id
    JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id
    WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.shopify_order_line_id=?
      AND (r.status='ACTIVE' OR (r.status='SHIPPED' AND EXISTS(SELECT 1 FROM shopify_orders o JOIN inventory_ledger_entries movement ON movement.tenant_id=o.tenant_id AND movement.marketplace_connection_id=o.marketplace_connection_id AND movement.source_id=o.id WHERE o.tenant_id=r.tenant_id AND o.marketplace_connection_id=r.marketplace_connection_id AND o.id=? AND o.platform_waiting_for_pickup AND movement.idempotency_key='shopify-local:'||o.local_handoff_id::text||':'||r.id::text))
        OR (? AND r.status='SHIPPED' AND EXISTS(SELECT 1 FROM inventory_ledger_entries movement WHERE movement.tenant_id=r.tenant_id AND movement.marketplace_connection_id=r.marketplace_connection_id AND movement.source_type='SHOPIFY_ORDER' AND movement.quantity<0 AND movement.idempotency_key LIKE 'shopify-shipment:%:'||r.id::text)))
    ORDER BY r.expiration_date NULLS LAST,loc.code,r.id
    """,(a,k)->new CarrierLabelComposer.Allocation(a.getString(1),a.getBigDecimal(2),a.getObject(3,java.time.LocalDate.class),a.getString(4)),tenant,store,r.getObject(1,UUID.class),id,labelTest);
   var parts=jdbc.queryForList("SELECT c.quantity,g.brand FROM shopify_order_components c JOIN account_catalog_items item ON item.tenant_id=c.tenant_id AND item.id=c.account_catalog_item_id LEFT JOIN global_catalog_products g ON g.id=item.global_product_id WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.order_line_id=? ORDER BY c.account_catalog_item_id",tenant,store,r.getObject(1,UUID.class));
   String brand=parts.stream().map(part->Objects.toString(part.get("brand"),"")).filter(value->!value.isBlank()).distinct().sorted().collect(java.util.stream.Collectors.joining(" / "));
   return CarrierLabelComposer.Line.mapped(brand,r.getString(2),r.getString(3),r.getInt(4),parts.stream().map(part->(java.math.BigDecimal)part.get("quantity")).toList(),packageName,allocations);
  },tenant,store,id);
 }
 private static BuyShippingService.Context testContext(BuyShippingService.Context source){
  if(!Set.of("Pending","Unshipped","PartiallyShipped","Shipped").contains(source.order().status()))throw new IllegalArgumentException("Choose an imported merchant order for the sandbox label test.");
  if(source.shipments().stream().anyMatch(s->Set.of("PURCHASED","PURCHASE_QUEUED","PURCHASE_IN_PROGRESS","PURCHASE_UNKNOWN").contains(s.state())))throw new IllegalStateException("A platform label already exists or needs review. Use its saved shipping history; do not create another label.");
  var o=source.order();var info=new BuyShippingRepository.OrderInfo(o.amazonOrderId(),"SandboxLabelTest",o.fulfillmentChannel(),o.marketplaceId(),o.earliestShip(),o.latestShip(),o.earliestDelivery(),o.latestDelivery());
  var items=source.items().stream().map(i->new BuyShippingRepository.OrderItem(i.orderItemId(),i.sellerSku(),i.asin(),i.title(),i.quantityOrdered(),0)).toList();
  return new BuyShippingService.Context(source.settings(),info,items,source.addresses(),source.profiles(),source.suggestedPackage(),source.shipments(),source.committedQuantities());
 }
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
}
