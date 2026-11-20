package com.nextaicommerce.platform.fba;

import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.fba.FbaInboundOrchestration.*;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.Scope;

/** Durable per-origin state under the same account stock lock as orders. No network in this repository. */
@Repository
public class FbaInboundCheckpoints implements Checkpoints {
 private final FbaReplenishmentPlans plans;
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 public FbaInboundCheckpoints(FbaReplenishmentPlans plans,JdbcTemplate jdbc,ObjectMapper json){this.plans=plans;this.jdbc=jdbc;this.json=json;}
 @Override @Transactional(readOnly=true)
 public Snapshot read(Scope scope,UUID warehouse){
  requireAccount(scope);plans.scope(scope.tenant());var plan=plans.read(scope.tenant(),scope.connection(),scope.replenishment(),false);requireOrigin(plan,scope,warehouse,false);
  return new Snapshot(plan.revision(),checkpoint(plan,scope,warehouse,json));
 }
 @Override @Transactional
 public Snapshot mutate(Scope scope,UUID warehouse,long expectedRevision,Audit audit,UnaryOperator<Checkpoint> transition){
  requireAccount(scope);Objects.requireNonNull(transition);
  if(audit==null||audit.requestId()==null||audit.kind()==null||audit.kind().length()>32||audit.actor()==null||audit.actor().isBlank())throw FbaReplenishmentPlans.invalid("A scoped audit identity is required.");
  if(expectedRevision < -1 || expectedRevision==-1&&!Set.of("INBOUND_ACK","INBOUND_STATUS_PENDING","INBOUND_STATUS_SUCCEEDED","INBOUND_STATUS_FAILED").contains(audit.kind()))throw FbaReplenishmentPlans.invalid("Use the current saved plan revision.");
  plans.lock(scope.tenant());var plan=plans.read(scope.tenant(),scope.connection(),scope.replenishment(),true);requireOrigin(plan,scope,warehouse,true);
  var current=checkpoint(plan,scope,warehouse,json);var details=new LinkedHashMap<String,Object>();details.put("warehouse",warehouse);details.put("details",audit.details());
  if(plans.replay(scope.tenant(),plan.id(),audit.requestId(),audit.kind(),details))return new Snapshot(plan.revision(),current);
  if(expectedRevision>=0)plans.revision(plan,expectedRevision);
  if(audit.details() instanceof Command command&&command.operation()==FbaInboundAdapter.Operation.CREATE&&(!plan.status().equals("ALLOCATED")||plan.allocations().stream().anyMatch(a->warehouse.equals(a.get("warehouse_id"))&&((Number)a.get("dispatched")).intValue()>0)))throw FbaReplenishmentPlans.invalid("Create Amazon plans only from undispatched confirmed source quantities.");
  Checkpoint next=Objects.requireNonNull(transition.apply(current));requireScope(next,scope,warehouse);
  if(!next.origin().children().isEmpty()){var units=new TreeMap<String,Integer>();next.origin().children().values().forEach(child->child.skuUnits().forEach((sku,n)->units.merge(sku,n,Math::addExact)));if(!units.equals(originUnits(plan,warehouse)))throw FbaReplenishmentPlans.invalid("Reconciled child contents differ from this warehouse's confirmed allocation.");}
  var payload=new LinkedHashMap<String,Object>(plan.checkpoint());var origins=new LinkedHashMap<String,Object>();Object raw=payload.get("inboundOrigins");if(raw instanceof Map<?,?> old)old.forEach((k,v)->origins.put(k.toString(),v));origins.put(warehouse.toString(),next);payload.put("inboundOrigins",origins);
  boolean cancelledNow=cancelled(next.origin())&&!cancelled(current.origin());
  if(cancelledNow)jdbc.update("UPDATE order_inventory_reservations r SET status='RELEASED',updated_at=now() WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.fba_plan_id=? AND r.status='ACTIVE' AND EXISTS(SELECT 1 FROM fba_replenishment_allocations a WHERE a.tenant_id=r.tenant_id AND a.reservation_id=r.id AND a.plan_id=r.fba_plan_id AND a.warehouse_id=?)",scope.tenant(),scope.connection(),plan.id(),warehouse);
  String status=cancelledNow?statusAfterCancellation(plan,origins,scope.tenant()):plan.status();
  String encoded=json.writeValueAsString(payload);if(encoded.length()>4194304)throw FbaReplenishmentPlans.invalid("This inbound checkpoint exceeds the saved plan limit.");
  jdbc.update("UPDATE fba_replenishment_plans SET checkpoint=?::jsonb,step=?,status=?,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",encoded,achievedStep(plan,origins),status,plans.actor(audit.actor()),scope.tenant(),scope.connection(),plan.id());
  plans.audit(scope.tenant(),plan.id(),audit.requestId(),audit.kind(),details,audit.actor(),Map.of("warehouse",warehouse,"packingVersion",next.origin().packingVersion()));
  return new Snapshot(Math.addExact(plan.revision(),1),next);
 }
 static boolean cancelled(FbaInboundWorkflow.Origin origin){return origin.attempts().values().stream().noneMatch(a->a.status()==FbaInboundWorkflow.Status.PENDING)&&origin.attempts().values().stream().anyMatch(a->a.operation()==FbaInboundAdapter.Operation.CANCEL&&a.status()==FbaInboundWorkflow.Status.SUCCEEDED);}
 String statusAfterCancellation(FbaReplenishmentPlans.Plan plan,Map<String,Object> origins,UUID tenant){
  var warehouses=new HashSet<String>();plan.allocations().stream().filter(a->!"RELEASED".equals(a.get("reservation_status"))).forEach(a->warehouses.add(a.get("warehouse_id").toString()));warehouses.addAll(origins.keySet());
  boolean allCancelled=!warehouses.isEmpty()&&warehouses.stream().allMatch(warehouse->{Object raw=origins.get(warehouse);return raw!=null&&cancelled(json.readValue(json.writeValueAsString(raw),Checkpoint.class).origin());});if(allCancelled)return "CANCELLED";
  return jdbc.queryForObject("SELECT CASE WHEN EXISTS(SELECT 1 FROM order_inventory_reservations WHERE tenant_id=? AND fba_plan_id=? AND status='ACTIVE') THEN CASE WHEN EXISTS(SELECT 1 FROM fba_replenishment_allocations WHERE tenant_id=? AND plan_id=? AND dispatched>0) THEN 'PART_DISPATCHED' ELSE 'ALLOCATED' END ELSE 'DISPATCHED' END",String.class,tenant,plan.id(),tenant,plan.id());
 }
 int achievedStep(FbaReplenishmentPlans.Plan plan,Map<String,Object> origins){
  var warehouses=new HashSet<UUID>();plan.allocations().stream().filter(a->!"RELEASED".equals(a.get("reservation_status"))).forEach(a->warehouses.add((UUID)a.get("warehouse_id")));if(warehouses.isEmpty())return plan.step();
  var states=new ArrayList<FbaInboundWorkflow.Origin>();for(UUID warehouse:warehouses){Object raw=origins.get(warehouse.toString());if(raw==null)return plan.step();states.add(json.readValue(json.writeValueAsString(raw),Checkpoint.class).origin());}
  boolean placement=states.stream().allMatch(o->completed(o,FbaInboundAdapter.Operation.CONFIRM_PLACEMENT,null)&&o.attempts().values().stream().noneMatch(a->a.operation()==FbaInboundAdapter.Operation.CANCEL&&a.status()==FbaInboundWorkflow.Status.SUCCEEDED));
  boolean transport=placement&&states.stream().allMatch(o->!o.children().isEmpty()&&o.children().keySet().stream().allMatch(child->completed(o,FbaInboundAdapter.Operation.CONFIRM_TRANSPORT,child)));
  int stage=transport?5:placement?4:states.stream().anyMatch(o->completed(o,FbaInboundAdapter.Operation.PACKING_INFORMATION,null)||completed(o,FbaInboundAdapter.Operation.PLACEMENT_OPTIONS,null))?3:plan.step();return Math.max(plan.step(),stage);
 }
 static boolean completed(FbaInboundWorkflow.Origin origin,FbaInboundAdapter.Operation operation,String child){return origin.attempts().values().stream().anyMatch(a->a.operation()==operation&&a.status()==FbaInboundWorkflow.Status.SUCCEEDED&&a.packingVersion()==origin.packingVersion()&&(child==null||child.equals(a.childId())));}
 static void requireAccount(Scope scope){if(scope==null||!scope.tenant().equals(scope.account()))throw FbaReplenishmentPlans.invalid("Inbound account does not match this account's inventory scope.");}
 void requireOrigin(FbaReplenishmentPlans.Plan plan,Scope scope,UUID warehouse,boolean mutation){
  if(warehouse==null||!Set.of("ALLOCATED","PART_DISPATCHED","DISPATCHED","CANCELLED").contains(plan.status())||mutation&&plan.status().equals("CANCELLED"))throw FbaReplenishmentPlans.invalid("A confirmed source allocation is required.");
  Object raw=plan.checkpoint().get("inboundOrigins");boolean stored=raw instanceof Map<?,?> values&&values.containsKey(warehouse.toString());
  if(stored&&(!mutation||cancelled(checkpoint(plan,scope,warehouse,json).origin()))){if(plan.allocations().stream().noneMatch(a->warehouse.equals(a.get("warehouse_id"))))throw FbaReplenishmentPlans.invalid("This origin is outside the plan's source history.");return;}
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouses WHERE tenant_id=? AND id=? AND status='ACTIVE')",Boolean.class,scope.tenant(),warehouse)))throw FbaReplenishmentPlans.invalid("Choose a managed active warehouse in this account.");
  originUnits(plan,warehouse);
 }
 static Map<String,Integer> originUnits(FbaReplenishmentPlans.Plan plan,UUID warehouse){
  var eaches=new HashMap<String,Integer>();var ratios=new HashMap<String,Integer>();
  for(var allocation:plan.allocations())if(warehouse.equals(allocation.get("warehouse_id"))&&!"RELEASED".equals(allocation.get("reservation_status"))){
   String key=allocation.get("seller_sku")+":"+allocation.get("account_catalog_item_id");int ratio=((Number)allocation.get("each_per_sku")).intValue();
   if(ratio<1)throw FbaReplenishmentPlans.invalid("Invalid source composition.");Integer prior=ratios.putIfAbsent(key,ratio);if(prior!=null&&prior!=ratio)throw FbaReplenishmentPlans.invalid("Source composition changed.");
   eaches.merge(key,((Number)allocation.get("quantity")).intValue(),Math::addExact);
  }
  var result=new TreeMap<String,Integer>();for(var entry:eaches.entrySet()){int ratio=ratios.get(entry.getKey());if(entry.getValue()<=0||entry.getValue()%ratio!=0)throw FbaReplenishmentPlans.invalid("Source must contain complete SKU units.");String sku=entry.getKey().substring(0,entry.getKey().lastIndexOf(':'));int units=entry.getValue()/ratio;Integer prior=result.putIfAbsent(sku,units);if(prior!=null&&prior!=units)throw FbaReplenishmentPlans.invalid("Bundle quantities differ within this source warehouse.");}
  if(result.isEmpty())throw FbaReplenishmentPlans.invalid("This warehouse is outside the confirmed source allocation.");return Collections.unmodifiableMap(result);
 }
 static Checkpoint checkpoint(FbaReplenishmentPlans.Plan plan,Scope scope,UUID warehouse,ObjectMapper json){
  Object raw=plan.checkpoint().get("inboundOrigins");Object origin=raw instanceof Map<?,?> origins?origins.get(warehouse.toString()):null;
  var state=origin==null?Checkpoint.initial(scope,warehouse):json.readValue(json.writeValueAsString(origin),Checkpoint.class);requireScope(state,scope,warehouse);return state;
 }
 static void requireScope(Checkpoint state,Scope scope,UUID warehouse){if(!state.origin().scope().equals(scope)||!state.origin().warehouse().equals(warehouse))throw FbaReplenishmentPlans.invalid("Saved inbound state belongs to another account, store, plan or warehouse.");}
}
