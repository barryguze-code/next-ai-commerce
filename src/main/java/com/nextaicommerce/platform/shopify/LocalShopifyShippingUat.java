package com.nextaicommerce.platform.shopify;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import com.nextaicommerce.platform.shipping.FedExSandboxRates;
import tools.jackson.databind.ObjectMapper;

/** Explicit local UAT rounds only. Original observations and all ledger movements remain intact. */
@Service
public class LocalShopifyShippingUat {
 private final JdbcTemplate jdbc;private final ShopifyOrderInventory inventory;private final ObjectMapper json;private final boolean enabled;
 public LocalShopifyShippingUat(JdbcTemplate jdbc,ShopifyOrderInventory inventory,ObjectMapper json,FedExSandboxRates rates,@Value("${app.fedex.local-shipping-uat.enabled:false}") boolean enabled,org.springframework.core.env.Environment env){this.jdbc=jdbc;this.inventory=inventory;this.json=json;this.enabled=enabled;if(enabled&&(!inventory.enabled()||rates.account()==null||env.getProperty("app.shopify.orders.polling-enabled",Boolean.class,false)||env.getProperty("app.shopify.orders.only-scheduling",Boolean.class,false)||env.getProperty("app.scheduling.enabled",Boolean.class,false)))throw new IllegalStateException("Local shipping UAT requires the guarded sandbox rate connection.");}
 public boolean enabled(){return enabled;}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 @Transactional(readOnly=true) public UUID cycle(UUID tenant,UUID store,UUID order){if(!enabled)return null;scope(tenant);var ids=jdbc.queryForList("SELECT id FROM local_shipping_fixtures WHERE tenant_id=? AND marketplace_connection_id=? AND definition->>'kind'='SHOPIFY_SHIPPING_UAT' AND definition->>'orderId'=? AND definition->>'active'='true'",UUID.class,tenant,store,order.toString());if(ids.size()>1)throw new IllegalStateException("Conflicting local UAT cycles.");return ids.isEmpty()?null:ids.getFirst();}
 public boolean active(UUID tenant,UUID store,UUID order){return cycle(tenant,store,order)!=null;}
 public static String shipmentKey(UUID order,UUID cycle){return "shopify-uat:"+order+":"+cycle;}
 @Transactional public UUID beginRound(UUID tenant,UUID store,UUID order,String actor){
  if(!enabled)throw new IllegalStateException("Local UAT resets are disabled.");inventory.lock(tenant);
  var rows=jdbc.queryForList("SELECT o.* FROM shopify_orders o JOIN marketplace_connections c ON c.tenant_id=o.tenant_id AND c.id=o.marketplace_connection_id AND c.channel='SHOPIFY' AND c.status='ACTIVE' WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND o.id=? FOR UPDATE OF o",tenant,store,order);
  if(rows.size()!=1)throw new IllegalArgumentException("Order is not in this Shopify store.");var source=rows.getFirst();
  if(!Boolean.TRUE.equals(source.get("evidence_complete"))||source.get("review_reason")!=null||source.get("cancelled_at")!=null||Boolean.TRUE.equals(source.get("is_test"))||Set.of("VOIDED","REFUNDED","EXPIRED").contains(source.get("payment_status")))throw new IllegalArgumentException("Complete uncancelled order evidence is required.");
  UUID old=cycle(tenant,store,order);
  if(old!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND (state IN ('PURCHASING','UNKNOWN') OR error_message='LOCAL_HANDOFF_PENDING'))",Boolean.class,tenant,store,shipmentKey(order,old))))throw new IllegalStateException("Review the previous carrier outcome before resetting this order.");
  if(old!=null&&!Boolean.TRUE.equals(source.get("platform_waiting_for_pickup")))return old;
  var original=old==null?json.createObjectNode():json.readTree(jdbc.queryForObject("SELECT definition::text FROM local_shipping_fixtures WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",String.class,tenant,store,old)).path("original").deepCopy();
  if(old==null){
   ((tools.jackson.databind.node.ObjectNode)original).set("order",json.readTree(jdbc.queryForObject("SELECT json_build_object('fulfillment_status',fulfillment_status,'platform_waiting_for_pickup',platform_waiting_for_pickup,'local_handoff_id',local_handoff_id,'local_handoff_at',local_handoff_at,'local_handoff_revision',local_handoff_revision,'local_handoff_actor',local_handoff_actor,'source_document_id',source_document_id,'source_updated_at',source_updated_at)::text FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",String.class,tenant,store,order)));
   ((tools.jackson.databind.node.ObjectNode)original).set("lines",json.readTree(jdbc.queryForObject("SELECT coalesce(json_agg(json_build_object('id',id,'stock_scope',stock_scope,'inventory_state',inventory_state,'reserve_units',reserve_units,'unfulfilled_quantity',unfulfilled_quantity,'local_handoff_units',local_handoff_units,'local_pending_units',local_pending_units)), '[]'::json)::text FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=?",String.class,tenant,store,order)));
  }
  var lines=jdbc.queryForList("SELECT id,ownership,current_quantity FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? ORDER BY id FOR UPDATE",tenant,store,order);if(lines.isEmpty()||lines.stream().anyMatch(l->!"MERCHANT".equals(l.get("ownership"))))throw new IllegalArgumentException("Merchant-managed lines are required for local shipping UAT.");
  if(old!=null)jdbc.update("UPDATE local_shipping_fixtures SET definition=jsonb_set(definition,'{active}','false') WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,old);
  UUID cycle=UUID.randomUUID();var doc=json.createObjectNode().put("kind","SHOPIFY_SHIPPING_UAT").put("orderId",order.toString()).put("active",true).put("startedAt",Instant.now().toString()).put("actor",actor);doc.set("original",original);
  jdbc.update("INSERT INTO local_shipping_fixtures(id,tenant_id,marketplace_connection_id,name,definition) VALUES(?,?,?,?,?::jsonb)",cycle,tenant,store,"Local UAT cycle "+order,json.writeValueAsString(doc));
  jdbc.update("UPDATE shopify_orders SET fulfillment_status='UNFULFILLED',platform_waiting_for_pickup=false WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,order);
  for(var line:lines)inventory.prepareLocalUat(tenant,store,(UUID)line.get("id"),((Number)line.get("current_quantity")).intValue());
  return cycle;
 }
 @Transactional public void refresh(UUID tenant,UUID store,UUID order){
  if(!enabled)throw new IllegalStateException("Local UAT is disabled.");inventory.lock(tenant);if(cycle(tenant,store,order)==null)throw new IllegalStateException("Prepare a local UAT round.");
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT platform_waiting_for_pickup OR fulfillment_status='FULFILLED' FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",Boolean.class,tenant,store,order)))throw new IllegalStateException("Shipped orders cannot request another handoff.");
  for(var l:jdbc.queryForList("SELECT id,reserve_units FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? AND stock_scope='TEST' ORDER BY id FOR UPDATE",tenant,store,order))inventory.prepareLocalUat(tenant,store,(UUID)l.get("id"),((Number)l.get("reserve_units")).intValue());
 }
 @Transactional public void closeRound(UUID tenant,UUID store,UUID order,String actor){
  if(!enabled)throw new IllegalStateException("Local UAT resets are disabled.");inventory.lock(tenant);UUID cycle=cycle(tenant,store,order);if(cycle==null)return;
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND (state IN ('PURCHASING','UNKNOWN') OR error_message='LOCAL_HANDOFF_PENDING'))",Boolean.class,tenant,store,shipmentKey(order,cycle))))throw new IllegalStateException("Review the uncertain sandbox outcome before closing this cycle.");
  var doc=json.readTree(jdbc.queryForObject("SELECT definition::text FROM local_shipping_fixtures WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",String.class,tenant,store,cycle));var original=doc.path("original");var o=original.path("order");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT source_document_id=?::uuid AND source_updated_at=?::timestamptz FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",Boolean.class,o.path("source_document_id").asText(),o.path("source_updated_at").asText(),tenant,store,order)))throw new IllegalStateException("Source revision changed; review the local reset snapshot before restoring it.");
  for(var l:original.path("lines")){UUID line=UUID.fromString(l.path("id").asText());inventory.release(tenant,store,line);jdbc.update("UPDATE shopify_order_lines SET stock_scope=?,inventory_state=?,reserve_units=?,unfulfilled_quantity=?,local_handoff_units=?,local_pending_units=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",l.path("stock_scope").asText(),l.path("inventory_state").asText(),l.path("reserve_units").asInt(),l.path("unfulfilled_quantity").asInt(),l.path("local_handoff_units").asInt(),l.path("local_pending_units").asInt(),tenant,store,line);}
  jdbc.update("UPDATE shopify_orders SET fulfillment_status=?,platform_waiting_for_pickup=?,local_handoff_id=?::uuid,local_handoff_at=?::timestamptz,local_handoff_revision=?::timestamptz,local_handoff_actor=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",o.path("fulfillment_status").asText(),o.path("platform_waiting_for_pickup").asBoolean(),o.path("local_handoff_id").asText(null),o.path("local_handoff_at").asText(null),o.path("local_handoff_revision").asText(null),o.path("local_handoff_actor").asText(null),tenant,store,order);
  jdbc.update("UPDATE local_shipping_fixtures SET definition=definition||jsonb_build_object('active',false,'closedAt',clock_timestamp(),'closedBy',?::text) WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",actor,tenant,store,cycle);
  inventory.restoreLocalUatReservations(tenant,store,order);
 }
 @Transactional public void handedOff(UUID tenant,UUID store,UUID order,UUID event){UUID cycle=cycle(tenant,store,order);if(cycle==null)return;jdbc.update("UPDATE local_shipping_fixtures SET definition=definition||jsonb_build_object('handoffId',?::text,'handedOffAt',clock_timestamp()) WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",event.toString(),tenant,store,cycle);}
}
