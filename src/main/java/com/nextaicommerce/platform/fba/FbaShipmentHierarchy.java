package com.nextaicommerce.platform.fba;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nextaicommerce.platform.web.TablePaging;

/** Parent pagination is computed before loading child costs/content; legacy records remain standalone. */
@Service
public class FbaShipmentHierarchy {
 private final FbaReadRepository shipments;private final FbaShipmentParents parents;
 public FbaShipmentHierarchy(FbaReadRepository shipments,FbaShipmentParents parents){this.shipments=shipments;this.parents=parents;}
 public record Page(FbaReadRepository.ShipmentPage shipments,Map<String,FbaShipmentParents.Parent> parents,Set<String> ambiguous,Map<String,Summary> summaries,Map<String,String> depletion){}
 @org.springframework.beans.factory.annotation.Autowired private FbaWorkspaceRepository workspace;
 static String depletionSummary(Collection<String> members,Map<String,FbaWorkspaceRepository.DepletionState> states,boolean complete){
  if(!complete||members.isEmpty()||members.stream().anyMatch(id->!states.containsKey(id)))return "Needs review";
  long decreased=members.stream().filter(id->states.get(id).depleted()).count();
  if(decreased==members.size())return "Decreased";
  if(decreased>0)return "Partially decreased";
  return members.stream().anyMatch(id->states.get(id).needsReview())?"Needs review":"";
 }
 public record Summary(int shipments,int matching,Long skus,Long shipped,Long received,String origin,String postal,String city,String destination,String status,String dates,FbaReadRepository.ShipmentCosts costs){public String label(FbaShipmentParents.Parent parent){return !parent.displayName().equals("Shipments")?parent.displayName():parent.created()!=null?"Shipments · Created "+java.time.format.DateTimeFormatter.ofPattern("MMM d, uuuu",Locale.US).withZone(java.time.ZoneOffset.UTC).format(parent.created()):dates.equals("—")?"Shipments":"Shipments · "+dates;}}
 static Summary summarize(List<FbaReadRepository.Shipment> rows,Map<String,FbaReadRepository.CompletedShipmentItems> identities,int matching){
  boolean complete=!rows.isEmpty()&&rows.stream().allMatch(r->r.itemsComplete()&&identities.containsKey(r.id())&&identities.get(r.id()).shipped()==r.shipped()&&identities.get(r.id()).received()==r.received());var skuSet=new HashSet<String>();boolean skuKnown=complete;
  for(var row:rows){var evidence=identities.get(row.id());var known=evidence==null?Set.<String>of():evidence.skus();if(evidence==null||known.size()!=row.skuCount())skuKnown=false;else skuSet.addAll(known);}
  long sent=rows.stream().mapToLong(FbaReadRepository.Shipment::shipped).sum(),received=rows.stream().mapToLong(FbaReadRepository.Shipment::received).sum();
  String postal=common(rows.stream().map(FbaReadRepository.Shipment::shipFromPostalCode).toList(),"Multiple origins"),city=common(rows.stream().map(FbaReadRepository.Shipment::shipFromCity).toList(),"Multiple origins");
  var costs=complete?FbaReadRepository.ShipmentCosts.aggregate(rows.stream().map(FbaReadRepository.Shipment::costs).toList(),rows.size()):new FbaReadRepository.ShipmentCosts(null,null,null,sent);
  var dates=rows.stream().map(FbaReadRepository.Shipment::importedCreation).filter(Objects::nonNull).sorted().toList();String date="—";if(dates.size()==rows.size()&&!dates.isEmpty()){var format=java.time.format.DateTimeFormatter.ofPattern("MM/dd/uuuu").withZone(java.time.ZoneOffset.UTC);date=format.format(dates.getFirst());String last=format.format(dates.getLast());if(!last.equals(date))date+=" – "+last;}
  return new Summary(rows.size(),matching,skuKnown?(long)skuSet.size():null,complete?sent:null,complete?received:null,common(rows.stream().map(FbaReadRepository.Shipment::shipFrom).toList(),"Multiple origins"),postal,city,common(rows.stream().map(FbaReadRepository.Shipment::destination).toList(),"Multiple"),common(rows.stream().map(FbaReadRepository.Shipment::status).toList(),"Mixed statuses"),date,costs);
 }
 private static String common(List<String> values,String mixed){if(values.isEmpty()||values.stream().anyMatch(v->v==null||v.isBlank()))return "—";return values.stream().distinct().count()==1?values.getFirst():mixed;}
 @Transactional(readOnly=true) public Page page(UUID tenant,UUID store,String query,int requestedPage,int requestedSize,String email,String status,UUID warehouse,boolean removed){
  var references=shipments.matchingShipmentReferences(tenant,store,query,email,status,warehouse,removed);FbaShipmentParents.Projection projection;List<String> allEligible=references;
  if(query!=null&&!query.isBlank()){
   var eligible=shipments.matchingShipmentReferences(tenant,store,"",email,status,warehouse,removed);allEligible=eligible;projection=parents.read(tenant,store,eligible);var direct=new HashSet<>(references);String term=query.trim().toLowerCase(Locale.ROOT);var verified=projection.links();
   references=eligible.stream().filter(id->{var parent=verified.get(id);return direct.contains(id)||parent!=null&&(parent.id().toLowerCase(Locale.ROOT).contains(term)||Objects.toString(parent.name(),"").toLowerCase(Locale.ROOT).contains(term));}).toList();
  }else projection=parents.read(tenant,store,references);
  var groups=group(references,projection.links());int size=TablePaging.size(requestedSize),page=Math.min(Math.max(0,requestedPage),Math.max(0,(groups.size()-1)/size));
  var visible=groups.values().stream().skip((long)page*size).limit(size).flatMap(Collection::stream).toList();var loaded=shipments.shipmentReferences(tenant,store,visible,email,removed).rows();var byId=new HashMap<String,FbaReadRepository.Shipment>();loaded.forEach(r->byId.put(r.id(),r));var rows=visible.stream().map(byId::get).filter(Objects::nonNull).toList();
  var depletion=new LinkedHashMap<String,String>();var depletionStates=workspace==null?Map.<String,FbaWorkspaceRepository.DepletionState>of():workspace.states(tenant,store);var verifiedProjection=projection;var summaries=new LinkedHashMap<String,Summary>();var selectedParents=rows.stream().map(r->verifiedProjection.links().get(r.id())).filter(Objects::nonNull).map(FbaShipmentParents.Parent::id).collect(java.util.stream.Collectors.toSet());
  if(!selectedParents.isEmpty()){
   var currentRefs=shipments.matchingShipmentReferences(tenant,store,"",email,"",null,false);var hiddenRefs=shipments.matchingShipmentReferences(tenant,store,"",email,"",null,true);var eligible=new LinkedHashSet<String>(currentRefs);eligible.addAll(hiddenRefs);var allLinks=parents.read(tenant,store,eligible).links();var fullRefs=eligible.stream().filter(id->allLinks.containsKey(id)&&selectedParents.contains(allLinks.get(id).id())).toList();
   if(fullRefs.size()>1000)throw new IllegalArgumentException("This shipping plan exceeds the 1000-child display budget.");
   var full=new ArrayList<FbaReadRepository.Shipment>();var cached=new HashMap<String,FbaReadRepository.Shipment>();loaded.forEach(r->cached.put(r.id(),r));for(String ref:fullRefs)if(cached.containsKey(ref))full.add(cached.get(ref));
   for(boolean hidden:List.of(false,true)){var missing=fullRefs.stream().filter(id->!cached.containsKey(id)&&hiddenRefs.contains(id)==hidden).toList();if(!missing.isEmpty())full.addAll(shipments.shipmentReferences(tenant,store,missing,email,hidden).rows());}
   var identities=shipments.completedShipmentItems(tenant,store,fullRefs);
   for(String id:selectedParents){var members=full.stream().filter(r->allLinks.containsKey(r.id())&&allLinks.get(r.id()).id().equals(id)).toList();int matched=(int)rows.stream().filter(r->verifiedProjection.links().containsKey(r.id())&&verifiedProjection.links().get(r.id()).id().equals(id)).count();var parentInfo=allLinks.values().stream().filter(p->p.id().equals(id)).findFirst().orElse(null);boolean membershipComplete=parentInfo==null||parentInfo.declaredChildren().isEmpty()||parentInfo.declaredChildren().equals(members.stream().map(r->allLinks.get(r.id()).child()).collect(java.util.stream.Collectors.toSet()));depletion.put(id,depletionSummary(members.stream().map(FbaReadRepository.Shipment::id).toList(),depletionStates,membershipComplete));summaries.put(id,membershipComplete?summarize(members,identities,matched):new Summary(parentInfo.declaredChildren().size(),matched,null,null,null,"—","—","—","—","Membership needs review","—",new FbaReadRepository.ShipmentCosts(null,null,null,0)));}
  }
  return new Page(new FbaReadRepository.ShipmentPage(rows,groups.size(),page,size),projection.links(),projection.ambiguous(),summaries,depletion);
 }
 static LinkedHashMap<String,List<String>> group(List<String> ordered,Map<String,FbaShipmentParents.Parent> links){var groups=new LinkedHashMap<String,List<String>>();for(String id:ordered){var p=links.get(id);String key=p==null?"shipment:"+id:"plan:"+p.id();groups.computeIfAbsent(key,k->new ArrayList<>()).add(id);}return groups;}
}
