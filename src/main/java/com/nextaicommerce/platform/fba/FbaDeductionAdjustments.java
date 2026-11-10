package com.nextaicommerce.platform.fba;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.nextaicommerce.platform.web.MemberAccessService;

/** Append-only, explicitly confirmed compensation. Never edits an Amazon observation or prior debit. */
@Repository
public class FbaDeductionAdjustments {
 private final JdbcTemplate jdbc;private final FbaDepletionRepository stock;private final MemberAccessService access;
 private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
 public FbaDeductionAdjustments(JdbcTemplate jdbc,FbaDepletionRepository stock,MemberAccessService access){this.jdbc=jdbc;this.stock=stock;this.access=access;}
 public record Quantity(String sku,int original,int current,int proposed,Integer amazonShipped,String title){}
 public record Delta(UUID item,UUID location,String locationName,LocalDate expiration,UUID receiptLayer,UUID debitEntry,BigDecimal quantity,BigDecimal available,BigDecimal unitCost,String currency){}
 public record History(UUID id,Instant date,String reason,Map<String,Integer> quantities,long revision){}
 public record Review(UUID depletion,String shipment,String warehouse,Instant deductedAt,List<Quantity> lines,List<Delta> deltas,List<History> history,List<FbaDepletionRepository.LinePresentation> presentation,List<String> blockers,String revision){}
 public record Request(String shipment,Map<String,Integer> quantities,String reason,UUID requestId,String revision){}
 private record Saved(UUID id,UUID warehouse,String warehouseName,Instant date,String approval){}
 private record Debit(UUID entry,UUID item,UUID location,String locationName,LocalDate expiry,UUID layer,BigDecimal net,BigDecimal cost,String currency,Instant date){}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private String hash(Object value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private Saved saved(UUID tenant,UUID connection,String shipment){
  var rows=jdbc.query("""
   SELECT d.id,d.warehouse_id,w.name,d.created_at,d.approval::text FROM fba_shipment_depletions d
   JOIN warehouses w ON w.tenant_id=d.tenant_id AND w.id=d.warehouse_id
   WHERE d.tenant_id=? AND d.marketplace_connection_id=? AND d.shipment_id=?
   """,(r,n)->new Saved(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getTimestamp(4).toInstant(),r.getString(5)),tenant,connection,shipment);
  if(rows.isEmpty())throw new IllegalArgumentException("No saved deduction for this shipment in this store.");return rows.getFirst();
 }
 private List<FbaDepletionRepository.Composition> originalComposition(tools.jackson.databind.JsonNode values){
  var result=new ArrayList<FbaDepletionRepository.Composition>();for(var c:values)result.add(new FbaDepletionRepository.Composition(c.path("sku").asText(),UUID.fromString(c.path("item").asText()),c.path("quantity").decimalValue().stripTrailingZeros(),c.path("active").booleanValue()));return result;
 }
 private Map<String,Integer> quantities(String value){var result=new TreeMap<String,Integer>();json.readTree(value).properties().forEach(e->result.put(e.getKey(),e.getValue().intValue()));return result;}
 private List<Debit> debits(UUID tenant,UUID depletion){return jdbc.query("""
  WITH debits AS (
   SELECT a.ledger_entry_id entry,a.receipt_layer_id layer,0::bigint operation_order,x.ordinality allocation_order
   FROM fba_shipment_depletion_allocations a JOIN fba_shipment_depletions d ON d.tenant_id=a.tenant_id AND d.id=a.depletion_id
   CROSS JOIN LATERAL jsonb_array_elements(d.approval->'allocations') WITH ORDINALITY x(value,ordinality)
   WHERE a.tenant_id=? AND a.depletion_id=? AND x.value->>'layer'=a.receipt_layer_id::text
   UNION ALL SELECT a.ledger_entry_id,a.receipt_layer_id,h.revision,a.allocation_order FROM fba_deduction_adjustment_entries a
   JOIN fba_deduction_adjustments h ON h.tenant_id=a.tenant_id AND h.id=a.adjustment_id
   WHERE a.tenant_id=? AND h.depletion_id=? AND a.quantity<0
  ) SELECT e.id,e.account_catalog_item_id,e.location_id,l.code||' · '||l.name location_name,e.expiration_date,d.layer,
    -e.quantity-coalesce((SELECT sum(a.quantity) FROM fba_deduction_adjustment_entries a WHERE a.tenant_id=e.tenant_id AND a.debit_entry_id=e.id AND a.quantity>0),0) net,
    e.unit_cost,e.currency,e.occurred_at
  FROM debits d JOIN inventory_ledger_entries e ON e.tenant_id=? AND e.id=d.entry
  JOIN warehouse_locations l ON l.tenant_id=e.tenant_id AND l.id=e.location_id
  ORDER BY d.operation_order DESC,d.allocation_order DESC
  """,(r,n)->new Debit(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getObject(5,LocalDate.class),r.getObject(6,UUID.class),r.getBigDecimal(7),r.getBigDecimal(8),r.getString(9),r.getTimestamp(10).toInstant()),tenant,depletion,tenant,depletion,tenant);}
 @Transactional(readOnly=true) public Review review(UUID tenant,UUID connection,String shipment,Map<String,Integer> requested){
  scope(tenant);var saved=saved(tenant,connection,shipment);var approval=json.readTree(saved.approval());var blockers=new ArrayList<String>();
  var original=new TreeMap<String,Integer>();var titles=new HashMap<String,String>();for(var l:approval.path("lines")){original.put(l.path("sku").asText(),l.path("unitsToDeduct").intValue());titles.put(l.path("sku").asText(),l.path("title").asText());}
  var history=jdbc.query("SELECT id,created_at,reason,quantities::text,revision FROM fba_deduction_adjustments WHERE tenant_id=? AND depletion_id=? ORDER BY revision",(r,n)->new History(r.getObject(1,UUID.class),r.getTimestamp(2).toInstant(),r.getString(3),quantities(r.getString(4)),r.getLong(5)),tenant,saved.id());
  Map<String,Integer> current=history.isEmpty()?original:history.getLast().quantities();
  var desired=requested==null?new TreeMap<>(current):new TreeMap<>(requested);
  if(!desired.keySet().equals(original.keySet())||desired.values().stream().anyMatch(v->v==null||v<0||v>10000000))throw new IllegalArgumentException("Enter whole SKU quantities for every original shipment item.");
  var observed=new HashMap<String,Integer>();jdbc.query("SELECT seller_sku,quantity_shipped FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",r->{observed.put(r.getString(1),r.getInt(2));},tenant,connection,shipment);
  var lines=original.entrySet().stream().map(e->new Quantity(e.getKey(),e.getValue(),current.get(e.getKey()),desired.get(e.getKey()),observed.get(e.getKey()),titles.get(e.getKey()))).toList();
  var composition=stock.composition(tenant,connection,new ArrayList<>(original.keySet()));
  if(!approval.path("composition").isArray())blockers.add("This deduction has no verified original mapping snapshot. Inventory adjustment is unavailable; history is read-only.");
  else if(!originalComposition(approval.path("composition")).equals(composition))blockers.add("The original mapping or product status changed. Resolve the mapping before adjustment.");
  for(var line:lines){
   var components=composition.stream().filter(c->c.sku().equals(line.sku())).toList();
   if(line.proposed()>0&&(components.isEmpty()||components.stream().anyMatch(c->!c.active())))blockers.add("Complete active original mapping is required for SKU "+line.sku()+". Review is read-only.");
   if(components.stream().anyMatch(c->c.quantity().multiply(BigDecimal.valueOf(line.proposed())).stripTrailingZeros().scale()>0))blockers.add("New quantity must resolve to whole eaches for SKU "+line.sku()+".");
  }
  var debit=debits(tenant,saved.id());var lots=stock.stock(tenant,saved.warehouse(),new HashSet<>(composition.stream().map(FbaDepletionRepository.Composition::item).toList()));
  var needed=new TreeMap<UUID,BigDecimal>();var expectedCurrent=new TreeMap<UUID,BigDecimal>();
  for(var c:composition){needed.merge(c.item(),c.quantity().multiply(BigDecimal.valueOf((long)desired.get(c.sku())-current.get(c.sku()))),BigDecimal::add);expectedCurrent.merge(c.item(),c.quantity().multiply(BigDecimal.valueOf(current.get(c.sku()))),BigDecimal::add);}
  var actualCurrent=new TreeMap<UUID,BigDecimal>();debit.forEach(d->actualCurrent.merge(d.item(),d.net(),BigDecimal::add));
  var items=new HashSet<>(expectedCurrent.keySet());items.addAll(actualCurrent.keySet());
  if(items.stream().anyMatch(i->expectedCurrent.getOrDefault(i,BigDecimal.ZERO).compareTo(actualCurrent.getOrDefault(i,BigDecimal.ZERO))!=0))blockers.add("Saved allocation totals do not match the deduction history. Review is read-only.");
  var availableByPosition=new HashMap<String,BigDecimal>();
  if(!debit.isEmpty())jdbc.query("""
   SELECT e.account_catalog_item_id,e.location_id,e.expiration_date,sum(e.quantity)-coalesce((SELECT sum(r.quantity) FROM order_inventory_reservations r
    WHERE r.tenant_id=e.tenant_id AND r.account_catalog_item_id=e.account_catalog_item_id AND r.location_id=e.location_id AND r.expiration_date IS NOT DISTINCT FROM e.expiration_date AND r.status='ACTIVE'),0) available
   FROM inventory_ledger_entries e WHERE e.tenant_id=? AND e.account_catalog_item_id=ANY(?::uuid[])
   GROUP BY e.tenant_id,e.account_catalog_item_id,e.location_id,e.expiration_date
   """,r->{availableByPosition.put(r.getObject(1)+"|"+r.getObject(2)+"|"+r.getObject(3),r.getBigDecimal(4));},tenant,debit.stream().map(d->d.item().toString()).distinct().toArray(String[]::new));
  var deltas=new ArrayList<Delta>();
  for(var e:needed.entrySet()){
   BigDecimal remaining=e.getValue().abs();
   if(e.getValue().stripTrailingZeros().scale()>0){blockers.add("Mapping must resolve to whole eaches.");continue;}
   if(e.getValue().signum()>0){
    for(var lot:lots){if(!lot.item().equals(e.getKey())||remaining.signum()==0)continue;var take=remaining.min(lot.available());deltas.add(new Delta(lot.item(),lot.location(),lot.locationName(),lot.expiration(),lot.layer(),null,take.negate(),lot.available(),lot.unitCost(),lot.currency()));remaining=remaining.subtract(take);}
    if(remaining.signum()>0)blockers.add("Insufficient eligible unreserved stock for item "+e.getKey()+" (short "+remaining.toPlainString()+").");
   }else if(e.getValue().signum()<0){
    for(var d:debit){if(!d.item().equals(e.getKey())||d.net().signum()<=0||remaining.signum()==0)continue;var take=remaining.min(d.net());
     BigDecimal available=availableByPosition.getOrDefault(d.item()+"|"+d.location()+"|"+d.expiry(),BigDecimal.ZERO);
     deltas.add(new Delta(d.item(),d.location(),d.locationName(),d.expiry(),d.layer(),d.entry(),take,available,d.cost(),d.currency()));remaining=remaining.subtract(take);}
    if(remaining.signum()>0)blockers.add("Return exceeds the net original deduction.");
   }
  }
  if(deltas.stream().anyMatch(d->d.quantity().stripTrailingZeros().scale()>0))blockers.add("Allocation contains fractional eaches. Resolve the stock layers before adjustment.");
  if(desired.equals(current))blockers.add("Quantities are unchanged.");
  String revision=hash(List.of(saved,history,composition,debit,lots,deltas,desired));
  return new Review(saved.id(),shipment,saved.warehouseName(),saved.date(),lines,List.copyOf(deltas),history,stock.presentation(tenant,connection,new ArrayList<>(original.keySet())),List.copyOf(new LinkedHashSet<>(blockers)),revision);
 }
 @Transactional public UUID confirm(UUID tenant,UUID connection,String email,Request request){
  if(request.quantities()==null||request.requestId()==null||request.revision()==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>1000)throw new IllegalArgumentException("Review the quantities and enter a reason.");
  scope(tenant);var member=access.current(tenant,email);if(!access.activePlatformAdministrator(email)&&(member==null||!Set.of("OWNER","ADMIN","OPERATOR").contains(member.role())||member.restricted()&&!member.stores().contains(connection)))throw new org.springframework.security.access.AccessDeniedException("Account or store access changed.");
  stock.lockShipment(tenant,connection,request.shipment());var saved=saved(tenant,connection,request.shipment());
  var prior=jdbc.query("SELECT id,depletion_id,previous_revision,quantities::text,reason FROM fba_deduction_adjustments WHERE tenant_id=? AND request_id=?",(r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),quantities(r.getString(4)),r.getString(5)},tenant,request.requestId());
  if(!prior.isEmpty()){var p=prior.getFirst();if(!saved.id().equals(p[1])||!request.revision().equals(p[2])||!request.quantities().equals(p[3])||!request.reason().trim().equals(p[4]))throw new IllegalArgumentException("Request was already used with different adjustment details.");return (UUID)p[0];}
  var review=review(tenant,connection,request.shipment(),request.quantities());if(!review.blockers().isEmpty())throw new IllegalArgumentException(String.join(" ",review.blockers()));if(!review.revision().equals(request.revision()))throw new IllegalArgumentException("Stock or deduction changed. Review again.");
  UUID id=UUID.randomUUID(),actor=jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)",UUID.class,email);
  jdbc.update("INSERT INTO fba_deduction_adjustments(id,tenant_id,depletion_id,request_id,previous_revision,quantities,reason,created_by) VALUES (?,?,?,?,?,?::jsonb,?,?)",id,tenant,saved.id(),request.requestId(),request.revision(),json.writeValueAsString(new TreeMap<>(request.quantities())),request.reason().trim(),actor);
  int ordinal=0;for(var d:review.deltas()){
   UUID entry=UUID.randomUUID();jdbc.update("""
    INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,marketplace_connection_id,entry_type,quantity,location_id,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
    VALUES (?,?,?,?,'FBA_DEPLETION_ADJUSTMENT',?,?,?,?,?,'FBA_SHIPMENT',?,now(),?,?,?,?)
    """,entry,tenant,d.item(),connection,d.quantity(),d.location(),d.expiration(),d.unitCost(),d.currency(),saved.id(),"fba-adjustment:"+id+":"+entry,"FBA shipment "+request.shipment()+" · adjustment: "+request.reason().trim(),actor,d.unitCost()==null?"PROVISIONAL":"FINAL");
   jdbc.update("INSERT INTO fba_deduction_adjustment_entries(tenant_id,adjustment_id,ledger_entry_id,debit_entry_id,receipt_layer_id,quantity,allocation_order) VALUES (?,?,?,?,?,?,?)",tenant,id,entry,d.quantity().signum()<0?entry:d.debitEntry(),d.receiptLayer(),d.quantity(),++ordinal);
  }return id;
 }
}
