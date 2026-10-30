package com.nextaicommerce.platform.sync;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.LOCAL_MAPPING_AVAILABILITY;

/** Call inside a tenant transaction. Stock changes and invalidations share their original transaction. */
@Repository
public class InventoryPublicationRepository {
 private final JdbcTemplate jdbc;private final boolean sharedSimulation;
 public InventoryPublicationRepository(JdbcTemplate jdbc){this(jdbc,false);}
 @org.springframework.beans.factory.annotation.Autowired
 public InventoryPublicationRepository(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Value("${app.inventory.publication-simulation-enabled:false}") boolean sharedSimulation){this.jdbc=jdbc;this.sharedSimulation=sharedSimulation;}
 public void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 public List<UUID> tenants(){return jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE'",(r,n)->r.getObject(1,UUID.class));}
 public int plan(UUID tenant){
  scope(tenant);
  // Same lock as order allocation: never observe a half-rebuilt reservation set.
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",r->{},tenant);
  int due=jdbc.update("INSERT INTO inventory_publication_reconciliation VALUES (?,now()) ON CONFLICT(tenant_id) DO UPDATE SET checked_at=now() WHERE inventory_publication_reconciliation.checked_at<now()-interval '15 minutes'",tenant);
  if(due>0)jdbc.update("INSERT INTO inventory_publication_dirty VALUES (?,'00000000-0000-0000-0000-000000000000',now()) ON CONFLICT(tenant_id,item_id) DO UPDATE SET changed_at=now()",tenant);
  var dirty=jdbc.query("DELETE FROM inventory_publication_dirty WHERE tenant_id=? RETURNING item_id",(r,n)->r.getObject(1,UUID.class).toString(),tenant);
  if(dirty.isEmpty())return 0;
  String dirtyIds="{"+String.join(",",dirty)+"}";
  if(sharedSimulation)planShopify(tenant,dirtyIds);
  // Item-only events expand through the reverse mapping; full sweeps include never-mapped FBM listings.
  int count=jdbc.update("""
   WITH dirty_items AS (SELECT unnest(?::uuid[]) item_id)
   INSERT INTO inventory_publications(tenant_id,connection_id,marketplace_id,seller_sku,desired_quantity)
   SELECT listing.tenant_id,listing.marketplace_connection_id,listing.marketplace_id,listing.seller_sku,
     least(2147483647,greatest(0,coalesce(local_inventory.available,0)))::integer
   FROM amazon_listings listing
   JOIN marketplace_connections connection ON connection.tenant_id=listing.tenant_id AND connection.id=listing.marketplace_connection_id
   LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=listing.tenant_id
     AND mapping.marketplace_connection_id=listing.marketplace_connection_id AND mapping.marketplace_sku=listing.seller_sku AND mapping.status='ACTIVE'
   """+LOCAL_MAPPING_AVAILABILITY+"""
   WHERE listing.tenant_id=? AND connection.channel='AMAZON' AND connection.status='ACTIVE'
     AND upper(coalesce(listing.fulfillment_channel,'')) IN ('MFN','FBM','DEFAULT','MERCHANT')
     AND EXISTS(SELECT 1 FROM dirty_items dirty WHERE
       (dirty.item_id='00000000-0000-0000-0000-000000000000' OR dirty.item_id=mapping.account_catalog_item_id OR EXISTS(
         SELECT 1 FROM marketplace_sku_mapping_components component WHERE component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id AND component.account_catalog_item_id=dirty.item_id)))
   ON CONFLICT(tenant_id,connection_id,marketplace_id,seller_sku) DO UPDATE
     SET desired_quantity=excluded.desired_quantity,revision=inventory_publications.revision+1,status='PENDING',attempts=0,
       next_attempt_at=now(),last_error=NULL,updated_at=now()
     WHERE inventory_publications.desired_quantity<>excluded.desired_quantity OR inventory_publications.status='DISABLED'
   """,dirtyIds,tenant);
  jdbc.update("""
   UPDATE inventory_publications p SET status='DISABLED',last_error='Listing missing, connection inactive, or not explicitly seller fulfilled.'
   WHERE p.tenant_id=? AND NOT EXISTS(SELECT 1 FROM amazon_listings l JOIN marketplace_connections c ON c.tenant_id=l.tenant_id AND c.id=l.marketplace_connection_id
     WHERE l.tenant_id=p.tenant_id AND l.marketplace_connection_id=p.connection_id AND l.marketplace_id=p.marketplace_id AND l.seller_sku=p.seller_sku
       AND c.status='ACTIVE' AND c.channel='AMAZON' AND upper(coalesce(l.fulfillment_channel,'')) IN ('MFN','FBM','DEFAULT','MERCHANT'))
   """,tenant);
  return count;
 }
 private void planShopify(UUID tenant,String dirtyIds){
  jdbc.update("""
   INSERT INTO shopify_inventory_simulations(tenant_id,marketplace_connection_id,variant_id,desired_quantity,state,detail)
   SELECT v.tenant_id,v.marketplace_connection_id,v.id,
     CASE WHEN mapping.id IS NULL OR mapping.shopify_mapping_hash IS DISTINCT FROM v.mapping_hash OR p.product_status<>'ACTIVE' THEN 0
       ELSE least(2147483647,greatest(0,coalesce(local_inventory.available,0)))::integer END,
     CASE WHEN mapping.id IS NULL OR mapping.shopify_mapping_hash IS DISTINCT FROM v.mapping_hash THEN 'ATTENTION'
       WHEN p.product_status<>'ACTIVE' THEN 'DISABLED' ELSE 'DRY_RUN' END,
     CASE WHEN mapping.id IS NULL THEN 'Mapping is unresolved. No external inventory request.'
       WHEN mapping.shopify_mapping_hash IS DISTINCT FROM v.mapping_hash THEN 'Mapping evidence changed; review required. No external inventory request.'
       WHEN p.product_status<>'ACTIVE' THEN 'Product is not active. No external inventory request.'
       ELSE 'Simulation only. Merchant inventory location is not selected; no Shopify inventory request.' END
   FROM shopify_variants v JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
   JOIN marketplace_connections connection ON connection.tenant_id=v.tenant_id AND connection.id=v.marketplace_connection_id AND connection.channel='SHOPIFY' AND connection.status='ACTIVE'
   LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=v.tenant_id AND mapping.marketplace_connection_id=v.marketplace_connection_id AND mapping.shopify_variant_id=v.id AND mapping.status='ACTIVE'
   """+LOCAL_MAPPING_AVAILABILITY+"""
   WHERE v.tenant_id=? AND ('00000000-0000-0000-0000-000000000000'=ANY(?::uuid[]) OR EXISTS(
     SELECT 1 FROM marketplace_sku_mapping_components part WHERE part.tenant_id=mapping.tenant_id AND part.marketplace_sku_mapping_id=mapping.id AND part.account_catalog_item_id=ANY(?::uuid[])))
   ON CONFLICT(tenant_id,marketplace_connection_id,variant_id) DO UPDATE SET desired_quantity=excluded.desired_quantity,state=excluded.state,
     detail=excluded.detail,revision=shopify_inventory_simulations.revision+1,updated_at=now()
   WHERE (shopify_inventory_simulations.desired_quantity,shopify_inventory_simulations.state,shopify_inventory_simulations.detail)
     IS DISTINCT FROM (excluded.desired_quantity,excluded.state,excluded.detail)
   """,tenant,dirtyIds,dirtyIds);
  jdbc.update("UPDATE shopify_inventory_simulations s SET state='DISABLED',detail='Connection is inactive. No external inventory request.' WHERE s.tenant_id=? AND NOT EXISTS(SELECT 1 FROM marketplace_connections c WHERE c.tenant_id=s.tenant_id AND c.id=s.marketplace_connection_id AND c.channel='SHOPIFY' AND c.status='ACTIVE')",tenant);
 }
 public record Pending(UUID tenant,UUID connection,String marketplace,String sku,int quantity,long revision,String status,int attempts,String seller){}
 public Optional<Pending> next(UUID tenant){
  return next(tenant,null);
 }
 public Optional<Pending> next(UUID tenant,java.time.Instant startup){
  return next(tenant,startup,null);
 }
 public Optional<Pending> next(UUID tenant,java.time.Instant startup,Set<UUID> allowedConnections){
  return next(tenant,startup,allowedConnections,"");
 }
 public Optional<Pending> next(UUID tenant,java.time.Instant startup,Set<UUID> allowedConnections,String allowedSkus){
  return next(tenant,startup,allowedConnections,allowedSkus,null);
 }
 private Optional<Pending> next(UUID tenant,java.time.Instant startup,Set<UUID> allowedConnections,String allowedSkus,String exactSku){
  scope(tenant);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",r->{},tenant);
  return jdbc.query("""
   SELECT p.*,c.seller_identifier FROM inventory_publications p JOIN marketplace_connections c ON c.tenant_id=p.tenant_id AND c.id=p.connection_id
   WHERE p.tenant_id=? AND c.channel='AMAZON' AND c.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM inventory_publication_dirty d WHERE d.tenant_id=p.tenant_id) AND p.status NOT IN ('DISABLED','DRY_RUN') AND p.next_attempt_at<=now()
     AND (?::uuid[] IS NULL OR p.connection_id=ANY(?::uuid[]))
     AND (?='' OR p.seller_sku=ANY(string_to_array(?,',')))
     AND (?::text IS NULL OR p.seller_sku=?)
     AND (?::timestamptz IS NULL OR (
       EXISTS(SELECT 1 FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
         WHERE j.tenant_id=p.tenant_id AND j.marketplace_connection_id=p.connection_id
           AND j.job_type='FINAL_RECONCILIATION' AND j.status='COMPLETED' AND r.status='COMPLETED'
           AND r.sync_profile IN ('ORDER_CHANGES','STARTUP_ORDERS','RECENT_ORDER_RECONCILIATION','ORDER_LIFECYCLE')
           AND j.completed_at>=? AND r.window_end>now()-interval '15 minutes')
       AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs j WHERE j.tenant_id=p.tenant_id
         AND j.marketplace_connection_id=p.connection_id AND j.job_type IN ('ORDERS_API_DELTA','ORDERS_30_DAY','ORDER_ITEMS_30_DAY','FINAL_RECONCILIATION')
         AND j.status IN ('QUEUED','RUNNING','WAITING'))
     ))
   AND (p.desired_quantity=0 OR (
     NOT EXISTS(SELECT 1 FROM marketplace_connections shop
       JOIN shopify_order_controls control ON control.tenant_id=shop.tenant_id AND control.marketplace_connection_id=shop.id AND control.inventory_enabled
       WHERE shop.tenant_id=p.tenant_id AND shop.channel='SHOPIFY' AND shop.status='ACTIVE'
       AND (NOT EXISTS(SELECT 1 FROM marketplace_sync_runs sr JOIN marketplace_sync_jobs sj ON sj.tenant_id=sr.tenant_id AND sj.marketplace_connection_id=sr.marketplace_connection_id AND sj.sync_run_id=sr.id
         WHERE sr.tenant_id=shop.tenant_id AND sr.marketplace_connection_id=shop.id AND sr.sync_profile='SHOPIFY_ORDERS' AND sr.status='COMPLETED' AND sj.status='COMPLETED'
          AND sr.window_end>clock_timestamp()-interval '10 minutes' AND (?::timestamptz IS NULL OR sj.completed_at>=?))
        OR EXISTS(SELECT 1 FROM marketplace_sync_jobs sj WHERE sj.tenant_id=shop.tenant_id AND sj.marketplace_connection_id=shop.id AND sj.job_type='SHOPIFY_ORDERS' AND sj.status IN ('QUEUED','WAITING','RUNNING'))))
     AND """+com.nextaicommerce.platform.shopify.ShopifyPublicationSafety.allowsPositive("p.tenant_id","(SELECT m.id FROM marketplace_sku_mappings m WHERE m.tenant_id=p.tenant_id AND m.marketplace_connection_id=p.connection_id AND m.marketplace_sku=p.seller_sku AND m.status='ACTIVE')")+"""
   ))
   ORDER BY CASE WHEN p.status='CONFIRMED' THEN 2
                 WHEN p.next_attempt_at<now()-interval '2 minutes' THEN 0 ELSE 1 END,
     CASE WHEN p.next_attempt_at>=now()-interval '2 minutes' THEN p.desired_quantity=0 ELSE false END DESC,
     p.next_attempt_at,p.seller_sku FOR UPDATE OF p SKIP LOCKED LIMIT 1
   """,(r,n)->new Pending(tenant,r.getObject("connection_id",UUID.class),r.getString("marketplace_id"),r.getString("seller_sku"),r.getInt("desired_quantity"),r.getLong("revision"),r.getString("status"),r.getInt("attempts"),r.getString("seller_identifier")),tenant,connectionArray(allowedConnections),connectionArray(allowedConnections),allowedSkus,allowedSkus,exactSku,exactSku,startup==null?null:java.sql.Timestamp.from(startup),startup==null?null:java.sql.Timestamp.from(startup),startup==null?null:java.sql.Timestamp.from(startup),startup==null?null:java.sql.Timestamp.from(startup)).stream().findFirst();
 }
 private static String connectionArray(Set<UUID> ids){return ids==null?null:"{"+String.join(",",ids.stream().map(UUID::toString).toList())+"}";}
 /** Recheck the exact revision and shared warehouse holds immediately before sending. */
 public boolean current(Pending pending,java.time.Instant startup){
  return next(pending.tenant(),startup,Set.of(pending.connection()),"",pending.sku())
   .filter(p->p.marketplace().equals(pending.marketplace())&&p.revision()==pending.revision()
     &&p.quantity()==pending.quantity()&&p.status().equals(pending.status())).isPresent();
 }
 public void result(Pending p,String state,int attempts,int delay,String error,Integer observed){
  jdbc.update("UPDATE inventory_publications SET status=?,attempts=?,next_attempt_at=now()+make_interval(secs=>?),last_error=?,observed_quantity=?,updated_at=now() WHERE tenant_id=? AND connection_id=? AND marketplace_id=? AND seller_sku=? AND revision=?",
   state,attempts,delay,error,observed,p.tenant,p.connection,p.marketplace,p.sku,p.revision);
 }
 public boolean acquireRequestSlot(){return jdbc.update("UPDATE inventory_publication_rate_slot SET ready_at=now()+interval '1 second' WHERE id=1 AND ready_at<=now()")==1;}
 public void simulate(UUID tenant){scope(tenant);jdbc.update("UPDATE inventory_publications SET status='DRY_RUN',last_error='Local simulation only — no Amazon request sent.',updated_at=now() WHERE tenant_id=? AND status NOT IN ('DISABLED','DRY_RUN')",tenant);}
}
