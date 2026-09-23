package com.nextaicommerce.platform.sync;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ShelfSaleRepository {
 private final JdbcTemplate jdbc;
 public ShelfSaleRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 public record Listing(UUID tenant,UUID connection,String marketplace,String sku,String seller,String owned,String pending,String status,int attempts){}
 public record Plan(BigDecimal percent,Instant end){}
 @Transactional(readOnly=true) public boolean allowed(UUID tenant,Set<UUID> connections){scope(tenant);return jdbc.query("SELECT id FROM marketplace_connections WHERE tenant_id=? AND channel='AMAZON' AND status='ACTIVE'",(r,n)->r.getObject(1,UUID.class),tenant).stream().anyMatch(connections::contains);}
 @Transactional(readOnly=true) public boolean enabled(UUID tenant){scope(tenant);return Boolean.TRUE.equals(jdbc.query("SELECT enabled FROM shelf_sale_settings WHERE tenant_id=?",r->r.next()?r.getBoolean(1):false,tenant));}
 @Transactional public void configure(UUID tenant,boolean enabled,String actor){
  scope(tenant);jdbc.update("INSERT INTO shelf_sale_settings(tenant_id,enabled,updated_by) VALUES (?,?,?) ON CONFLICT(tenant_id) DO UPDATE SET enabled=excluded.enabled,updated_by=excluded.updated_by,updated_at=now()",tenant,enabled,actor);
  jdbc.update("UPDATE shelf_sale_publications SET next_attempt_at=now() WHERE tenant_id=?",tenant);
 }
 public List<UUID> tenants(){return jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE'",(r,n)->r.getObject(1,UUID.class));}
 public void discover(UUID tenant,UUID connection){
  scope(tenant);
  jdbc.update("""
   INSERT INTO shelf_sale_publications(tenant_id,connection_id,marketplace_id,seller_sku)
   SELECT l.tenant_id,l.marketplace_connection_id,l.marketplace_id,l.seller_sku FROM amazon_listings l
   WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.marketplace_id='ATVPDKIKX0DER'
    AND upper(coalesce(l.fulfillment_channel,'')) IN ('MFN','FBM','DEFAULT','MERCHANT')
    AND EXISTS(SELECT 1 FROM marketplace_sku_mappings m WHERE m.tenant_id=l.tenant_id AND m.marketplace_connection_id=l.marketplace_connection_id AND m.marketplace_sku=l.seller_sku AND m.status='ACTIVE')
   ON CONFLICT DO NOTHING
   """,tenant,connection);
 }
 public Optional<Listing> next(UUID tenant,UUID connection){
  scope(tenant);
  return jdbc.query("""
   SELECT p.*,c.seller_identifier FROM shelf_sale_publications p JOIN marketplace_connections c ON c.tenant_id=p.tenant_id AND c.id=p.connection_id
   WHERE p.tenant_id=? AND p.connection_id=? AND c.status='ACTIVE' AND p.next_attempt_at<=now()
   ORDER BY p.next_attempt_at,p.seller_sku FOR UPDATE OF p SKIP LOCKED LIMIT 1
   """,(r,n)->new Listing(tenant,connection,r.getString("marketplace_id"),r.getString("seller_sku"),r.getString("seller_identifier"),r.getString("owned_discount"),r.getString("pending_discount"),r.getString("status"),r.getInt("attempts")),tenant,connection).stream().findFirst();
 }
 /** Re-evaluated before every request: old queued work never recreates a discount for exhausted stock. */
 public Optional<Plan> plan(Listing l){
  scope(l.tenant());
  return jdbc.query("""
   WITH mapped AS (
    SELECT m.* FROM marketplace_sku_mappings m JOIN amazon_listings l ON l.tenant_id=m.tenant_id AND l.marketplace_connection_id=m.marketplace_connection_id AND l.seller_sku=m.marketplace_sku
    WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.marketplace_sku=? AND m.status='ACTIVE'
     AND l.marketplace_id=? AND upper(coalesce(l.fulfillment_channel,'')) IN ('MFN','FBM','DEFAULT','MERCHANT')
   ), components AS (
    SELECT c.account_catalog_item_id item,c.quantity FROM mapped m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
    UNION ALL SELECT m.account_catalog_item_id,greatest(m.quantity_per_marketplace_unit,1) FROM mapped m
     WHERE m.account_catalog_item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id)
   ), batches AS (
    SELECT e.account_catalog_item_id item,e.expiration_date expiry,sum(e.quantity) quantity FROM inventory_ledger_entries e
    WHERE e.tenant_id=? AND e.account_catalog_item_id IN (SELECT item FROM components)
    GROUP BY e.account_catalog_item_id,e.expiration_date HAVING sum(e.quantity)>0
   ), eligible AS (
    SELECT b.*,c.quantity pack,p.minimum_sellable_days,
     CASE WHEN a.id IS NOT NULL THEN coalesce(t.discount_percent,a.discount_percent) ELSE coalesce(o.discount_percent,p.default_sale_discount_percent,10) END percent,
     CASE WHEN a.id IS NOT NULL THEN a.starts_at ELSE (b.expiry-coalesce(o.sale_start_days_before_expiration,p.sale_start_days_before_expiration,20))::timestamptz END starts,
     least((b.expiry-coalesce(p.minimum_sellable_days,10))::timestamptz,
       CASE WHEN a.id IS NOT NULL THEN a.ends_at ELSE (b.expiry-coalesce(o.sale_start_days_before_expiration,p.sale_start_days_before_expiration,20)+coalesce(o.sale_duration_days,p.sale_duration_days,7))::timestamptz END) ends
    FROM batches b JOIN components c ON c.item=b.item
    LEFT JOIN inventory_shelf_life_policies p ON p.tenant_id=?
    LEFT JOIN inventory_shelf_life_product_overrides o ON o.tenant_id=? AND o.account_catalog_item_id=b.item
    LEFT JOIN LATERAL (SELECT a.* FROM inventory_expiration_actions a WHERE a.tenant_id=? AND a.account_catalog_item_id=b.item AND a.expiration_date=b.expiry ORDER BY a.created_at DESC LIMIT 1) a ON true
    LEFT JOIN inventory_expiration_action_targets t ON t.tenant_id=? AND t.action_id=a.id AND t.seller_sku=?
    WHERE b.expiry>current_date+coalesce(p.minimum_sellable_days,10)
     AND ((a.id IS NULL AND coalesce(p.auto_sale_enabled,true)) OR (a.status='PLANNED' AND a.action_type='DISCOUNT' AND t.seller_sku IS NOT NULL))
     AND b.quantity-coalesce((SELECT sum(r.quantity) FROM order_inventory_reservations r WHERE r.tenant_id=? AND r.account_catalog_item_id=b.item AND r.expiration_date=b.expiry AND r.status='ACTIVE'),0)>=c.quantity
   ) SELECT percent,ends FROM eligible
   WHERE starts<=now() AND ends>now() AND percent>0 AND percent<100
    AND EXISTS(SELECT 1 FROM shelf_sale_settings WHERE tenant_id=? AND enabled)
    AND EXISTS(SELECT 1 FROM inventory_publications WHERE tenant_id=? AND connection_id=? AND marketplace_id=? AND seller_sku=? AND desired_quantity>0 AND status='CONFIRMED')
   ORDER BY percent DESC,ends LIMIT 1
   """,(r,n)->new Plan(r.getBigDecimal(1),r.getTimestamp(2).toInstant()),l.tenant(),l.connection(),l.sku(),l.marketplace(),l.tenant(),l.tenant(),l.tenant(),l.tenant(),l.tenant(),l.sku(),l.tenant(),l.tenant(),l.tenant(),l.connection(),l.marketplace(),l.sku()).stream().findFirst();
 }
 public void result(Listing l,String status,String owned,String pending,int delay,String error){
  scope(l.tenant());jdbc.update("UPDATE shelf_sale_publications SET status=?,owned_discount=?::jsonb,pending_discount=?::jsonb,next_attempt_at=now()+make_interval(secs=>?),last_error=?,attempts=CASE WHEN ? IS NULL THEN 0 ELSE attempts+1 END,updated_at=now() WHERE tenant_id=? AND connection_id=? AND marketplace_id=? AND seller_sku=?",status,owned,pending,delay,error,error,l.tenant(),l.connection(),l.marketplace(),l.sku());
  if(!status.equals(l.status())||error!=null)jdbc.update("INSERT INTO shelf_sale_events(tenant_id,connection_id,seller_sku,event,detail) VALUES (?,?,?,?,?)",l.tenant(),l.connection(),l.sku(),status,error);
 }
 @Transactional(readOnly=true) public List<Map<String,Object>> summary(UUID tenant){scope(tenant);return jdbc.queryForList("SELECT status,count(*) count FROM shelf_sale_publications WHERE tenant_id=? GROUP BY status ORDER BY status",tenant);}
}
