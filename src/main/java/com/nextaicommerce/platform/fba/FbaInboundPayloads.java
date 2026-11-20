package com.nextaicommerce.platform.fba;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.orders.PackingPackageNames;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.*;
import static com.nextaicommerce.platform.fba.FbaInboundAdapter.Operation.*;

/** Server-owned SP-API 2024-03-20 inputs. Schema source (checked 2026-11-17):
 * https://github.com/amzn/selling-partner-api-models/blob/main/models/fulfillment-inbound-api-model/fulfillmentInbound_2024-03-20.json
 * Only persisted source allocations, managed addresses and confirmed actual cartons are used.
 * No network calls; unknown prep/packing provenance fails before an outbound attempt is persisted.
 */
@Component
public class FbaInboundPayloads implements FbaInboundOrchestration.Payloads {
 private final FbaReplenishmentPlans plans;private final JdbcTemplate jdbc;private final CatalogRepository catalog;
 private final PackingPackageNames packages;private final ObjectMapper json;
 public FbaInboundPayloads(FbaReplenishmentPlans plans,JdbcTemplate jdbc,CatalogRepository catalog,PackingPackageNames packages,ObjectMapper json){this.plans=plans;this.jdbc=jdbc;this.catalog=catalog;this.packages=packages;this.json=json;}
 @Override @Transactional(readOnly=true)
 public Map<String,Integer> expectedSkuUnits(Scope scope,UUID warehouse){return expected(load(scope),warehouse);}
 FbaReplenishmentPlans.Plan load(Scope scope){
  if(!scope.tenant().equals(scope.account()))throw new IllegalArgumentException("Account and tenant scope must match.");
  var plan=plans.get(scope.tenant(),scope.connection(),scope.replenishment());
  if(!Set.of("ALLOCATED","PART_DISPATCHED").contains(plan.status()))throw new IllegalStateException("Confirm source allocation before preparing an Amazon request.");return plan;
 }
 static Map<String,Integer> expected(FbaReplenishmentPlans.Plan plan,UUID warehouse){
  var out=FbaInboundCheckpoints.originUnits(plan,warehouse);for(var entry:out.entrySet())if(entry.getKey().isBlank()||entry.getKey().length()>255||entry.getValue()<1||entry.getValue()>500000)throw new IllegalStateException("Amazon requires 1–500,000 complete units per SKU.");return out;
 }
 @Override @Transactional(readOnly=true)
 public String build(Scope scope,UUID warehouse,FbaInboundOrchestration.Checkpoint state,FbaInboundOrchestration.Command command){
  if(!scope.equals(state.origin().scope())||!warehouse.equals(state.origin().warehouse()))throw new IllegalArgumentException("Inbound request belongs to another origin.");
  if(command.operation()==CANCEL){if(!scope.tenant().equals(scope.account()))throw new IllegalArgumentException("Account scope mismatch.");plans.get(scope.tenant(),scope.connection(),scope.replenishment());return "{}";}
  var plan=load(scope);var units=expected(plan,warehouse);boolean dispatched=plan.allocations().stream().anyMatch(a->warehouse.equals(a.get("warehouse_id"))&&((Number)a.get("dispatched")).intValue()>0);
  if(dispatched){if(command.operation()==CREATE||command.operation()==PACKING_INFORMATION)throw new IllegalStateException("Dispatched origin packing cannot be recreated or amended.");requireIssuedBasis(scope,warehouse,plan,state,units);}
  var cartons=dispatched?List.<FbaPackingManifest.Carton>of():cartons(scope,warehouse,plan,units);var expiration=dispatched?Map.<String,LocalDate>of():sourceExpirations(scope,warehouse,units.keySet());if(!dispatched)validateCartonExpirations(cartons,expiration);
  Object body=switch(command.operation()){
   case CREATE->{requireLeadTimes(scope,units.keySet());yield create(scope,warehouse,plan,units,expiration);}
   case PACKING_INFORMATION->packing(state,cartons,expiration,preparations(plan,warehouse,units.keySet()));
   case TRANSPORT_OPTIONS->transport(warehouse,plan,state);
   case CONFIRM_TRANSPORT->{if(command.childId()==null||!state.origin().children().containsKey(command.childId()))throw new IllegalArgumentException("Choose a verified child shipment in this origin.");var selected=state.options().get(command.optionId());if(selected==null||selected.confirmation()!=CONFIRM_TRANSPORT||!Objects.equals(selected.childId(),command.childId()))throw new IllegalArgumentException("Transportation option does not belong to this shipment.");yield Map.of("transportationSelections",List.of(Map.of("shipmentId",command.childId(),"transportationOptionId",selected.id(),"contactInformation",contact(source(scope,warehouse)))));}
   case PACKING_OPTIONS,CONFIRM_PACKING,PLACEMENT_OPTIONS,CONFIRM_PLACEMENT,CANCEL->Map.of();
   case STATUS->throw new IllegalArgumentException("Status reads use the reconciliation path.");
  };
  return json.writeValueAsString(body);
 }
 void requireIssuedBasis(Scope scope,UUID warehouse,FbaReplenishmentPlans.Plan plan,FbaInboundOrchestration.Checkpoint state,Map<String,Integer> expected){
  var saved=FbaInboundCheckpoints.checkpoint(plan,scope,warehouse,json);var origin=state.origin();if(origin.amazonPlanId()==null||!origin.equals(saved.origin()))throw new IllegalStateException("Reconcile the current issued origin and packing version before continuing.");
  var packing=json.valueToTree(plan.checkpoint().get("packing"));if(!packing.path("validated").asBoolean(false))throw new IllegalStateException("Issued packing evidence is missing.");var cartons=new ArrayList<FbaPackingManifest.Carton>();for(JsonNode raw:packing.path("cartons")){var c=json.treeToValue(raw,FbaPackingManifest.Carton.class);if(warehouse.equals(c.warehouse()))cartons.add(c);}var quantities=new TreeMap<String,Integer>();expected.forEach((sku,n)->quantities.put(warehouse+":"+sku,n));
  // Names are retained from the already validated immutable packing, even if the managed package is renamed later.
  FbaPackingManifest.validate(cartons,quantities,cartons.stream().map(FbaPackingManifest.Carton::packageName).collect(java.util.stream.Collectors.toSet()));var children=new TreeMap<String,Integer>();origin.children().values().forEach(c->c.skuUnits().forEach((sku,n)->children.merge(sku,n,Math::addExact)));if(!children.equals(expected))throw new IllegalStateException("Issued child contents differ from the original confirmed source allocation.");
 }
 @SuppressWarnings("unchecked") List<FbaPackingManifest.Carton> cartons(Scope scope,UUID warehouse,FbaReplenishmentPlans.Plan plan,Map<String,Integer> units){
  var packing=json.valueToTree(plan.checkpoint().get("packing"));if(!packing.path("validated").asBoolean(false))throw new IllegalStateException("Confirm actual carton measurements and contents first.");
  var cartons=new ArrayList<FbaPackingManifest.Carton>();for(JsonNode raw:packing.path("cartons")){var carton=json.treeToValue(raw,FbaPackingManifest.Carton.class);if(warehouse.equals(carton.warehouse()))cartons.add(carton);}
  var expected=new TreeMap<String,Integer>();units.forEach((sku,n)->expected.put(warehouse+":"+sku,n));
  FbaPackingManifest.validate(cartons,expected,new HashSet<>(packages.names(scope.tenant())));return List.copyOf(cartons);
 }
 Map<String,LocalDate> sourceExpirations(Scope scope,UUID warehouse,Set<String> expected){
  var dates=new HashMap<String,Set<LocalDate>>();jdbc.query("""
   SELECT a.seller_sku,r.expiration_date FROM fba_replenishment_allocations a
   JOIN order_inventory_reservations r ON r.tenant_id=a.tenant_id AND r.id=a.reservation_id
   WHERE a.tenant_id=? AND a.marketplace_connection_id=? AND a.plan_id=? AND a.warehouse_id=? AND r.status='ACTIVE'
   ORDER BY a.seller_sku,r.expiration_date NULLS LAST
   """,(org.springframework.jdbc.core.RowCallbackHandler)r->dates.computeIfAbsent(r.getString(1),k->new HashSet<>()).add(r.getObject(2,LocalDate.class)),scope.tenant(),scope.connection(),scope.replenishment(),warehouse);
  var out=new HashMap<String,LocalDate>();for(String sku:expected){var values=dates.get(sku);if(values==null||values.isEmpty())throw new IllegalStateException("Source reservation provenance changed. Refresh allocation.");if(values.size()!=1)throw new IllegalStateException("Choose one verified expiration per SKU and origin; mixed source expirations need explicit carton provenance.");LocalDate value=values.iterator().next();if(value!=null){if(value.isBefore(LocalDate.now(ZoneOffset.UTC)))throw new IllegalStateException("Allocated stock has expired. Correct sources before Amazon creation.");out.put(sku,value);}}
  return Map.copyOf(out);
 }
 static void validateCartonExpirations(List<FbaPackingManifest.Carton> cartons,Map<String,LocalDate> source){for(var carton:cartons)for(String sku:carton.contents().keySet()){LocalDate entered=carton.expirations()==null?null:carton.expirations().get(sku);if(!Objects.equals(source.get(sku),entered))throw new IllegalStateException("Confirm carton expiration against its source reservation; unknown or mixed provenance cannot be sent to Amazon.");}}
 CatalogRepository.WarehouseDetails source(Scope scope,UUID warehouse){
  var address=catalog.warehouseDetails(scope.tenant(),warehouse);if(address==null||!"ACTIVE".equals(address.status()))throw new IllegalStateException("Choose an active managed source warehouse.");return address;
 }
 Map<String,Object> create(Scope scope,UUID warehouse,FbaReplenishmentPlans.Plan plan,Map<String,Integer> units,Map<String,LocalDate> expiration){
  var w=source(scope,warehouse);var contact=contact(w);var address=new LinkedHashMap<String,Object>();address.put("name",contact.get("name"));address.put("phoneNumber",contact.get("phoneNumber"));address.put("addressLine1",text(w.addressLine1(),180,"warehouse street address"));address.put("city",text(w.city(),30,"warehouse city"));address.put("postalCode",text(w.postalCode(),32,"warehouse postal code"));String country=text(w.countryCode(),2,"warehouse country");if(!country.matches("[A-Z]{2}"))throw new IllegalArgumentException("Warehouse country must be a two-letter ISO code.");address.put("countryCode",country);
  optional(address,"addressLine2",w.addressLine2(),60);optional(address,"stateOrProvinceCode",w.stateOrProvince(),64);optional(address,"companyName",w.name(),50);
  String market=jdbc.queryForObject("SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='AMAZON'",String.class,scope.tenant(),scope.connection());market=text(market,20,"destination marketplace");
  var prep=preparations(plan,warehouse,units.keySet());var items=units.entrySet().stream().map(e->item(e.getKey(),e.getValue(),expiration.get(e.getKey()),prep.get(e.getKey()))).toList();var body=new LinkedHashMap<String,Object>();body.put("destinationMarketplaces",List.of(market));body.put("items",items);body.put("sourceAddress",address);if(plan.name().length()<=40)body.put("name",plan.name());return body;
 }
 Map<String,FbaReplenishmentPlans.Preparation> preparations(FbaReplenishmentPlans.Plan plan,UUID warehouse,Set<String> skus){
  var saved=json.valueToTree(plan.checkpoint().get("packing")).path("preparationByOrigin").path(warehouse.toString());var result=new TreeMap<String,FbaReplenishmentPlans.Preparation>();
  for(String sku:skus){var raw=saved.path(sku);if(!raw.isObject())throw new IllegalStateException("Confirm preparation and labeling owners for every SKU at this origin.");var prep=json.treeToValue(raw,FbaReplenishmentPlans.Preparation.class);if(!prep.confirmed()||prep.prepOwner()==null||prep.labelOwner()==null||!Set.of("AMAZON","SELLER","NONE").contains(prep.prepOwner())||!Set.of("AMAZON","SELLER","NONE").contains(prep.labelOwner()))throw new IllegalStateException("Confirm preparation and labeling owners for every SKU at this origin.");result.put(sku,prep);}return Map.copyOf(result);
 }
 void requireLeadTimes(Scope scope,Set<String> skus){
  var policies=new HashMap<String,FbaReplenishmentMath.Policy>();jdbc.query("SELECT scope_key,configuration::text FROM fba_replenishment_policies WHERE tenant_id=?",(org.springframework.jdbc.core.RowCallbackHandler)r->policies.put(r.getString(1),json.readValue(r.getString(2),FbaReplenishmentMath.Policy.class)),scope.tenant());
  for(String sku:skus){var brands=jdbc.queryForList("""
   SELECT DISTINCT coalesce(g.brand,'') brand FROM marketplace_sku_mappings m
   JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
   JOIN global_catalog_products g ON g.id=a.global_product_id
   WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.marketplace_sku=? AND m.status='ACTIVE'
   """,String.class,scope.tenant(),scope.connection(),sku);String brand=brands.size()==1?brands.getFirst():"";
   var policy=policies.getOrDefault("SKU:"+scope.connection()+":"+sku,policies.getOrDefault("BRAND:"+brand,policies.getOrDefault("ACCOUNT",FbaReplenishmentMath.Policy.defaults())));if(!policy.leadTimesConfirmed())throw new IllegalStateException("Set and confirm preparation, transit and receiving lead times before Amazon creation.");
  }
 }
 static Map<String,Object> item(String sku,int quantity,LocalDate expiration,FbaReplenishmentPlans.Preparation preparation){var value=new LinkedHashMap<String,Object>();value.put("msku",sku);value.put("quantity",quantity);value.put("prepOwner",preparation.prepOwner());value.put("labelOwner",preparation.labelOwner());if(expiration!=null)value.put("expiration",expiration.toString());return value;}
 Object packing(FbaInboundOrchestration.Checkpoint state,List<FbaPackingManifest.Carton> cartons,Map<String,LocalDate> expiration,Map<String,FbaReplenishmentPlans.Preparation> preparations){
  var option=confirmed(state,CONFIRM_PACKING);JsonNode source=json.readTree(option.source());var groupIds=new LinkedHashSet<String>();for(JsonNode id:source.path("packingGroups"))groupIds.add(text(id.asText(),38,"verified packing group"));if(groupIds.isEmpty())throw new IllegalStateException("The accepted Amazon packing option has no verified groups.");
  var grouped=new LinkedHashMap<String,List<Object>>();var assigned=new HashMap<String,Map<String,Integer>>();
  for(var carton:cartons){String group;if(groupIds.size()==1)group=groupIds.iterator().next();else {var matches=new ArrayList<String>();for(String id:groupIds){JsonNode contents=source.path("verifiedPackingGroupItems").path(id);if(contents.isObject()&&carton.contents().keySet().stream().allMatch(contents::has))matches.add(id);}if(matches.size()!=1)throw new IllegalStateException("Carton contents must match exactly one verified Amazon packing group. Repack and review the groups.");group=matches.getFirst();}
   var items=new ArrayList<Object>();for(var e:carton.contents().entrySet()){LocalDate date=expiration.get(e.getKey());JsonNode entered=json.valueToTree(carton).path("expirations").path(e.getKey());if(date!=null&&(!entered.isTextual()||!date.equals(LocalDate.parse(entered.asText()))))throw new IllegalStateException("Confirm the actual source expiration for every SKU in its carton.");items.add(item(e.getKey(),e.getValue(),date,preparations.get(e.getKey())));assigned.computeIfAbsent(group,k->new TreeMap<>()).merge(e.getKey(),e.getValue(),Math::addExact);}
   String dim=carton.dimensionUnit().equals("in")?"IN":"CM";String weightUnit;BigDecimal weight=carton.weight();switch(carton.weightUnit()){case "lb"->weightUnit="LB";case "kg"->weightUnit="KG";case "oz"->{weightUnit="LB";weight=weight.divide(new BigDecimal("16"),8,RoundingMode.HALF_UP);}case "g"->{weightUnit="KG";weight=weight.divide(new BigDecimal("1000"),8,RoundingMode.HALF_UP);}default->throw new IllegalArgumentException("Use supported actual packed weight units.");}
   grouped.computeIfAbsent(group,k->new ArrayList<>()).add(Map.of("contentInformationSource","BOX_CONTENT_PROVIDED","dimensions",Map.of("length",carton.length(),"width",carton.width(),"height",carton.height(),"unitOfMeasurement",dim),"weight",Map.of("unit",weightUnit,"value",weight),"quantity",1,"items",items));
  }
  if(groupIds.size()>1)for(String group:groupIds){JsonNode evidence=source.path("verifiedPackingGroupItems").path(group);var expected=new TreeMap<String,Integer>();if(!evidence.isObject())throw new IllegalStateException("Retrieve verified packing-group contents first.");evidence.properties().forEach(e->{if(!e.getValue().isIntegralNumber()||!e.getValue().canConvertToInt()||e.getValue().intValue()<1)throw new IllegalStateException("Invalid verified group contents.");expected.put(e.getKey(),e.getValue().intValue());});if(!expected.equals(assigned.get(group)))throw new IllegalStateException("Carton quantities do not match the accepted Amazon packing group.");}
  return Map.of("packageGroupings",grouped.entrySet().stream().map(e->Map.of("packingGroupId",e.getKey(),"boxes",e.getValue())).toList());
 }
 Object transport(UUID warehouse,FbaReplenishmentPlans.Plan plan,FbaInboundOrchestration.Checkpoint state){
  JsonNode raw=json.valueToTree(plan.checkpoint().get("packing")).path("transportByOrigin").path(warehouse.toString()).path("readyToShipAt");if(!raw.isTextual())throw new IllegalStateException("Enter the actual ready-to-ship date and time for this origin.");Instant ready;try{ready=Instant.parse(raw.asText()).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);}catch(RuntimeException e){throw new IllegalArgumentException("Enter a valid ready-to-ship date and time.");}if(!ready.isAfter(Instant.now()))throw new IllegalStateException("Ready-to-ship time has passed. Review it before generating transportation options.");
  var placement=confirmed(state,CONFIRM_PLACEMENT);if(state.origin().children().isEmpty())throw new IllegalStateException("Reconcile verified Amazon child shipments first.");var contact=contact(source(state.origin().scope(),warehouse));var configs=state.origin().children().keySet().stream().filter(child->!FbaInboundCheckpoints.completed(state.origin(),CONFIRM_TRANSPORT,child)).sorted().map(child->Map.of("shipmentId",child,"readyToShipWindow",Map.of("start",ready.toString()),"contactInformation",contact)).toList();return Map.of("placementOptionId",placement.id(),"shipmentTransportationConfigurations",configs);
 }
 static FbaInboundOrchestration.Option confirmed(FbaInboundOrchestration.Checkpoint state,FbaInboundAdapter.Operation operation){var ids=new HashSet<String>();state.origin().attempts().values().stream().filter(a->a.operation()==operation&&a.status()==Status.SUCCEEDED&&a.packingVersion()==state.origin().packingVersion()).forEach(a->ids.add(a.optionId()));if(ids.size()!=1)throw new IllegalStateException("Reconcile one current accepted Amazon option first.");var option=state.options().get(ids.iterator().next());if(option==null||option.confirmation()!=operation||option.packingVersion()!=state.origin().packingVersion())throw new IllegalStateException("The accepted option metadata is stale. Refresh it first.");return option;}
 static Map<String,String> contact(CatalogRepository.WarehouseDetails w){if(w.contacts()==null||w.contacts().isEmpty())throw new IllegalStateException("Add the managed warehouse's contact name and phone first.");var c=w.contacts().getFirst();return Map.of("name",text(c.fullName(),50,"warehouse contact name"),"phoneNumber",text(c.phone(),20,"warehouse contact phone"));}
 static String text(String value,int max,String label){if(value==null||value.isBlank()||value.trim().length()>max)throw new IllegalArgumentException("Enter a valid "+label+" (up to "+max+" characters).");return value.trim();}
 static void optional(Map<String,Object> target,String key,String value,int max){if(value!=null&&!value.isBlank())target.put(key,text(value,max,key));}
}
