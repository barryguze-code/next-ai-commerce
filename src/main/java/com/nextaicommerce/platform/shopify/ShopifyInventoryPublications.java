package com.nextaicommerce.platform.shopify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import static com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.localMappingAvailability;

/** Durable immutable publication intent. All methods scope tenant and take the stock-allocation lock. */
@Repository
public class ShopifyInventoryPublications {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;
 public ShopifyInventoryPublications(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
 public record Operation(UUID id,UUID tenant,UUID connection,UUID binding,long revision,UUID variant,String mapping,
   ShopifyInventoryClient.Target target,int desired,Integer expected,String state,int attempts){}
 private void scope(UUID tenant){
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",r->{},tenant);
 }
 private static final String ELIGIBLE="""
  FROM shopify_inventory_bindings binding
  JOIN marketplace_connections connection ON connection.tenant_id=binding.tenant_id AND connection.id=binding.marketplace_connection_id AND connection.channel='SHOPIFY' AND connection.status='ACTIVE'
  JOIN shopify_variants v ON v.tenant_id=binding.tenant_id AND v.marketplace_connection_id=binding.marketplace_connection_id
  JOIN shopify_products product ON product.tenant_id=v.tenant_id AND product.marketplace_connection_id=v.marketplace_connection_id AND product.id=v.product_id
  LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=v.tenant_id AND mapping.marketplace_connection_id=v.marketplace_connection_id AND mapping.shopify_variant_id=v.id AND mapping.status='ACTIVE' AND mapping.shopify_mapping_hash=v.mapping_hash
  """+localMappingAvailability(true)+"""
  WHERE binding.tenant_id=? AND binding.marketplace_connection_id=? AND binding.enabled
   AND EXISTS(SELECT 1 FROM shopify_inventory_binding_sources source WHERE source.tenant_id=binding.tenant_id AND source.marketplace_connection_id=binding.marketplace_connection_id AND source.binding_id=binding.id)
   AND (mapping.id IS NOT NULL OR EXISTS(SELECT 1 FROM shopify_inventory_publications managed WHERE managed.tenant_id=v.tenant_id AND managed.marketplace_connection_id=v.marketplace_connection_id AND managed.variant_id=v.id AND managed.inventory_item_gid=v.inventory_item_gid AND managed.shop_gid=binding.shop_gid AND managed.location_gid=binding.location_gid AND managed.state='CONFIRMED'))
  """;
 private static final String QUANTITY="CASE WHEN mapping.id IS NULL OR product.product_status<>'ACTIVE' THEN 0 ELSE least(2147483647,greatest(0,coalesce(local_inventory.available,0)))::integer END";
 private boolean fresh(UUID tenant,Instant startup){
  return fresh(tenant,startup,false);
 }
 private boolean fresh(UUID tenant,Instant startup,boolean requireWindowCoverage){
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   SELECT NOT EXISTS (
    SELECT 1 FROM marketplace_connections c WHERE c.tenant_id=? AND c.status='ACTIVE' AND c.channel IN ('AMAZON','SHOPIFY')
    AND (NOT EXISTS(SELECT 1 FROM marketplace_sync_runs r JOIN marketplace_sync_jobs j ON j.tenant_id=r.tenant_id AND j.sync_run_id=r.id AND j.marketplace_connection_id=r.marketplace_connection_id
      WHERE r.tenant_id=c.tenant_id AND r.marketplace_connection_id=c.id AND r.status='COMPLETED' AND j.status='COMPLETED'
        AND r.window_end>clock_timestamp()-interval '10 minutes' AND j.completed_at>=?
        AND (NOT ? OR r.window_end>=?)
        AND ((c.channel='SHOPIFY' AND r.sync_profile='SHOPIFY_ORDERS') OR (c.channel='AMAZON' AND j.job_type='FINAL_RECONCILIATION' AND r.sync_profile IN ('ORDER_CHANGES','STARTUP_ORDERS','RECENT_ORDER_RECONCILIATION','ORDER_LIFECYCLE'))))
     OR EXISTS(SELECT 1 FROM marketplace_sync_jobs j WHERE j.tenant_id=c.tenant_id AND j.marketplace_connection_id=c.id AND j.status IN ('QUEUED','WAITING','RUNNING') AND j.job_type IN ('ORDERS_API_DELTA','ORDERS_30_DAY','ORDER_ITEMS_30_DAY','FINAL_RECONCILIATION','SHOPIFY_ORDERS'))
     OR (c.channel='SHOPIFY' AND EXISTS(
      SELECT 1 FROM shopify_orders o WHERE o.tenant_id=c.tenant_id AND o.marketplace_connection_id=c.id
       AND NOT o.is_test AND o.cancelled_at IS NULL AND o.payment_status<>'VOIDED'
       AND (NOT o.evidence_complete OR EXISTS(
        SELECT 1 FROM shopify_order_lines line WHERE line.tenant_id=o.tenant_id AND line.marketplace_connection_id=o.marketplace_connection_id AND line.order_id=o.id
         AND line.requires_shipping AND line.unfulfilled_quantity>0 AND line.ownership<>'EXTERNAL'
         AND (line.ownership<>'MERCHANT' OR line.stock_scope<>'LIVE' OR line.inventory_state<>'READY_TO_SHIP'
          OR line.reserve_units<>line.unfulfilled_quantity))))))
   )
   """,Boolean.class,tenant,Timestamp.from(startup),requireWindowCoverage,Timestamp.from(startup)));
 }
 public int plan(UUID tenant,UUID connection,Instant startup){return tx.execute(s->{scope(tenant);if(!fresh(tenant,startup))return 0;
  return jdbc.update("""
   INSERT INTO shopify_inventory_publications(tenant_id,marketplace_connection_id,binding_id,binding_revision,variant_id,inventory_item_gid,mapping_hash,shop_gid,location_gid,desired_quantity)
   SELECT binding.tenant_id,binding.marketplace_connection_id,binding.id,binding.revision,v.id,v.inventory_item_gid,v.mapping_hash,binding.shop_gid,binding.location_gid,
   """+QUANTITY+" "+ELIGIBLE+"""
   AND NOT EXISTS(SELECT 1 FROM shopify_inventory_publications p WHERE p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.variant_id=v.id
     AND (p.state IN ('PREPARING','READY','VERIFYING','ATTENTION') OR (p.state='CONFIRMED' AND p.binding_revision=binding.revision AND p.mapping_hash=v.mapping_hash AND p.desired_quantity=
   """+QUANTITY+"""
    AND p.confirmed_at>clock_timestamp()-interval '15 minutes')))
   ORDER BY v.id LIMIT 25 ON CONFLICT DO NOTHING
   """,tenant,connection);
 });}
 public Optional<Operation> next(UUID tenant,UUID connection){return tx.execute(s->{scope(tenant);
  return jdbc.query("""
   SELECT p.* FROM shopify_inventory_publications p JOIN shopify_inventory_bindings b ON b.tenant_id=p.tenant_id AND b.marketplace_connection_id=p.marketplace_connection_id AND b.id=p.binding_id
   WHERE p.tenant_id=? AND p.marketplace_connection_id=? AND p.state IN ('PREPARING','READY','VERIFYING') AND p.available_at<=clock_timestamp()
   ORDER BY p.created_at,p.id LIMIT 1
   """,(r,n)->new Operation(r.getObject("id",UUID.class),tenant,connection,r.getObject("binding_id",UUID.class),r.getLong("binding_revision"),r.getObject("variant_id",UUID.class),r.getString("mapping_hash"),
     new ShopifyInventoryClient.Target(tenant,connection,r.getString("shop_gid"),r.getString("inventory_item_gid"),r.getString("location_gid")),r.getInt("desired_quantity"),(Integer)r.getObject("expected_quantity"),r.getString("state"),r.getInt("attempts")),tenant,connection).stream().findFirst();
 });}
 private boolean lock(Operation p){
  return !jdbc.queryForList("SELECT id FROM shopify_inventory_publications WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state=? AND attempts=? FOR UPDATE SKIP LOCKED",UUID.class,p.tenant(),p.connection(),p.id(),p.state(),p.attempts()).isEmpty();
 }
 private boolean current(Operation p,Instant startup){
  if(!fresh(p.tenant(),startup))return false;
  return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 "+ELIGIBLE+" AND binding.id=? AND binding.revision=? AND binding.shop_gid=? AND binding.location_gid=? AND v.id=? AND v.mapping_hash=? AND v.inventory_item_gid=? AND "+QUANTITY+"=?)",Boolean.class,p.tenant(),p.connection(),p.binding(),p.revision(),p.target().shop(),p.target().location(),p.variant(),p.mapping(),p.target().item(),p.desired()));
 }
 /** Prepare commits the immutable compare value before any mutation is attempted. */
 public void prepare(Operation p,ShopifyInventoryClient client,Instant startup){tx.executeWithoutResult(s->{scope(p.tenant());if(!lock(p))return;
  if(!current(p,startup)){finish(p,"CANCELLED",null,"Stock, mapping, binding or order freshness changed");return;}
  if(!requestSlot())return;
  try{int expected=client.available(p.target(),()->{});
   if(unreconciledDecrease(p,expected)){
    finish(p,"ATTENTION",expected,"Shopify stock fell below the last confirmed publication; refresh orders and reconcile before increasing it");return;
   }
   jdbc.update("UPDATE shopify_inventory_publications SET expected_quantity=?,state='READY',prepared_at=clock_timestamp(),available_at=clock_timestamp()+interval '1 second' WHERE tenant_id=? AND id=?",expected,p.tenant(),p.id());
  }catch(com.nextaicommerce.platform.web.ShopifyConnectionService.RateLimited e){pause(p,e.retryAt());}
  catch(RuntimeException e){finish(p,"ATTENTION",null,"Inventory read or permission preflight failed; no write attempted");}
 });}
 /** CAS only protects changes after our read. A pre-existing remote decrease needs later order reconciliation. */
 private boolean unreconciledDecrease(Operation p,int observed){
  if(p.desired()<=observed)return false;
  return Boolean.TRUE.equals(jdbc.queryForObject("""
   SELECT EXISTS (
    SELECT 1 FROM (
     SELECT desired_quantity,confirmed_at FROM shopify_inventory_publications
     WHERE tenant_id=? AND marketplace_connection_id=? AND variant_id=? AND inventory_item_gid=?
       AND shop_gid=? AND location_gid=? AND state='CONFIRMED'
     ORDER BY confirmed_at DESC,id DESC LIMIT 1
    ) confirmed WHERE confirmed.desired_quantity>?
    AND NOT EXISTS (
     SELECT 1 FROM shopify_inventory_publications reviewed
     WHERE reviewed.tenant_id=? AND reviewed.marketplace_connection_id=? AND reviewed.variant_id=?
       AND reviewed.inventory_item_gid=? AND reviewed.shop_gid=? AND reviewed.location_gid=?
       AND reviewed.state='CANCELLED' AND reviewed.reconciled_at>confirmed.confirmed_at
       AND reviewed.observed_quantity=?
    )
   )
   """,Boolean.class,p.tenant(),p.connection(),p.variant(),p.target().item(),p.target().shop(),p.target().location(),observed,
   p.tenant(),p.connection(),p.variant(),p.target().item(),p.target().shop(),p.target().location(),observed));
 }
 /** Commit VERIFYING before the request: a crash can only resume with readback, never a blind write. */
 public boolean markSubmitting(Operation p,Instant startup){return Boolean.TRUE.equals(tx.execute(s->{scope(p.tenant());if(!lock(p))return false;
  if(!current(p,startup)){finish(p,"CANCELLED",null,"Stock, mapping, binding or order freshness changed");return false;}
  if(p.attempts()>=8){finish(p,"ATTENTION",null,"Publication attempt budget exhausted");return false;}
  if(!requestSlot())return false;
  int changed=jdbc.update("UPDATE shopify_inventory_publications SET state='VERIFYING',attempts=attempts+1,submitted_at=clock_timestamp(),available_at=clock_timestamp()+interval '60 seconds' WHERE tenant_id=? AND id=? AND prepared_at>clock_timestamp()-interval '60 seconds'",p.tenant(),p.id());
  if(changed==0)finish(p,"CANCELLED",null,"Prepared observation expired; a new observation is required");
  return changed==1;
 }));}
 public void submit(Operation p,ShopifyInventoryClient client,Instant startup){tx.executeWithoutResult(s->{scope(p.tenant());
  var verifying=new Operation(p.id(),p.tenant(),p.connection(),p.binding(),p.revision(),p.variant(),p.mapping(),p.target(),p.desired(),p.expected(),"VERIFYING",p.attempts()+1);
  if(!lock(verifying))return;
  if(!current(p,startup)){finish(p,"ATTENTION",null,"Stock changed before submission; reconcile before a new operation");return;}
  try{client.setAvailable(p.target(),p.expected(),p.desired(),p.id(),()->{});}
  catch(com.nextaicommerce.platform.web.ShopifyConnectionService.RateLimited e){pause(verifying,e.retryAt());}
  catch(ShopifyInventoryClient.StaleQuantity e){finish(p,"ATTENTION",null,"Shopify quantity changed; reconcile orders before publishing again");}
  catch(RuntimeException e){/* Unknown outcome remains VERIFYING; do not repeat the mutation. */}
 });}
 public void verify(Operation p,ShopifyInventoryClient client){tx.executeWithoutResult(s->{scope(p.tenant());if(!lock(p))return;
  if(!requestSlot())return;
  try{int observed=client.available(p.target(),()->{});finish(p,observed==p.desired()?"CONFIRMED":"ATTENTION",observed,observed==p.desired()?"Quantity readback confirmed":"Quantity differs; reconcile before a new operation");}
  catch(com.nextaicommerce.platform.web.ShopifyConnectionService.RateLimited e){pause(p,e.retryAt());}
  catch(RuntimeException e){
   if(p.attempts()>=8)finish(p,"ATTENTION",null,"Readback unavailable after bounded attempts");
   else jdbc.update("UPDATE shopify_inventory_publications SET attempts=attempts+1,available_at=clock_timestamp()+interval '15 minutes',detail='Readback unavailable; no repeated write' WHERE tenant_id=? AND id=?",p.tenant(),p.id());
  }
 });}
 /** Explicit operator recovery preserves the failed operation and requires order evidence newer than its failure. */
 public boolean requestReconciliation(UUID tenant,UUID id,String actor){return Boolean.TRUE.equals(tx.execute(s->{scope(tenant);
  var rows=jdbc.queryForList("SELECT updated_at FROM shopify_inventory_publications WHERE tenant_id=? AND id=? AND state='ATTENTION' FOR UPDATE",tenant,id);
  if(rows.isEmpty()||!fresh(tenant,((Timestamp)rows.getFirst().get("updated_at")).toInstant(),true))return false;
  jdbc.update("UPDATE shopify_inventory_publications SET state='CANCELLED',updated_at=clock_timestamp(),reconciled_at=clock_timestamp(),detail=? WHERE tenant_id=? AND id=?", "Reconciliation requested by "+actor+" after fresh account-wide order imports; prior operation preserved",tenant,id);
  return true;
 }));}
 private boolean requestSlot(){return jdbc.update("UPDATE inventory_publication_rate_slot SET ready_at=clock_timestamp()+interval '1 second' WHERE id=1 AND ready_at<=clock_timestamp()")==1;}
 private void pause(Operation p,Instant retryAt){
  if(p.attempts()>=8){finish(p,"ATTENTION",null,"Inventory rate-limit retry budget exhausted");return;}
  jdbc.update("UPDATE shopify_inventory_publications SET attempts=attempts+1,available_at=?,detail='Shopify requested a pause; no repeated write' WHERE tenant_id=? AND id=?",Timestamp.from(retryAt),p.tenant(),p.id());
 }
 private void finish(Operation p,String state,Integer observed,String detail){jdbc.update("UPDATE shopify_inventory_publications SET state=?,observed_quantity=?,detail=?,updated_at=clock_timestamp(),confirmed_at=CASE WHEN ?='CONFIRMED' THEN clock_timestamp() ELSE NULL END WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",state,observed,detail,state,p.tenant(),p.connection(),p.id());}
}
