package com.nextaicommerce.platform.fba;
import java.util.*;import java.math.*;import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Repository;import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.receiving.InventoryRepository;

/** Stock transitions share the customer-order tenant lock and reservation table. Draft checkpoints are non-operational. */
@Repository
public class FbaReplenishmentPlans {
 public record Line(String sku,int units){}
 public record Draft(UUID id,long revision,String name,int step,List<Line> lines,Map<String,Object> packing){}
 public record Plan(UUID id,String name,String status,long revision,int step,Map<String,Object> checkpoint,List<Map<String,Object>> allocations,List<Map<String,Object>> actions){}
 public record Choice(String sku,UUID layer,int each){}
 public record Allocate(UUID requestId,long revision,List<Choice> choices){}
 public record DispatchLine(String sku,UUID warehouse,int units){}
 public record Dispatch(UUID requestId,long revision,List<DispatchLine> lines,String reason){}
 public record Cancel(UUID requestId,long revision,String reason){}
 public record TransportInput(Instant readyToShipAt){}
 public record Preparation(String prepOwner,String labelOwner,boolean confirmed){}
 public record Checkpoint(UUID requestId,long revision,int step,List<FbaPackingManifest.Carton> cartons,Map<UUID,TransportInput> transportByOrigin,Map<UUID,Map<String,Preparation>> preparationByOrigin){
  public Checkpoint(UUID requestId,long revision,int step,List<FbaPackingManifest.Carton> cartons){this(requestId,revision,step,cartons,null,null);}
  public Checkpoint(UUID requestId,long revision,int step,List<FbaPackingManifest.Carton> cartons,Map<UUID,TransportInput> transportByOrigin){this(requestId,revision,step,cartons,transportByOrigin,null);}
 }
 public record Stock(UUID layer,UUID item,UUID location,UUID warehouse,String warehouseName,String locationName,String name,LocalDate expiration,BigDecimal available,BigDecimal cost,String currency){}
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final InventoryRepository inventory;
 @org.springframework.beans.factory.annotation.Autowired private com.nextaicommerce.platform.orders.PackingPackageNames packageNames;
 public FbaReplenishmentPlans(JdbcTemplate jdbc,ObjectMapper json,InventoryRepository inventory){this.jdbc=jdbc;this.json=json;this.inventory=inventory;}
 void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 void lock(UUID tenant){scope(tenant);jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,tenant.toString());}
 UUID actor(String email){return jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?) AND status='ACTIVE'",UUID.class,email);}
 static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
 @Transactional(readOnly=true) public List<Map<String,Object>> list(UUID tenant,UUID connection){scope(tenant);return jdbc.queryForList("SELECT id,name,status,revision,step,updated_at FROM fba_replenishment_plans WHERE tenant_id=? AND marketplace_connection_id=? ORDER BY updated_at DESC LIMIT 100",tenant,connection);}
 @Transactional(readOnly=true) public Plan get(UUID tenant,UUID connection,UUID id){scope(tenant);return read(tenant,connection,id,false);}
 @SuppressWarnings("unchecked") Plan read(UUID tenant,UUID connection,UUID id,boolean locked){var rows=jdbc.queryForList("SELECT * FROM fba_replenishment_plans WHERE tenant_id=? AND marketplace_connection_id=? AND id=?"+(locked?" FOR UPDATE":""),tenant,connection,id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FBA replenishment not found in this store.");var p=rows.getFirst();return new Plan(id,(String)p.get("name"),(String)p.get("status"),((Number)p.get("revision")).longValue(),((Number)p.get("step")).intValue(),json.readValue(p.get("checkpoint").toString(),Map.class),jdbc.queryForList("SELECT a.*,r.status reservation_status,r.account_catalog_item_id,r.expiration_date FROM fba_replenishment_allocations a JOIN order_inventory_reservations r ON r.tenant_id=a.tenant_id AND r.id=a.reservation_id WHERE a.tenant_id=? AND a.plan_id=? ORDER BY warehouse_id,seller_sku,a.id",tenant,id),jdbc.queryForList("SELECT kind,created_at,result FROM fba_replenishment_actions WHERE tenant_id=? AND plan_id=? ORDER BY created_at DESC LIMIT 100",tenant,id));}
 @Transactional public Plan save(UUID tenant,UUID connection,String email,Draft d){
  validate(d);lock(tenant);if(d.step()>1)throw invalid("Confirm source allocation before packing.");UUID user=actor(email);String payload=json.writeValueAsString(Map.of("lines",d.lines(),"packing",d.packing()==null?Map.of():d.packing()));
  requireEditableSlot(tenant,connection,d.id());
  if(payload.length()>262144)throw invalid("Draft details are too large. Use up to 500 SKU lines.");
  int inserted=jdbc.update("INSERT INTO fba_replenishment_plans(id,tenant_id,marketplace_connection_id,name,step,checkpoint,created_by,updated_by) VALUES (?,?,?,?,?,?::jsonb,?,?) ON CONFLICT(id) DO NOTHING",d.id(),tenant,connection,d.name().trim(),d.step(),payload,user,user);
  var p=read(tenant,connection,d.id(),true);if(inserted==1){if(d.revision()!=0)throw invalid("New drafts start at revision zero.");return p;}
  var retained=new LinkedHashMap<String,Object>(p.checkpoint());retained.put("lines",d.lines());retained.put("packing",d.packing()==null?Map.of():d.packing());boolean productsChanged=!json.valueToTree(p.checkpoint().get("lines")).equals(json.valueToTree(d.lines()));if(productsChanged){retained.remove("cartons");retained.put("packing",Map.of());}payload=json.writeValueAsString(retained);
  if(p.name().equals(d.name().trim())&&p.step()==d.step()&&p.checkpoint().equals(json.readValue(payload,Map.class)))return p;
  if(p.revision()!=d.revision())throw invalid("This plan changed. Reload its saved version.");if(!p.status().equals("DRAFT"))throw invalid("Release the remaining allocation before changing products or packing.");
  jdbc.update("UPDATE fba_replenishment_plans SET name=?,step=?,checkpoint=?::jsonb,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",d.name().trim(),d.step(),payload,user,tenant,d.id());return read(tenant,connection,d.id(),false);
 }
 static void validate(Draft d){if(d==null||d.id()==null||d.revision()<0||d.name()==null||d.name().isBlank()||d.name().length()>160||d.name().toLowerCase(Locale.ROOT).contains("draft")||d.step()<0||d.step()>5||d.lines()==null||d.lines().size()>500)throw invalid("Enter a saved name without Draft and up to 500 SKU lines.");var seen=new HashSet<String>();for(var l:d.lines())if(l==null||l.sku()==null||l.sku().isBlank()||l.sku().length()>240||!seen.add(l.sku())||l.units()<1||l.units()>1000000)throw invalid("Choose unique SKUs and positive whole units.");}
 void requireEditableSlot(UUID tenant,UUID connection,UUID id){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_replenishment_plans WHERE tenant_id=? AND marketplace_connection_id=? AND status='DRAFT' AND id<>?)",Boolean.class,tenant,connection,id)))throw invalid("This store already has an editable FBA replenishment draft. Open it or cancel it before creating another.");}
 record Component(String sku,UUID item,int quantity,BigDecimal masterCase){Component(String sku,UUID item,int quantity){this(sku,item,quantity,BigDecimal.ONE);}}
 public record SourceProfile(UUID item,BigDecimal netWeight,String weightUnit,BigDecimal length,BigDecimal width,BigDecimal height,String dimensionUnit,String measurementBasis,boolean actualConfirmed,Boolean expirationRequired){public SourceProfile(UUID item,BigDecimal netWeight,String weightUnit,BigDecimal length,BigDecimal width,BigDecimal height,String dimensionUnit,String measurementBasis,boolean actualConfirmed){this(item,netWeight,weightUnit,length,width,height,dimensionUnit,measurementBasis,actualConfirmed,null);}}
 List<Component> components(UUID tenant,UUID connection,List<Line> lines){var skus=lines.stream().map(Line::sku).toArray(String[]::new);var result=jdbc.query("""
  SELECT m.marketplace_sku,c.account_catalog_item_id,CASE WHEN a.status='ACTIVE' AND a.deleted_at IS NULL THEN c.quantity ELSE 0 END quantity,coalesce(a.replenishment_units_per_case,offer.units_per_case,g.units_per_case) master_case FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
  JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
  JOIN global_catalog_products g ON g.id=a.global_product_id
  LEFT JOIN LATERAL (
   SELECT packaging.units_per_case FROM vendor_catalog_offers offer
   JOIN vendors vendor ON vendor.tenant_id=offer.tenant_id AND vendor.id=offer.vendor_id AND vendor.status='ACTIVE'
   LEFT JOIN global_product_packaging_versions packaging ON packaging.id=offer.packaging_version_id
   WHERE offer.tenant_id=a.tenant_id AND offer.account_catalog_item_id=a.id AND offer.effective_from<=current_date
    AND (offer.effective_to IS NULL OR offer.effective_to>=current_date)
   ORDER BY offer.is_default DESC,offer.updated_at DESC,offer.id LIMIT 1
  ) offer ON true
  WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.marketplace_sku=ANY(?::text[]) AND m.status='ACTIVE'
  AND EXISTS(SELECT 1 FROM amazon_listings l WHERE l.tenant_id=m.tenant_id AND l.marketplace_connection_id=m.marketplace_connection_id AND l.seller_sku=m.marketplace_sku AND (upper(coalesce(l.fulfillment_channel,'')) LIKE '%AMAZON%' OR upper(l.fulfillment_channel) IN ('AFN','FBA')))
  ORDER BY m.marketplace_sku,c.account_catalog_item_id
  """,(r,n)->new Component(r.getString(1),r.getObject(2,UUID.class),wholeMapping(r.getBigDecimal(3)),wholeCase(r.getBigDecimal(4))),tenant,connection,skus);
  for(var line:lines)if(result.stream().noneMatch(c->c.sku().equals(line.sku()))||result.stream().anyMatch(c->c.quantity()<=0))throw invalid("Complete the active FBA mapping for "+line.sku()+" before allocation.");return result;
 }
 static BigDecimal wholeCase(BigDecimal quantity){return quantity!=null&&quantity.signum()>0&&quantity.stripTrailingZeros().scale()<=0?quantity:null;}
 static int wholeMapping(BigDecimal quantity){try{if(quantity==null)return 0;return quantity.intValueExact();}catch(ArithmeticException e){throw invalid("FBA mappings require positive whole product units.");}}
 @SuppressWarnings("unchecked") List<Line> lines(Plan plan){var out=new ArrayList<Line>();for(var raw:(List<Map<String,Object>>)plan.checkpoint().getOrDefault("lines",List.of()))out.add(new Line((String)raw.get("sku"),((Number)raw.get("units")).intValue()));return out;}
 @Transactional(readOnly=true) public Map<String,Object> sources(UUID tenant,UUID connection,UUID id){scope(tenant);var p=read(tenant,connection,id,false);var c=components(tenant,connection,lines(p));var ids=c.stream().map(Component::item).distinct().toList();return Map.of("plan",p,"components",c,"stock",stock(tenant,ids),"sourceProfiles",sourceProfiles(tenant,ids));}
 List<SourceProfile> sourceProfiles(UUID tenant,List<UUID> ids){if(ids.isEmpty())return List.of();return jdbc.query("""
  SELECT a.id,g.net_weight,g.weight_unit,g.length,g.width,g.height,g.dimension_unit,g.requires_expiration_date
  FROM account_catalog_items a JOIN global_catalog_products g ON g.id=a.global_product_id
  WHERE a.tenant_id=? AND a.id=ANY(?::uuid[]) AND a.status='ACTIVE' AND a.deleted_at IS NULL ORDER BY a.id
  """,(r,n)->new SourceProfile(r.getObject(1,UUID.class),r.getBigDecimal(2),r.getString(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6),r.getString(7),"Catalogue dimensions and net weight; actual packed carton measurements not verified",false,r.getObject(8,Boolean.class)),tenant,ids.toArray(UUID[]::new));}
 List<Stock> stock(UUID tenant,List<UUID> ids){if(ids.isEmpty())return List.of();LocalDate today=LocalDate.now(ZoneId.of("America/Los_Angeles"));var policy=inventory.shelfLifePolicy(tenant);var allowed=new HashMap<String,BigDecimal>();for(var p:inventory.inventory(tenant,ids))if(p.daysRemaining(today)>=0&&!p.marketplaceStoppedByPlan()&&(!policy.autoZeroMarketplaceSellable()||p.daysRemaining(today)>policy.minimumSellableDays()))allowed.put(p.itemId()+":"+p.locationId()+":"+p.expirationDate(),p.quantity().subtract(p.reservedQuantity()).max(BigDecimal.ZERO));
  var rows=jdbc.query("""
   WITH layers AS (
    SELECT e.*,coalesce(sum(quantity) FILTER(WHERE quantity>0) OVER(PARTITION BY e.account_catalog_item_id,e.location_id,e.expiration_date ORDER BY occurred_at,e.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior_positive,
    coalesce(sum(-quantity) FILTER(WHERE quantity<0) OVER(PARTITION BY e.account_catalog_item_id,e.location_id,e.expiration_date),0) debits,
    w.id warehouse,w.name warehouse_name,loc.code,coalesce(nullif(a.display_name,''),g.canonical_name,a.account_sku) name
    FROM inventory_ledger_entries e JOIN warehouse_locations loc ON loc.tenant_id=e.tenant_id AND loc.id=e.location_id AND loc.status='ACTIVE'
    JOIN warehouses w ON w.tenant_id=loc.tenant_id AND w.id=loc.warehouse_id AND w.status='ACTIVE'
    JOIN account_catalog_items a ON a.tenant_id=e.tenant_id AND a.id=e.account_catalog_item_id AND a.status='ACTIVE' AND a.deleted_at IS NULL JOIN global_catalog_products g ON g.id=a.global_product_id
    WHERE e.tenant_id=? AND e.account_catalog_item_id=ANY(?::uuid[])
   ) SELECT x.*,greatest(0,quantity-greatest(0,debits-prior_positive)-coalesce((SELECT sum(r.quantity) FROM order_inventory_reservations r WHERE r.tenant_id=x.tenant_id AND r.cost_layer_id=x.id AND r.status='ACTIVE'),0)) available
   FROM layers x WHERE quantity>0 AND NOT EXISTS(SELECT 1 FROM order_inventory_reservations r WHERE r.tenant_id=x.tenant_id AND r.account_catalog_item_id=x.account_catalog_item_id AND r.location_id=x.location_id AND r.expiration_date IS NOT DISTINCT FROM x.expiration_date AND r.status='ACTIVE' AND r.cost_layer_id IS NULL)
   ORDER BY expiration_date ASC NULLS LAST,occurred_at,id
   """,(r,n)->new Stock(r.getObject("id",UUID.class),r.getObject("account_catalog_item_id",UUID.class),r.getObject("location_id",UUID.class),r.getObject("warehouse",UUID.class),r.getString("warehouse_name"),r.getString("code"),r.getString("name"),r.getObject("expiration_date",LocalDate.class),r.getBigDecimal("available"),r.getBigDecimal("unit_cost"),r.getString("currency")),tenant,ids.toArray(UUID[]::new));var out=new ArrayList<Stock>();for(var r:rows){String key=r.item()+":"+r.location()+":"+r.expiration();var qty=r.available().min(allowed.getOrDefault(key,BigDecimal.ZERO));if(qty.signum()>0){out.add(new Stock(r.layer(),r.item(),r.location(),r.warehouse(),r.warehouseName(),r.locationName(),r.name(),r.expiration(),qty,r.cost(),r.currency()));allowed.merge(key,qty.negate(),BigDecimal::add);}}return out;
 }
 String hash(Object data){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(data).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 boolean replay(UUID tenant,UUID plan,UUID request,String kind,Object body){if(request==null)throw invalid("A request identity is required.");var previous=jdbc.queryForList("SELECT plan_id,kind,request_hash FROM fba_replenishment_actions WHERE tenant_id=? AND request_id=?",tenant,request);if(previous.isEmpty())return false;var p=previous.getFirst();if(!plan.equals(p.get("plan_id"))||!kind.equals(p.get("kind"))||!hash(body).equals(p.get("request_hash")))throw invalid("Request identity was already used for a different action.");return true;}
 void audit(UUID tenant,UUID plan,UUID request,String kind,Object body,String email,Object result){jdbc.update("INSERT INTO fba_replenishment_actions(tenant_id,plan_id,request_id,kind,request_hash,actor,result) VALUES (?,?,?,?,?,?,?::jsonb)",tenant,plan,request,kind,hash(body),actor(email),json.writeValueAsString(result));}
 void revision(Plan p,long expected){if(p.revision()!=expected)throw invalid("This plan changed. Reload before continuing.");}
 /** Keep source-confirmed products immutable while saving packing and Back navigation. */
 @Transactional public Plan checkpoint(UUID tenant,UUID connection,UUID id,String email,Checkpoint command){
  if(command==null||command.step()<0||command.step()>3)throw invalid("Amazon placement and transport are unavailable in Local UAT.");
  lock(tenant);var p=read(tenant,connection,id,true);if(replay(tenant,id,command.requestId(),"CHECKPOINT",command))return p;revision(p,command.revision());
  if(command.cartons()==null&&command.preparationByOrigin()==null&&command.transportByOrigin()!=null)return transportCheckpoint(tenant,connection,email,p,command);
  if(!p.status().equals("ALLOCATED"))throw invalid("Confirm allocation before saving packing; dispatched or cancelled plans cannot change packing.");
  var payload=new LinkedHashMap<String,Object>(p.checkpoint());var cartons=command.cartons();
  if(cartons!=null){
   if(cartons.size()>1000)throw invalid("Use up to 1,000 cartons.");
   if(command.step()>=3){FbaPackingManifest.validate(cartons,expectedPacking(p),new HashSet<>(packageNames.names(tenant)));validateExpirations(tenant,p,cartons);}
   var packing=new LinkedHashMap<String,Object>();Object oldPacking=payload.get("packing");if(oldPacking instanceof Map<?,?> old)old.forEach((k,v)->packing.put(k.toString(),v));packing.put("cartons",cartons);packing.put("validated",command.step()>=3);payload.put("packing",packing);
  }else if(command.step()>=3){
   throw invalid("Confirm actual carton measurements and contents before placement.");
  }
  if(command.transportByOrigin()!=null){
   if(command.transportByOrigin().size()>100)throw invalid("Too many source transport inputs.");
   for(var entry:command.transportByOrigin().entrySet()){if(entry.getKey()==null||entry.getValue()==null||entry.getValue().readyToShipAt()==null||entry.getValue().readyToShipAt().isBefore(Instant.now().minusSeconds(300))||entry.getValue().readyToShipAt().isAfter(Instant.now().plusSeconds(366L*86400)))throw invalid("Enter a current ready-to-ship date within the next year.");FbaInboundCheckpoints.originUnits(p,entry.getKey());}
   var packing=new LinkedHashMap<String,Object>();Object oldPacking=payload.get("packing");if(oldPacking instanceof Map<?,?> old)old.forEach((k,v)->packing.put(k.toString(),v));packing.put("transportByOrigin",command.transportByOrigin());payload.put("packing",packing);
  }
  if(command.preparationByOrigin()!=null){
   if(command.preparationByOrigin().size()>100)throw invalid("Too many preparation origins.");
   for(var origin:command.preparationByOrigin().entrySet()){if(origin.getKey()==null)throw invalid("Choose a valid preparation origin.");var units=FbaInboundCheckpoints.originUnits(p,origin.getKey());if(origin.getValue()==null||origin.getValue().size()>500)throw invalid("Choose preparation for up to 500 SKUs per origin.");for(var entry:origin.getValue().entrySet()){var preparation=entry.getValue();if(!units.containsKey(entry.getKey())||preparation==null||!preparation.confirmed()||preparation.prepOwner()==null||preparation.labelOwner()==null||!Set.of("AMAZON","SELLER","NONE").contains(preparation.prepOwner())||!Set.of("AMAZON","SELLER","NONE").contains(preparation.labelOwner()))throw invalid("Explicitly confirm preparation and labeling owners for this origin's SKU. Amazon eligibility still requires validation.");}}
   var packing=new LinkedHashMap<String,Object>();Object oldPacking=payload.get("packing");if(oldPacking instanceof Map<?,?> old)old.forEach((k,v)->packing.put(k.toString(),v));packing.put("preparationByOrigin",command.preparationByOrigin());payload.put("packing",packing);
  }
  requirePreparationMutable(p.checkpoint(),payload);
  if(!json.valueToTree(p.checkpoint().get("packing")).equals(json.valueToTree(payload.get("packing"))))invalidateInboundPacking(payload);
  String encoded=json.writeValueAsString(payload);if(encoded.length()>1048576)throw invalid("Packing details exceed this plan's limit.");
  jdbc.update("UPDATE fba_replenishment_plans SET step=?,checkpoint=?::jsonb,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",command.step(),encoded,actor(email),tenant,connection,id);
  audit(tenant,id,command.requestId(),"CHECKPOINT",command,email,Map.of("step",command.step(),"message",command.step()==3?"Packing saved; Amazon creation unavailable in Local UAT":"Progress saved"));return read(tenant,connection,id,false);
 }
 Plan transportCheckpoint(UUID tenant,UUID connection,String email,Plan plan,Checkpoint command){
  if(!Set.of("ALLOCATED","PART_DISPATCHED").contains(plan.status())||command.transportByOrigin().isEmpty()||command.transportByOrigin().size()>100)throw invalid("Choose a confirmed origin with remaining transport to prepare.");
  var payload=new LinkedHashMap<String,Object>(plan.checkpoint());var packing=new LinkedHashMap<String,Object>();if(payload.get("packing") instanceof Map<?,?> old)old.forEach((k,v)->packing.put(k.toString(),v));var ready=new LinkedHashMap<String,Object>();if(packing.get("transportByOrigin") instanceof Map<?,?> old)old.forEach((k,v)->ready.put(k.toString(),v));var origins=new LinkedHashMap<String,Object>();if(payload.get("inboundOrigins") instanceof Map<?,?> old)old.forEach((k,v)->origins.put(k.toString(),v));
  for(var entry:command.transportByOrigin().entrySet()){UUID warehouse=entry.getKey();var input=entry.getValue();if(warehouse==null||input==null||input.readyToShipAt()==null||!input.readyToShipAt().isAfter(Instant.now())||input.readyToShipAt().isAfter(Instant.now().plusSeconds(366L*86400)))throw invalid("Enter a future ready-to-ship date within the next year.");FbaInboundCheckpoints.originUnits(plan,warehouse);var state=FbaInboundCheckpoints.checkpoint(plan,new FbaInboundWorkflow.Scope(tenant,tenant,connection,plan.id()),warehouse,json);var origin=state.origin();if(origin.attempts().values().stream().anyMatch(a->a.status()==FbaInboundWorkflow.Status.PENDING||a.operation()==FbaInboundAdapter.Operation.CANCEL&&a.status()==FbaInboundWorkflow.Status.SUCCEEDED))throw invalid("Reconcile pending or cancelled Amazon activity before changing ready time.");
   var confirmed=new HashSet<String>();origin.attempts().values().stream().filter(a->a.operation()==FbaInboundAdapter.Operation.CONFIRM_TRANSPORT&&a.status()==FbaInboundWorkflow.Status.SUCCEEDED&&a.packingVersion()==origin.packingVersion()).forEach(a->confirmed.add(a.childId()));if(!origin.children().isEmpty()&&confirmed.containsAll(origin.children().keySet()))throw invalid("All child transportation is already confirmed.");
   var options=new LinkedHashMap<>(state.options());options.values().removeIf(o->o.confirmation()==FbaInboundAdapter.Operation.CONFIRM_TRANSPORT&&!confirmed.contains(o.childId()));var quotes=new LinkedHashMap<>(origin.quotes());quotes.values().removeIf(q->q.confirmation()==FbaInboundAdapter.Operation.CONFIRM_TRANSPORT&&!options.containsKey(q.optionId()));var changed=new FbaInboundWorkflow.Origin(origin.scope(),warehouse,origin.packingVersion(),origin.amazonPlanId(),origin.attempts(),origin.children(),quotes,origin.documents());if(origins.containsKey(warehouse.toString()))origins.put(warehouse.toString(),new FbaInboundOrchestration.Checkpoint(changed,state.acknowledgements(),options));ready.put(warehouse.toString(),input);
  }
  packing.put("transportByOrigin",ready);payload.put("packing",packing);if(!origins.isEmpty())payload.put("inboundOrigins",origins);jdbc.update("UPDATE fba_replenishment_plans SET checkpoint=?::jsonb,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(payload),actor(email),tenant,connection,plan.id());audit(tenant,plan.id(),command.requestId(),"CHECKPOINT",command,email,Map.of("message","Ready time updated; unconfirmed transport options require refresh. Packing and confirmed children unchanged."));return read(tenant,connection,plan.id(),false);
 }
 void validateExpirations(UUID tenant,Plan plan,List<FbaPackingManifest.Carton> cartons){
  var required=new HashSet<UUID>();sourceProfiles(tenant,plan.allocations().stream().map(a->(UUID)a.get("account_catalog_item_id")).filter(Objects::nonNull).distinct().toList()).stream().filter(profile->Boolean.TRUE.equals(profile.expirationRequired())).forEach(profile->required.add(profile.item()));
  for(var allocation:plan.allocations())if("ACTIVE".equals(allocation.get("reservation_status"))&&required.contains(allocation.get("account_catalog_item_id"))&&allocation.get("expiration_date")==null)throw invalid("Expiration is required for an allocated product. Correct its source inventory evidence before packing.");
  var dates=new HashMap<String,Set<LocalDate>>();var missing=new HashSet<String>();
  for(var allocation:plan.allocations())if("ACTIVE".equals(allocation.get("reservation_status"))){String key=allocation.get("warehouse_id")+":"+allocation.get("seller_sku");Object raw=allocation.get("expiration_date");if(raw==null)missing.add(key);else dates.computeIfAbsent(key,k->new HashSet<>()).add(raw instanceof LocalDate date?date:LocalDate.parse(raw.toString()));}
  for(var carton:cartons)for(String sku:carton.contents().keySet()){String key=carton.warehouse()+":"+sku;var source=dates.getOrDefault(key,Set.of());if(source.size()>1||!source.isEmpty()&&missing.contains(key))throw invalid("Different source expirations for "+sku+". Select matching-expiration source stock before packing; no expiry is inferred.");LocalDate supplied=carton.expirations()==null?null:carton.expirations().get(sku);if(!source.isEmpty()&&!source.contains(supplied)||source.isEmpty()&&supplied!=null)throw invalid("Confirm the exact source expiration for "+sku+" on each carton.");}
 }
 void requirePreparationMutable(Map<String,Object> oldPayload,Map<String,Object> newPayload){
  var before=json.valueToTree(oldPayload.get("packing")).path("preparationByOrigin");var after=json.valueToTree(newPayload.get("packing")).path("preparationByOrigin");Object raw=oldPayload.get("inboundOrigins");if(!(raw instanceof Map<?,?> origins))return;
  for(var entry:origins.entrySet())if(!before.path(entry.getKey().toString()).equals(after.path(entry.getKey().toString()))){var origin=json.readValue(json.writeValueAsString(entry.getValue()),FbaInboundOrchestration.Checkpoint.class).origin();if(origin.amazonPlanId()!=null||origin.attempts().values().stream().anyMatch(a->a.status()==FbaInboundWorkflow.Status.PENDING))throw invalid("Preparation and labeling owners are fixed after Amazon creation begins. Reconcile cancellation and recreate the origin before changing them.");}
 }
 @SuppressWarnings("unchecked") void invalidateInboundPacking(Map<String,Object> payload){
  Object raw=payload.get("inboundOrigins");if(!(raw instanceof Map<?,?> origins)||origins.isEmpty())return;var changed=new LinkedHashMap<String,Object>();var histories=new LinkedHashMap<String,Object>();Object oldHistories=payload.get("inboundOriginHistory");if(oldHistories instanceof Map<?,?> old)old.forEach((k,v)->histories.put(k.toString(),v));
  for(var entry:origins.entrySet()){var state=json.readValue(json.writeValueAsString(entry.getValue()),FbaInboundOrchestration.Checkpoint.class);var history=new ArrayList<Object>();Object old=histories.get(entry.getKey().toString());if(old instanceof List<?> values)history.addAll(values);if(history.size()>=100)throw invalid("This plan has reached its packing revision history limit. Preserve it and use a new plan.");history.add(state);histories.put(entry.getKey().toString(),history);changed.put(entry.getKey().toString(),new FbaInboundOrchestration.Checkpoint(FbaInboundWorkflow.packingChanged(state.origin()),state.acknowledgements(),Map.of()));}
  payload.put("inboundOrigins",changed);payload.put("inboundOriginHistory",histories);
 }
 @SuppressWarnings("unchecked") void retireInboundOrigins(Map<String,Object> payload){Object raw=payload.remove("inboundOrigins");if(!(raw instanceof Map<?,?> origins))return;var histories=new LinkedHashMap<String,Object>();Object prior=payload.get("inboundOriginHistory");if(prior instanceof Map<?,?> old)old.forEach((k,v)->histories.put(k.toString(),v));for(var entry:origins.entrySet()){var values=new ArrayList<Object>();Object old=histories.get(entry.getKey().toString());if(old instanceof List<?> list)values.addAll(list);if(values.size()>=100)throw invalid("Preserve this origin history and use a new replenishment plan.");values.add(entry.getValue());histories.put(entry.getKey().toString(),values);}payload.put("inboundOriginHistory",histories);}
 void requireNoRemoteCommitments(Plan plan){
  Object raw=plan.checkpoint().get("inboundOrigins");var settledCancelled=new HashSet<String>();
  if(raw instanceof Map<?,?> origins)for(var entry:origins.entrySet()){var state=json.readValue(json.writeValueAsString(entry.getValue()),FbaInboundOrchestration.Checkpoint.class);var origin=state.origin();boolean pending=origin.attempts().values().stream().anyMatch(a->a.status()==FbaInboundWorkflow.Status.PENDING);boolean cancelled=origin.attempts().values().stream().anyMatch(a->a.operation()==FbaInboundAdapter.Operation.CANCEL&&a.status()==FbaInboundWorkflow.Status.SUCCEEDED);
   if(pending||origin.amazonPlanId()!=null&&!cancelled||!origin.children().isEmpty()&&!cancelled)throw invalid("Amazon activity is pending or committed. Reconcile Amazon cancellation before releasing source reservations.");if(cancelled)settledCancelled.add(entry.getKey().toString());
  }
  if(plan.allocations().stream().anyMatch(a->a.get("amazon_shipment_id")!=null&&!settledCancelled.contains(a.get("warehouse_id").toString())))throw invalid("This plan has a linked Amazon shipment. Reconcile its cancellation before releasing any remaining reservation.");
 }
 /** Allocation rows are component receipt layers: use sum(eaches)/each-per-SKU, never sum duplicated sku_units. */
 static Map<String,Integer> expectedPacking(Plan plan){
  var eaches=new HashMap<String,Integer>();var ratios=new HashMap<String,Integer>();
  for(var a:plan.allocations())if("ACTIVE".equals(a.get("reservation_status"))){String key=a.get("warehouse_id")+":"+a.get("seller_sku")+":"+a.get("account_catalog_item_id");
   // read() joins the reservation product below; historic released allocations are excluded.
   int ratio=((Number)a.get("each_per_sku")).intValue();if(ratios.putIfAbsent(key,ratio)!=null&&ratios.get(key)!=ratio)throw invalid("Allocation composition changed; review sources.");
   eaches.merge(key,((Number)a.get("quantity")).intValue()-((Number)a.get("dispatched")).intValue(),Math::addExact);
  }
  var expected=new HashMap<String,Integer>();for(var e:eaches.entrySet()){int ratio=ratios.get(e.getKey());if(e.getValue()<=0||e.getValue()%ratio!=0)throw invalid("Packing requires complete reserved SKU units.");String originSku=e.getKey().substring(0,e.getKey().lastIndexOf(':'));int units=e.getValue()/ratio;Integer prior=expected.putIfAbsent(originSku,units);if(prior!=null&&prior!=units)throw invalid("Bundle components do not match the packing quantity.");}
  if(expected.isEmpty())throw invalid("No active source allocation remains.");return expected;
 }
 @Transactional public Plan release(UUID tenant,UUID connection,UUID id,String email,Cancel command){
  lock(tenant);var p=read(tenant,connection,id,true);if(replay(tenant,id,command.requestId(),"RELEASE",command))return p;revision(p,command.revision());
  if(!p.status().equals("ALLOCATED")||p.allocations().stream().anyMatch(a->((Number)a.get("dispatched")).intValue()>0||a.get("amazon_shipment_id")!=null))throw invalid("Only undispatched allocations without an Amazon shipment can return to source selection.");
  if(command.reason()==null||command.reason().isBlank()||command.reason().length()>500)throw invalid("Enter a reason for releasing these sources.");
  requireNoRemoteCommitments(p);requireEditableSlot(tenant,connection,id);
  jdbc.update("UPDATE order_inventory_reservations SET status='RELEASED',updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND fba_plan_id=? AND status='ACTIVE'",tenant,connection,id);
  var payload=new LinkedHashMap<String,Object>(p.checkpoint());retireInboundOrigins(payload);payload.put("packing",Map.of());jdbc.update("UPDATE fba_replenishment_plans SET status='DRAFT',step=1,checkpoint=?::jsonb,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(payload),actor(email),tenant,connection,id);
  audit(tenant,id,command.requestId(),"RELEASE",command,email,Map.of("message","Sources released; products retained for reassignment. On-hand unchanged."));return read(tenant,connection,id,false);
 }
 @Transactional public Plan allocate(UUID tenant,UUID connection,UUID id,String email,Allocate command){
  lock(tenant);var p=read(tenant,connection,id,true);if(replay(tenant,id,command.requestId(),"ALLOCATE",command))return p;revision(p,command.revision());if(!p.status().equals("DRAFT"))throw invalid("This plan is already allocated or closed.");if(command.choices()==null||command.choices().isEmpty()||command.choices().size()>5000)throw invalid("Choose source stock before confirming allocation.");
  var lines=lines(p);if(lines.isEmpty())throw invalid("Add SKU quantities first.");var components=components(tenant,connection,lines);var lots=new HashMap<UUID,Stock>();stock(tenant,components.stream().map(Component::item).distinct().toList()).forEach(s->lots.put(s.layer(),s));
  var used=new HashMap<UUID,Integer>();var perOrigin=new HashMap<String,Map<UUID,Integer>>();var originSku=new HashMap<String,String>();var originWarehouse=new HashMap<String,UUID>();var unique=new HashSet<String>();
  for(var choice:command.choices()){var lot=lots.get(choice.layer());if(lot==null||choice.each()<1||!unique.add(choice.sku()+":"+choice.layer())||components.stream().noneMatch(c->c.sku().equals(choice.sku())&&c.item().equals(lot.item())))throw invalid("Source stock or SKU mapping changed. Refresh availability.");if(BigDecimal.valueOf(used.merge(choice.layer(),choice.each(),Math::addExact)).compareTo(lot.available())>0)throw invalid("Selected stock exceeds unreserved quantity.");String origin=lot.warehouse()+":"+choice.sku();originSku.put(origin,choice.sku());originWarehouse.put(origin,lot.warehouse());perOrigin.computeIfAbsent(origin,k->new HashMap<>()).merge(lot.item(),choice.each(),Math::addExact);}
  var totalSku=new HashMap<String,Integer>();var originUnits=new HashMap<String,Integer>();for(var group:perOrigin.entrySet()){String sku=originSku.get(group.getKey());Integer units=null;for(var c:components)if(c.sku().equals(sku)){int each=group.getValue().getOrDefault(c.item(),0);if(each%c.quantity()!=0||each==0)throw invalid("Every origin must contain complete mapped units for "+sku+".");int n=each/c.quantity();if(units!=null&&units!=n)throw invalid("Bundle components do not match at this origin: "+sku);units=n;}originUnits.put(group.getKey(),units);totalSku.merge(sku,units,Math::addExact);}
  for(var line:lines)if(totalSku.getOrDefault(line.sku(),0)!=line.units())throw invalid("Allocate exactly "+line.units()+" complete units for "+line.sku()+".");
  for(var choice:command.choices()){var lot=lots.get(choice.layer());UUID reservation=UUID.randomUUID(),allocation=UUID.randomUUID();int eachPerSku=components.stream().filter(c->c.sku().equals(choice.sku())&&c.item().equals(lot.item())).findFirst().orElseThrow().quantity();
   jdbc.update("INSERT INTO order_inventory_reservations(id,tenant_id,marketplace_connection_id,fba_plan_id,account_catalog_item_id,location_id,expiration_date,quantity,cost_layer_id,unit_cost,cost_currency) VALUES (?,?,?,?,?,?,?,?,?,?,?)",reservation,tenant,connection,id,lot.item(),lot.location(),lot.expiration(),choice.each(),lot.layer(),lot.cost(),lot.currency());
   jdbc.update("INSERT INTO fba_replenishment_allocations(id,tenant_id,plan_id,marketplace_connection_id,warehouse_id,seller_sku,cost_layer_id,each_per_sku,sku_units,quantity,reservation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",allocation,tenant,id,connection,lot.warehouse(),choice.sku(),lot.layer(),eachPerSku,originUnits.get(lot.warehouse()+":"+choice.sku()),choice.each(),reservation);
  }
  jdbc.update("UPDATE fba_replenishment_plans SET status='ALLOCATED',step=2,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",actor(email),tenant,id);audit(tenant,id,command.requestId(),"ALLOCATE",command,email,Map.of("message","Stock reserved; on-hand unchanged"));return read(tenant,connection,id,false);
 }
 @Transactional public Plan cancel(UUID tenant,UUID connection,UUID id,String email,Cancel command){lock(tenant);var p=read(tenant,connection,id,true);if(replay(tenant,id,command.requestId(),"CANCEL",command))return p;revision(p,command.revision());requireNoRemoteCommitments(p);if(command.reason()==null||command.reason().isBlank()||command.reason().length()>500)throw invalid("Enter a release/cancellation reason.");jdbc.update("UPDATE order_inventory_reservations SET status='RELEASED',updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND fba_plan_id=? AND status='ACTIVE'",tenant,connection,id);jdbc.update("UPDATE fba_replenishment_plans SET status='CANCELLED',revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",actor(email),tenant,id);audit(tenant,id,command.requestId(),"CANCEL",command,email,Map.of("message","Undispatched reservations released; dispatched stock unchanged"));return read(tenant,connection,id,false);}
 @Transactional public Plan dispatch(UUID tenant,UUID connection,UUID id,String email,Dispatch command){
  lock(tenant);var p=read(tenant,connection,id,true);if(replay(tenant,id,command.requestId(),"DISPATCH",command))return p;revision(p,command.revision());
  if(!Set.of("ALLOCATED","PART_DISPATCHED").contains(p.status()))throw invalid("Confirm source allocation before dispatch.");
  if(command.lines()==null||command.lines().isEmpty()||command.lines().size()>500||command.reason()==null||command.reason().isBlank()||command.reason().length()>500)throw invalid("Choose dispatched SKU quantities and enter a dispatch reference.");
  var requests=new HashMap<String,Integer>();for(var line:command.lines())if(line.sku()==null||line.warehouse()==null||line.units()<1||requests.put(line.warehouse()+":"+line.sku(),line.units())!=null)throw invalid("Enter one positive quantity per origin/SKU.");
  var allocations=jdbc.queryForList("""
   SELECT a.*,r.account_catalog_item_id item,r.location_id,r.expiration_date,r.unit_cost,r.cost_currency,r.quantity reserved,r.status reservation_status
   FROM fba_replenishment_allocations a JOIN order_inventory_reservations r ON r.tenant_id=a.tenant_id AND r.id=a.reservation_id
   WHERE a.tenant_id=? AND a.marketplace_connection_id=? AND a.plan_id=? AND r.status='ACTIVE' ORDER BY a.warehouse_id,a.seller_sku,r.expiration_date NULLS LAST,a.created_at,a.id FOR UPDATE OF a,r
   """,tenant,connection,id);
  // Origin+SKU is not a destination-child identity. Never guess which child was dispatched.
  for(String request:requests.keySet())if(allocations.stream().filter(a->request.equals(a.get("warehouse_id")+":"+a.get("seller_sku"))).map(a->a.get("amazon_shipment_id")).distinct().count()>1)throw invalid("This origin/SKU spans multiple destination shipments. Dispatch each child explicitly after shipment reconciliation.");
  // Amazon creation/printing does not deduct stock. Dispatch requires a verified imported/created child identity.
  for(var a:allocations)if(requests.containsKey(a.get("warehouse_id")+":"+a.get("seller_sku"))){String shipment=(String)a.get("amazon_shipment_id");if(shipment==null||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?)",Boolean.class,tenant,connection,shipment)))throw invalid("Confirm and reconcile the Amazon shipment before marking this origin dispatched.");}
  var needed=new HashMap<String,Integer>();for(var entry:requests.entrySet()){
   var group=allocations.stream().filter(a->entry.getKey().equals(a.get("warehouse_id")+":"+a.get("seller_sku"))).toList();if(group.isEmpty())throw invalid("Origin/SKU is not allocated.");var items=new HashMap<UUID,Integer>();for(var a:group)items.put((UUID)a.get("item"),((Number)a.get("each_per_sku")).intValue());
   for(var item:items.entrySet()){int each=Math.multiplyExact(entry.getValue(),item.getValue());int remaining=group.stream().filter(a->item.getKey().equals(a.get("item"))&&"ACTIVE".equals(a.get("reservation_status"))).mapToInt(a->((Number)a.get("reserved")).intValue()).sum();if(each>remaining)throw invalid("Dispatch exceeds reserved complete SKU units.");needed.put(entry.getKey()+":"+item.getKey(),each);}
  }
  for(var a:allocations){String key=a.get("warehouse_id")+":"+a.get("seller_sku")+":"+a.get("item");int required=needed.getOrDefault(key,0);if(required<=0||!"ACTIVE".equals(a.get("reservation_status")))continue;int reserved=((Number)a.get("reserved")).intValue(),take=Math.min(required,reserved);UUID allocation=(UUID)a.get("id"),entry=UUID.randomUUID();
   BigDecimal physical=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ?",BigDecimal.class,tenant,a.get("item"),a.get("location_id"),a.get("expiration_date"));
   BigDecimal allReserved=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ? AND status='ACTIVE'",BigDecimal.class,tenant,a.get("item"),a.get("location_id"),a.get("expiration_date"));if(physical.compareTo(allReserved)<0)throw invalid("Physical stock no longer covers reservations. Reconcile this location before dispatch.");
   jdbc.update("""
    INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,marketplace_connection_id,entry_type,quantity,location_id,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
    VALUES (?,?,?,?,'FBA_DISPATCH',?,?,?,?,?,'FBA_REPLENISHMENT',?,now(),?,?,?,?)
    """,entry,tenant,a.get("item"),connection,-take,a.get("location_id"),a.get("expiration_date"),a.get("unit_cost"),a.get("cost_currency"),id,"fba-dispatch:"+command.requestId()+":"+allocation,command.reason().trim(),actor(email),a.get("unit_cost")==null?"PROVISIONAL":"FINAL");
   if(take==reserved)jdbc.update("UPDATE order_inventory_reservations SET status='SHIPPED',updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?",tenant,a.get("reservation_id"),id);
   else jdbc.update("UPDATE order_inventory_reservations SET quantity=quantity-?,updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?",take,tenant,a.get("reservation_id"),id);
   jdbc.update("UPDATE fba_replenishment_allocations SET dispatched=dispatched+? WHERE tenant_id=? AND id=?",take,tenant,allocation);needed.put(key,required-take);
  }
  if(needed.values().stream().anyMatch(n->n!=0))throw invalid("Complete component allocation changed; dispatch was not applied.");
  boolean remaining=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM order_inventory_reservations WHERE tenant_id=? AND fba_plan_id=? AND status='ACTIVE')",Boolean.class,tenant,id));
  jdbc.update("UPDATE fba_replenishment_plans SET status=?,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND id=?",remaining?"PART_DISPATCHED":"DISPATCHED",actor(email),tenant,id);audit(tenant,id,command.requestId(),"DISPATCH",command,email,Map.of("message","Dispatched quantities deducted and matching reservations released atomically"));return read(tenant,connection,id,false);
 }
}
