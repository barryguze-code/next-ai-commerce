package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.nextaicommerce.platform.orders.OrderRepository;

/** Caller owns a transaction. Never infer cancellation from age or absence. */
@Repository
public class UnresolvedOrderRepository {
 private final JdbcTemplate jdbc; private final OrderRepository orders;
 public UnresolvedOrderRepository(JdbcTemplate jdbc,OrderRepository orders){this.jdbc=jdbc;this.orders=orders;}
 public record Check(UUID tenant,UUID connection,String orderId,int failures){}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 public List<UUID> tenants(){return jdbc.queryForList("SELECT id FROM tenants WHERE status='ACTIVE'",UUID.class);}
 public Optional<Check> claim(UUID tenant){
  scope(tenant);
  jdbc.update("""
   INSERT INTO amazon_order_status_checks(tenant_id,connection_id,amazon_order_id)
   SELECT o.tenant_id,o.marketplace_connection_id,o.amazon_order_id FROM amazon_orders o
   JOIN marketplace_connections c ON c.tenant_id=o.tenant_id AND c.id=o.marketplace_connection_id
   WHERE o.tenant_id=? AND c.status='ACTIVE' AND c.channel='AMAZON' AND o.operational_scope='LIVE'
    AND upper(coalesce(o.fulfillment_channel,'')) NOT IN ('AFN','AMAZON')
    AND (regexp_replace(upper(o.order_status),'[^A-Z]','','g') IN ('PENDING','PENDINGAVAILABILITY','UNSHIPPED','PARTIALLYSHIPPED')
      OR EXISTS(SELECT 1 FROM order_inventory_reservations r WHERE r.tenant_id=o.tenant_id
       AND r.marketplace_connection_id=o.marketplace_connection_id AND r.amazon_order_id=o.amazon_order_id AND r.status='ACTIVE'))
   ON CONFLICT DO NOTHING
   """,tenant);
  var result=jdbc.query("""
   SELECT s.connection_id,s.amazon_order_id,s.failures FROM amazon_order_status_checks s
   JOIN amazon_orders o ON o.tenant_id=s.tenant_id AND o.marketplace_connection_id=s.connection_id AND o.amazon_order_id=s.amazon_order_id
   JOIN marketplace_connections c ON c.tenant_id=s.tenant_id AND c.id=s.connection_id
   WHERE s.tenant_id=? AND c.status='ACTIVE' AND s.next_check_at<=now() AND o.operational_scope='LIVE'
    AND regexp_replace(upper(o.order_status),'[^A-Z]','','g') IN ('PENDING','PENDINGAVAILABILITY','UNSHIPPED','PARTIALLYSHIPPED')
   ORDER BY s.next_check_at,o.purchase_date FOR UPDATE OF s SKIP LOCKED LIMIT 1
   """,(r,n)->new Check(tenant,r.getObject(1,UUID.class),r.getString(2),r.getInt(3)),tenant);
  if(result.isEmpty())return Optional.empty();
  var c=result.getFirst();
  // Durable short lease recovers after a worker crash. HTTP timeout is 45 seconds.
  jdbc.update("UPDATE amazon_order_status_checks SET next_check_at=now()+interval '5 minutes' WHERE tenant_id=? AND connection_id=? AND amazon_order_id=?",tenant,c.connection,c.orderId);
  return Optional.of(c);
 }
 public void confirmed(Check c,String status,Instant updated){
  scope(c.tenant);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",r->{},c.tenant);
  int changed=jdbc.update("""
   UPDATE amazon_orders SET order_status=?,last_update_date=?,updated_at=now()
   WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
    AND (last_update_date IS NULL OR last_update_date<? OR (last_update_date=? AND
       (regexp_replace(upper(order_status),'[^A-Z]','','g') NOT IN ('CANCELED','CANCELLED')
        OR regexp_replace(upper(?),'[^A-Z]','','g') IN ('CANCELED','CANCELLED'))))
   """,status,Timestamp.from(updated),c.tenant,c.connection,c.orderId,Timestamp.from(updated),Timestamp.from(updated),status);
  if(changed>0)orders.reconcile(c.tenant,c.connection);
  boolean overdue=Boolean.TRUE.equals(jdbc.queryForObject("SELECT purchase_date<now()-interval '30 days' AND regexp_replace(upper(order_status),'[^A-Z]','','g') IN ('PENDING','PENDINGAVAILABILITY') FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",Boolean.class,c.tenant,c.connection,c.orderId));
  result(c,overdue?"OVERDUE":"CONFIRMED",0,3600,overdue?"Amazon still reports pending after 30 days. Reservation retained; review payment status.":changed>0?"Order status verified directly with Amazon.":"Older Amazon response ignored; newer stored status retained.");
  if(overdue)note(c,"overdue","Amazon still reports payment pending after 30 days. Reserved stock is retained; age does not cancel an order.");
 }
 public void failed(Check c,String state,String detail,int delay){
  scope(c.tenant);result(c,state,c.failures+1,delay,detail);
  if(state.equals("UNCONFIRMED")||c.failures>=2)note(c,"unconfirmed",detail+" Reserved stock retained. Review this order in Seller Central; no cancellation inferred.");
 }
 private void result(Check c,String state,int failures,int delay,String detail){
  jdbc.update("UPDATE amazon_order_status_checks SET checked_at=now(),state=?,failures=?,detail=?,next_check_at=now()+make_interval(secs=>?) WHERE tenant_id=? AND connection_id=? AND amazon_order_id=?",state,failures,detail,delay,c.tenant,c.connection,c.orderId);
 }
 private void note(Check c,String kind,String detail){
  jdbc.update("""
   INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,marketplace_connection_id,
    entry_type,quantity,expiration_date,source_type,source_id,occurred_at,idempotency_key,notes,cost_status)
   SELECT DISTINCT r.tenant_id,r.account_catalog_item_id,r.location_id,r.marketplace_connection_id,
    'ORDER_STATUS_REVIEW',0,r.expiration_date,'AMAZON_ORDER',o.id,now(),
    'order-check:'||?||':'||o.id||':'||r.account_catalog_item_id||':'||r.location_id||':'||coalesce(r.expiration_date::text,'none'),?, 'FINAL'
   FROM order_inventory_reservations r JOIN amazon_orders o ON o.tenant_id=r.tenant_id
    AND o.marketplace_connection_id=r.marketplace_connection_id AND o.amazon_order_id=r.amazon_order_id
   WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.amazon_order_id=? AND r.status='ACTIVE'
   ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
   """,kind,detail,c.tenant,c.connection,c.orderId);
 }
}
