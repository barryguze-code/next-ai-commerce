package com.nextaicommerce.platform.fba;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.fba.FbaReplenishmentPlans.invalid;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.*;

/** Exact child operations consume the parent's existing reservations; splits never reserve stock again. */
@Repository
public class FbaChildDispatches {
 public record Command(UUID requestId,long revision,UUID warehouse,String shipment,long packingVersion,Map<String,Integer> skuUnits,String reason){}
 public record Recorded(UUID warehouse,String shipment,long packingVersion,Map<String,Integer> skuUnits,Map<UUID,Integer> consumption,String reason,Instant updatedAt){}
 private final FbaReplenishmentPlans plans;
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 public FbaChildDispatches(FbaReplenishmentPlans plans,JdbcTemplate jdbc,ObjectMapper json){this.plans=plans;this.jdbc=jdbc;this.json=json;}
 @Transactional public FbaReplenishmentPlans.Plan dispatch(UUID tenant,UUID connection,UUID id,String actor,Command command){
  validate(command,false);plans.lock(tenant);var plan=plans.read(tenant,connection,id,true);
  if(plans.replay(tenant,id,command.requestId(),"CHILD_DISPATCH",command))return plan;plans.revision(plan,command.revision());
  if(!Set.of("ALLOCATED","PART_DISPATCHED").contains(plan.status()))throw invalid("Confirm allocation before dispatching a child shipment.");
  Origin origin=origin(plan,tenant,connection,command);validateChildQuantities(origin,command);
  var previous=recorded(plan,command.warehouse(),command.shipment());var increases=new TreeMap<String,Integer>();
  for(var entry:command.skuUnits().entrySet()){int change=entry.getValue()-(previous==null?0:previous.skuUnits().getOrDefault(entry.getKey(),0));if(change<0)throw invalid("Use an audited correction to reduce previously dispatched quantities.");if(change>0)increases.put(entry.getKey(),change);}
  if(increases.isEmpty())return plan;
  var rows=allocations(tenant,connection,id,command.warehouse());var consumed=previous==null?new LinkedHashMap<UUID,Integer>():new LinkedHashMap<>(previous.consumption());
  consume(tenant,connection,id,actor,command,rows,increases,consumed,"FBA_DISPATCH");
  return save(plan,tenant,connection,actor,command,"CHILD_DISPATCH",new Recorded(command.warehouse(),command.shipment(),command.packingVersion(),Map.copyOf(command.skuUnits()),Map.copyOf(consumed),command.reason().trim(),Instant.now()));
 }
 @Transactional public FbaReplenishmentPlans.Plan correct(UUID tenant,UUID connection,UUID id,String actor,Command command){
  validate(command,true);plans.lock(tenant);var plan=plans.read(tenant,connection,id,true);
  if(plans.replay(tenant,id,command.requestId(),"CHILD_CORRECTION",command))return plan;plans.revision(plan,command.revision());
  if(!Set.of("ALLOCATED","PART_DISPATCHED","DISPATCHED").contains(plan.status()))throw invalid("Cancelled or released shipment sources need an explicit inventory review before correction.");
  Origin origin=origin(plan,tenant,connection,command);Child child=origin.children().get(command.shipment());
  validateChildQuantities(origin,command);
  var previous=recorded(plan,command.warehouse(),command.shipment());if(previous==null)throw invalid("Record dispatch before correcting its operational quantities.");
  var rows=allocations(tenant,connection,id,command.warehouse());var consumed=new LinkedHashMap<>(previous.consumption());
  var increases=new TreeMap<String,Integer>();var decreases=new TreeMap<String,Integer>();for(var entry:command.skuUnits().entrySet()){int change=entry.getValue()-previous.skuUnits().getOrDefault(entry.getKey(),0);if(change>0)increases.put(entry.getKey(),change);else if(change<0)decreases.put(entry.getKey(),-change);}
  restore(tenant,connection,id,actor,command,rows,decreases,consumed);
  // A correction may contain both directions. Reload locked reservations after restoration.
  if(!increases.isEmpty())consume(tenant,connection,id,actor,command,allocations(tenant,connection,id,command.warehouse()),increases,consumed,"FBA_DISPATCH_CORRECTION");
  return save(plan,tenant,connection,actor,command,"CHILD_CORRECTION",new Recorded(command.warehouse(),command.shipment(),command.packingVersion(),Map.copyOf(command.skuUnits()),Map.copyOf(consumed),command.reason().trim(),Instant.now()));
 }
 static void validate(Command command,boolean correction){
  if(command==null||command.requestId()==null||command.revision()<0||command.warehouse()==null||command.shipment()==null||command.shipment().isBlank()||command.shipment().length()>100||command.packingVersion()<1||command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.skuUnits()==null||command.skuUnits().isEmpty()||command.skuUnits().size()>500)throw invalid("Choose a child shipment, current revision, whole SKU quantities and a reason.");
  for(var entry:command.skuUnits().entrySet())if(entry.getKey()==null||entry.getKey().isBlank()||entry.getKey().length()>240||entry.getValue()==null||entry.getValue()<0||entry.getValue()>1000000)throw invalid("Use bounded whole SKU quantities.");
  if(!correction&&command.skuUnits().values().stream().noneMatch(n->n>0))throw invalid("Enter at least one actual dispatched SKU unit.");
 }
 static void validateChildQuantities(Origin origin,Command command){var child=origin.children().get(command.shipment());if(child==null||!child.skuUnits().keySet().equals(command.skuUnits().keySet())||command.skuUnits().entrySet().stream().anyMatch(e->e.getValue()>child.skuUnits().get(e.getKey())))throw invalid("Actual cumulative quantities must stay within this genuine child's allocated SKU contents.");}
 Origin origin(FbaReplenishmentPlans.Plan plan,UUID tenant,UUID connection,Command command){
  var scope=new Scope(tenant,tenant,connection,plan.id());var checkpoint=FbaInboundCheckpoints.checkpoint(plan,scope,command.warehouse(),json);var origin=checkpoint.origin();
  if(origin.amazonPlanId()==null||origin.packingVersion()!=command.packingVersion()||!origin.children().containsKey(command.shipment()))throw invalid("Reconcile this origin's genuine Amazon shipment and current packing before dispatch.");
  if(origin.attempts().values().stream().anyMatch(a->a.status()==Status.PENDING||a.operation()==FbaInboundAdapter.Operation.CANCEL&&a.status()==Status.SUCCEEDED))throw invalid("Reconcile pending or cancelled Amazon activity before changing dispatched quantities.");
  boolean placement=origin.attempts().values().stream().anyMatch(a->a.operation()==FbaInboundAdapter.Operation.CONFIRM_PLACEMENT&&a.status()==Status.SUCCEEDED&&a.packingVersion()==origin.packingVersion());
  boolean transport=origin.attempts().values().stream().anyMatch(a->a.operation()==FbaInboundAdapter.Operation.CONFIRM_TRANSPORT&&a.status()==Status.SUCCEEDED&&a.packingVersion()==origin.packingVersion()&&command.shipment().equals(a.childId()));
  if(!placement||!transport)throw invalid("Confirm and reconcile placement and this child's transport before dispatch.");
  var total=new TreeMap<String,Integer>();origin.children().values().forEach(child->child.skuUnits().forEach((sku,n)->total.merge(sku,n,Math::addExact)));if(!total.equals(FbaInboundCheckpoints.originUnits(plan,command.warehouse())))throw invalid("Issued children do not match the confirmed source allocation.");return origin;
 }
 List<Map<String,Object>> allocations(UUID tenant,UUID connection,UUID id,UUID warehouse){return jdbc.queryForList("""
  SELECT a.*,r.account_catalog_item_id item,r.location_id,r.expiration_date,r.unit_cost,r.cost_currency,r.quantity reserved,r.status reservation_status
  FROM fba_replenishment_allocations a JOIN order_inventory_reservations r ON r.tenant_id=a.tenant_id AND r.id=a.reservation_id
  WHERE a.tenant_id=? AND a.marketplace_connection_id=? AND a.plan_id=? AND a.warehouse_id=?
  ORDER BY a.seller_sku,r.expiration_date NULLS LAST,a.created_at,a.id FOR UPDATE OF a,r
  """,tenant,connection,id,warehouse);}
 static Map<String,Integer> requirements(List<Map<String,Object>> rows,Map<String,Integer> units){
  var ratios=new HashMap<String,Integer>();for(var row:rows)if(!"RELEASED".equals(row.get("reservation_status"))&&units.containsKey(row.get("seller_sku"))){String key=row.get("seller_sku")+":"+row.get("item");int ratio=((Number)row.get("each_per_sku")).intValue();Integer prior=ratios.putIfAbsent(key,ratio);if(ratio<1||prior!=null&&prior!=ratio)throw invalid("The child's component ratio changed.");}
  var result=new LinkedHashMap<String,Integer>();for(var entry:ratios.entrySet()){String sku=entry.getKey().substring(0,entry.getKey().lastIndexOf(':'));result.put(entry.getKey(),Math.multiplyExact(units.get(sku),entry.getValue()));}
  for(String sku:units.keySet())if(ratios.keySet().stream().noneMatch(k->k.substring(0,k.lastIndexOf(':')).equals(sku)))throw invalid("This child has no matching allocated component.");return result;
 }
 void consume(UUID tenant,UUID connection,UUID id,String actor,Command command,List<Map<String,Object>> rows,Map<String,Integer> units,Map<UUID,Integer> consumed,String entryType){
  var needed=requirements(rows,units);
  for(var row:rows){String key=row.get("seller_sku")+":"+row.get("item");int required=needed.getOrDefault(key,0);if(required<1||!"ACTIVE".equals(row.get("reservation_status")))continue;
   int reserved=((Number)row.get("reserved")).intValue(),take=Math.min(required,reserved);if(take<1)continue;ensureCovered(tenant,row);
   movement(tenant,connection,id,actor,command,row,-take,entryType,"consume");
   if(take==reserved)jdbc.update("UPDATE order_inventory_reservations SET status='SHIPPED',updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?",tenant,row.get("reservation_id"),id);
   else jdbc.update("UPDATE order_inventory_reservations SET quantity=quantity-?,updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?",take,tenant,row.get("reservation_id"),id);
   jdbc.update("UPDATE fba_replenishment_allocations SET dispatched=dispatched+? WHERE tenant_id=? AND id=?",take,tenant,row.get("id"));consumed.merge((UUID)row.get("id"),take,Math::addExact);needed.put(key,required-take);
  }
  if(needed.values().stream().anyMatch(n->n!=0))throw invalid("The child's complete contents exceed the remaining source reservation. No dispatch was applied.");
 }
 void restore(UUID tenant,UUID connection,UUID id,String actor,Command command,List<Map<String,Object>> rows,Map<String,Integer> units,Map<UUID,Integer> consumed){
  var needed=requirements(rows,units);var reversed=new ArrayList<>(rows);Collections.reverse(reversed);
  for(var row:reversed){String key=row.get("seller_sku")+":"+row.get("item");int required=needed.getOrDefault(key,0),prior=consumed.getOrDefault(row.get("id"),0);if(required<1||prior<1)continue;
   if(!Set.of("ACTIVE","SHIPPED").contains(row.get("reservation_status")))throw invalid("This source reservation was released. Review inventory before restoring dispatched stock.");int take=Math.min(required,prior);
   movement(tenant,connection,id,actor,command,row,take,"FBA_DISPATCH_CORRECTION","restore");
   if("ACTIVE".equals(row.get("reservation_status")))jdbc.update("UPDATE order_inventory_reservations SET quantity=quantity+?,updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?",take,tenant,row.get("reservation_id"),id);
   else jdbc.update("UPDATE order_inventory_reservations SET status='ACTIVE',quantity=?,updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?",take,tenant,row.get("reservation_id"),id);
   jdbc.update("UPDATE fba_replenishment_allocations SET dispatched=dispatched-? WHERE tenant_id=? AND id=?",take,tenant,row.get("id"));if(take==prior)consumed.remove(row.get("id"));else consumed.put((UUID)row.get("id"),prior-take);needed.put(key,required-take);
  }
  if(needed.values().stream().anyMatch(n->n!=0))throw invalid("Correction exceeds this child's recorded source-layer dispatch. No inventory adjustment was applied.");
 }
 void ensureCovered(UUID tenant,Map<String,Object> row){
  BigDecimal physical=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ?",BigDecimal.class,tenant,row.get("item"),row.get("location_id"),row.get("expiration_date"));
  BigDecimal reserved=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ? AND status='ACTIVE'",BigDecimal.class,tenant,row.get("item"),row.get("location_id"),row.get("expiration_date"));
  if(physical==null||reserved==null||physical.compareTo(reserved)<0)throw invalid("Physical stock no longer covers reservations. Reconcile this location first.");
 }
 void movement(UUID tenant,UUID connection,UUID id,String actor,Command command,Map<String,Object> row,int each,String type,String direction){
  jdbc.update("""
   INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,marketplace_connection_id,entry_type,quantity,location_id,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
   VALUES (?,?,?,?,?,?,?,?,?,?,'FBA_REPLENISHMENT',?,now(),?,?,?,?)
   """,UUID.randomUUID(),tenant,row.get("item"),connection,type,each,row.get("location_id"),row.get("expiration_date"),row.get("unit_cost"),row.get("cost_currency"),id,"fba-child:"+command.requestId()+":"+row.get("id")+":"+direction,"FBA child "+command.shipment()+" · "+command.reason().trim(),plans.actor(actor),row.get("unit_cost")==null?"PROVISIONAL":"FINAL");
 }
 Recorded recorded(FbaReplenishmentPlans.Plan plan,UUID warehouse,String child){Object raw=plan.checkpoint().get("childDispatches");Object value=raw instanceof Map<?,?> all?all.get(warehouse+":"+child):null;return value==null?null:json.readValue(json.writeValueAsString(value),Recorded.class);}
 FbaReplenishmentPlans.Plan save(FbaReplenishmentPlans.Plan plan,UUID tenant,UUID connection,String actor,Command command,String kind,Recorded record){
  var payload=new LinkedHashMap<String,Object>(plan.checkpoint());var children=new LinkedHashMap<String,Object>();Object raw=payload.get("childDispatches");if(raw instanceof Map<?,?> old)old.forEach((k,v)->children.put(k.toString(),v));children.put(command.warehouse()+":"+command.shipment(),record);payload.put("childDispatches",children);
  String status=jdbc.queryForObject("SELECT CASE WHEN EXISTS(SELECT 1 FROM order_inventory_reservations WHERE tenant_id=? AND fba_plan_id=? AND status='ACTIVE') THEN CASE WHEN EXISTS(SELECT 1 FROM fba_replenishment_allocations WHERE tenant_id=? AND plan_id=? AND dispatched>0) THEN 'PART_DISPATCHED' ELSE 'ALLOCATED' END ELSE 'DISPATCHED' END",String.class,tenant,plan.id(),tenant,plan.id());
  jdbc.update("UPDATE fba_replenishment_plans SET checkpoint=?::jsonb,status=?,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(payload),status,plans.actor(actor),tenant,connection,plan.id());
  plans.audit(tenant,plan.id(),command.requestId(),kind,command,actor,Map.of("warehouse",command.warehouse(),"shipment",command.shipment(),"operationalSkuUnits",record.skuUnits(),"message",kind.equals("CHILD_CORRECTION")?"Audited warehouse adjustment; Amazon observations unchanged. Unsent source units remain reserved.":"Only this child's source units deducted; other children remain reserved."));return plans.read(tenant,connection,plan.id(),false);
 }
}
