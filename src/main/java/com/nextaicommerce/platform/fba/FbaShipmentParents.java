package com.nextaicommerce.platform.fba;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read projection of verified Amazon identities. Never decorates immutable imported shipment payloads. */
@Repository
public class FbaShipmentParents {
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public FbaShipmentParents(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public record Plan(UUID id,String name,JsonNode checkpoint){}
 public record Parent(String id,String name,UUID localPlan,UUID warehouse,String child,String city,String postal,java.time.Instant created,Set<String> declaredChildren,String evidenceRevision){
  public Parent(String id,String name,UUID localPlan,UUID warehouse,String child){this(id,name,localPlan,warehouse,child,null,null,null,Set.of(),null);}
  public String displayName(){return name==null||name.isBlank()||name.equals(id)?"Shipments":name;}
 }
 public record Projection(Map<String,Parent> links,Set<String> ambiguous){}
 @Transactional(readOnly=true) public Projection read(UUID tenant,UUID store,Collection<String> imported){
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  if(imported.isEmpty())return new Projection(Map.of(),Set.of());
  var plans=jdbc.query("SELECT id,name,checkpoint::text FROM fba_replenishment_plans WHERE tenant_id=? AND marketplace_connection_id=? AND (jsonb_exists(checkpoint,'inboundOrigins') OR jsonb_exists(checkpoint,'inboundOriginHistory'))",(r,n)->new Plan(r.getObject(1,UUID.class),r.getString(2),json.readTree(r.getString(3))),tenant,store);
  var known=project(tenant,store,plans,new HashSet<>(imported));
  record Evidence(String market,JsonNode payload){}
  var documents=jdbc.query("SELECT d.payload::text,c.marketplace_identifier FROM amazon_source_documents d JOIN marketplace_connections c ON c.tenant_id=d.tenant_id AND c.id=d.marketplace_connection_id AND c.channel='AMAZON' WHERE d.tenant_id=? AND d.marketplace_connection_id=? AND d.source_type='FBA_PARENT_RELATIONSHIPS' AND d.normalized_at IS NOT NULL ORDER BY d.received_at,d.id",(r,n)->new Evidence(r.getString(2),json.readTree(r.getString(1))),tenant,store);
  for(var document:documents)known=merge(known,projectDocuments(tenant,store,document.market(),List.of(document.payload()),new HashSet<>(imported)));
  return known;

 }
 static Projection project(UUID tenant,UUID store,List<Plan> plans,Set<String> imported){
  var links=new LinkedHashMap<String,Parent>();var ambiguous=new HashSet<String>();
  for(var plan:plans){var origins=new ArrayList<Map.Entry<String,JsonNode>>();plan.checkpoint().path("inboundOrigins").properties().forEach(e->origins.add(Map.entry(e.getKey(),e.getValue())));
   plan.checkpoint().path("inboundOriginHistory").properties().forEach(e->{if(e.getValue().isArray())e.getValue().forEach(old->origins.add(Map.entry(e.getKey(),old)));});
   for(var entry:origins){var origin=entry.getValue().path("origin");var scope=origin.path("scope");
    if(!tenant.toString().equals(scope.path("tenant").asText())||!tenant.toString().equals(scope.path("account").asText())||!store.toString().equals(scope.path("connection").asText())||!plan.id().toString().equals(scope.path("replenishment").asText())||!entry.getKey().equals(origin.path("warehouse").asText()))continue;
    String parent=origin.path("amazonPlanId").asText("");boolean verified=false;for(var attempt:origin.path("attempts"))if("CREATE".equals(attempt.path("operation").asText())&&"SUCCEEDED".equals(attempt.path("status").asText())&&!attempt.path("amazonOperationId").asText("").isBlank())verified=true;
    if(parent.isBlank()||!verified)continue;UUID warehouse;try{warehouse=UUID.fromString(entry.getKey());}catch(IllegalArgumentException invalid){continue;}
    for(var childEntry:origin.path("children").properties()){
     var child=childEntry.getValue();String childId=child.path("shipmentId").asText("");if(!childEntry.getKey().equals(childId))continue;
     var refs=new LinkedHashSet<String>();if(imported.contains(childId))refs.add(childId);String confirmation=child.path("confirmationId").asText("");if(!confirmation.isBlank()&&imported.contains(confirmation))refs.add(confirmation);
     if(refs.size()>1){ambiguous.addAll(refs);continue;}if(refs.isEmpty())continue;String reference=refs.iterator().next();var value=new Parent(parent,plan.name(),plan.id(),warehouse,childId,null,null,null,Set.copyOf(origin.path("children").propertyNames()),digest(origin.toString()));Parent old=links.putIfAbsent(reference,value);
     if(old!=null&&(!old.id().equals(parent)||!old.localPlan().equals(plan.id())||!old.warehouse().equals(warehouse)||!old.child().equals(childId)||!old.declaredChildren().equals(value.declaredChildren())))ambiguous.add(reference);
    }
   }
  }ambiguous.forEach(links::remove);return new Projection(Map.copyOf(links),Set.copyOf(ambiguous));
 }
 /** Source documents are immutable API read evidence; listing, plan, and every declared child must agree. */
 static Projection projectDocuments(UUID tenant,UUID store,String marketplace,List<JsonNode> documents,Set<String> imported){
  var result=new Projection(Map.of(),Set.of());for(var doc:documents){
   if(!tenant.toString().equals(doc.path("tenant").asText())||!store.toString().equals(doc.path("store").asText())||marketplace==null||!marketplace.equals(doc.path("marketplace").asText())||doc.path("listingRequestId").asText("").isBlank()||doc.path("completedAt").asText("").isBlank())continue;
   String market=doc.path("marketplace").asText();var listed=new HashMap<String,JsonNode>();for(var entry:doc.path("listing").path("inboundPlans"))if(singleMarket(entry,market))listed.put(entry.path("inboundPlanId").asText(),entry);
   for(var entry:doc.path("plans")){var plan=entry.path("plan");String id=plan.path("inboundPlanId").asText("");if(!id.matches("[A-Za-z0-9-]{38}")||!listed.containsKey(id)||!singleMarket(plan,market)||entry.path("planRequestId").asText("").isBlank())continue;
    var expected=new HashSet<String>();boolean valid=plan.path("shipments").isArray()&&entry.path("children").isArray();for(var child:plan.path("shipments")){String childId=child.path("shipmentId").asText("");if(!childId.matches("[A-Za-z0-9-]{38}")||!expected.add(childId))valid=false;}
    var returned=new HashSet<String>();for(var response:entry.path("children")){String childId=response.path("shipment").path("shipmentId").asText("");if(response.path("requestId").asText("").isBlank()||!expected.contains(childId)||!returned.add(childId))valid=false;}
    if(!valid||!expected.equals(returned))continue;
    var links=new LinkedHashMap<String,Parent>();var ambiguous=new HashSet<String>();for(var response:entry.path("children")){var child=response.path("shipment");String childId=child.path("shipmentId").asText();var refs=new LinkedHashSet<String>();if(imported.contains(childId))refs.add(childId);String confirmation=child.path("shipmentConfirmationId").asText("");if(!confirmation.isBlank()&&imported.contains(confirmation))refs.add(confirmation);
     if(refs.size()>1){ambiguous.addAll(refs);continue;}if(refs.isEmpty())continue;String reference=refs.iterator().next();var value=new Parent(id,plan.path("name").asText(""),null,null,childId,text(plan.path("sourceAddress"),"city"),text(plan.path("sourceAddress"),"postalCode"),instant(plan.path("createdAt").asText()),Set.copyOf(expected),digest(entry.toString()));var old=links.putIfAbsent(reference,value);if(old!=null&&(!old.child().equals(childId)||!old.declaredChildren().equals(value.declaredChildren())))ambiguous.add(reference);
    }ambiguous.forEach(links::remove);result=merge(result,new Projection(Map.copyOf(links),Set.copyOf(ambiguous)));
   }
  }return result;
 }
 static String digest(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static String text(JsonNode node,String key){String value=node.path(key).asText("");return value.isBlank()?null:value;}
 private static java.time.Instant instant(String value){try{return java.time.Instant.parse(value);}catch(Exception invalid){return null;}}
 private static boolean singleMarket(JsonNode plan,String market){return plan.path("marketplaceIds").isArray()&&plan.path("marketplaceIds").size()==1&&market.equals(plan.path("marketplaceIds").get(0).asText());}
 static Projection merge(Projection first,Projection second){var links=new LinkedHashMap<>(first.links());var ambiguous=new HashSet<>(first.ambiguous());ambiguous.addAll(second.ambiguous());second.links().forEach((reference,parent)->{var old=links.putIfAbsent(reference,parent);if(old!=null&&(!old.id().equals(parent.id())||!old.child().equals(parent.child())||!old.declaredChildren().equals(parent.declaredChildren())))ambiguous.add(reference);});ambiguous.forEach(links::remove);return new Projection(Map.copyOf(links),Set.copyOf(ambiguous));}
 public record Cases(Long each,Long cases,Long remainder,Long perCase){}
 static Cases cases(long skuUnits,BigDecimal componentEach,BigDecimal masterCase){
  if(skuUnits<0||componentEach==null||componentEach.signum()<=0||componentEach.stripTrailingZeros().scale()>0)return new Cases(null,null,null,null);
  long each;try{each=Math.multiplyExact(skuUnits,componentEach.longValueExact());}catch(ArithmeticException invalid){return new Cases(null,null,null,null);}
  if(masterCase==null||masterCase.signum()<=0||masterCase.stripTrailingZeros().scale()>0)return new Cases(each,null,null,null);
  try{long per=masterCase.longValueExact();return new Cases(each,each/per,each%per,per);}catch(ArithmeticException invalid){return new Cases(each,null,null,null);}
 }
}
