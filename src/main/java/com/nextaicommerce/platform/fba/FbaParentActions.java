package com.nextaicommerce.platform.fba;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nextaicommerce.platform.web.MemberAccessService;

/** Full verified parent scope; never accepts a browser's eligible/visible child subset. */
@Service
public class FbaParentActions {
 private final JdbcTemplate jdbc;private final FbaShipmentParents parents;private final FbaBatchDeduction batches;private final FbaDepletionRepository stock;private final MemberAccessService access;private final FbaReadRepository read;
 public FbaParentActions(JdbcTemplate jdbc,FbaShipmentParents parents,FbaBatchDeduction batches,FbaDepletionRepository stock,MemberAccessService access,FbaReadRepository read){this.jdbc=jdbc;this.parents=parents;this.batches=batches;this.stock=stock;this.access=access;this.read=read;}
 public record Group(String parent,String name,List<String> shipments,String identity,String revision,int decreased,List<String> remaining){
  public Group(String parent,String name,List<String> shipments,String identity,String revision,int decreased){this(parent,name,shipments,identity,revision,decreased,decreased==0?shipments:List.of());}
 }
 public record Request(String parent,UUID requestId,String identity,String groupRevision,String revision,List<FbaBatchDeduction.Choice> choices){}
 public record Visibility(String parent,String identity,String revision,boolean removed){}
 public record Review(Group group,List<FbaBatchDeduction.Plan> plans,List<String> blockers,String revision){}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private void authorize(UUID tenant,UUID store,String email){var m=access.current(tenant,email);if(!access.activePlatformAdministrator(email)&&(m==null||!Set.of("OWNER","ADMIN","OPERATOR").contains(m.role())||m.restricted()&&!m.stores().contains(store)))throw new org.springframework.security.access.AccessDeniedException("Account or store access changed.");}
 private void lock(UUID tenant,UUID store){scope(tenant);jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,tenant.toString());
  // Serialize group identity changes and source imports with the confirmation snapshot.
  jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND id=? FOR UPDATE",tenant,store);
  jdbc.queryForList("SELECT id FROM fba_replenishment_plans WHERE tenant_id=? AND marketplace_connection_id=? FOR SHARE",tenant,store);
  jdbc.queryForList("SELECT id FROM amazon_source_documents WHERE tenant_id=? AND marketplace_connection_id=? FOR SHARE",tenant,store);
 }
 @Transactional(readOnly=true) public Group group(UUID tenant,UUID store,String parent){scope(tenant);if(parent==null||parent.isBlank()||parent.length()>100)throw new IllegalArgumentException("Choose a verified shipping plan.");
  var imported=jdbc.queryForList("SELECT shipment_id FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? ORDER BY shipment_id LIMIT 50001",String.class,tenant,store);if(imported.size()>50000)throw new IllegalArgumentException("This store exceeds the shipping-plan review budget.");
  var projection=parents.read(tenant,store,imported);var group=verified(parent,projection);
  var states=jdbc.queryForList("SELECT s.shipment_id,md5(s.raw_payload::text) source_revision,d.id::text decreased_id,d.source_revision decrease_revision FROM amazon_fba_shipments s LEFT JOIN fba_shipment_depletions d ON d.tenant_id=s.tenant_id AND d.marketplace_connection_id=s.marketplace_connection_id AND d.shipment_id=s.shipment_id WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.shipment_id=ANY(?::text[]) ORDER BY s.shipment_id",tenant,store,group.shipments().toArray(String[]::new));
  if(states.size()!=group.shipments().size())throw new IllegalArgumentException("A shipment is missing. Review the complete shipping plan again.");
  int decreased=(int)states.stream().filter(v->v.get("decreased_id")!=null).count();var remaining=states.stream().filter(v->v.get("decreased_id")==null).map(v->(String)v.get("shipment_id")).sorted().toList();return new Group(parent,group.name(),group.shipments(),group.identity(),FbaShipmentParents.digest(group.identity()+states),decreased,remaining);
 }
 static Group verified(String parent,FbaShipmentParents.Projection projection){
  var links=projection.links().entrySet().stream().filter(e->e.getValue().id().equals(parent)).sorted(Map.Entry.comparingByKey()).toList();if(links.isEmpty())throw new IllegalArgumentException("Shipping plan is unavailable or its relationship needs review.");
  var expected=links.getFirst().getValue().declaredChildren();var actual=new HashSet<String>();var evidence=new TreeSet<String>();
  for(var entry:links){var p=entry.getValue();if(expected.isEmpty()||!expected.equals(p.declaredChildren())||p.evidenceRevision()==null||!actual.add(p.child()))throw new IllegalArgumentException("The full shipping-plan membership is not verified.");evidence.add(p.evidenceRevision());}
  if(!actual.equals(expected))throw new IllegalArgumentException("Some shipments are missing or ambiguous. Review the complete shipping plan first.");
  var ids=links.stream().map(Map.Entry::getKey).toList();if(ids.size()>1000)throw new IllegalArgumentException("This shipping plan exceeds the 1000-child review budget.");
  return new Group(parent,links.getFirst().getValue().displayName(),ids,FbaShipmentParents.digest(parent+ids+new TreeSet<>(expected)+evidence),null,0);
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public FbaReadRepository.ParentItems contents(UUID tenant,UUID store,String parent,String query,int page){return read.parentItems(tenant,store,group(tenant,store,parent).shipments(),query,page);}
 @Transactional(readOnly=true) public Review preview(UUID tenant,UUID store,String parent){var g=group(tenant,store,parent);if(g.remaining().isEmpty())return new Review(g,List.of(),List.of("All shipments are already decreased."),"");if(g.remaining().size()>25)return new Review(g,List.of(),List.of("Review at most 25 remaining shipments together. No subset will be decreased."),"");var review=batches.preview(tenant,store,g.remaining());return new Review(g,review.plans(),review.blockers(),review.revision());}
 @Transactional public List<UUID> confirm(UUID tenant,UUID store,String email,Request r){
  if(r==null||r.requestId()==null||r.identity()==null||r.groupRevision()==null||r.choices()==null||r.choices().stream().anyMatch(c->c==null||c.shipment()==null))throw new IllegalArgumentException("Review the complete shipping plan first.");
  authorize(tenant,store,email);lock(tenant,store);var g=group(tenant,store,r.parent());if(!g.identity().equals(r.identity()))throw new IllegalArgumentException("Shipping-plan membership changed. Review all shipments again.");
  for(String id:g.shipments())stock.lockShipment(tenant,store,id);g=group(tenant,store,r.parent());if(!g.identity().equals(r.identity()))throw new IllegalArgumentException("Shipping-plan membership changed. Review again.");
  var selected=r.choices().stream().map(FbaBatchDeduction.Choice::shipment).sorted().toList();
  var context=Map.<String,Object>of("parent",r.parent(),"identity",r.identity(),"review",r.groupRevision());
  boolean replay=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_shipment_depletions WHERE tenant_id=? AND marketplace_connection_id=? AND approval->'batch'->>'id'=? AND approval#>>'{batch,parent,parent}'=? AND approval#>>'{batch,parent,identity}'=?)",Boolean.class,tenant,store,r.requestId().toString(),r.parent(),r.identity()));
  if(replay){if(selected.isEmpty()||new HashSet<>(selected).size()!=selected.size()||!g.shipments().containsAll(selected))throw new IllegalArgumentException("Review every shipment from the saved parent decrease.");}
  else{
   if(!g.revision().equals(r.groupRevision()))throw new IllegalArgumentException("A shipment changed or was already decreased. Review the remaining plan again.");
   if(g.remaining().isEmpty())throw new IllegalArgumentException("All shipments are already decreased.");
   if(!selected.equals(g.remaining()))throw new IllegalArgumentException("Every remaining shipment in the verified plan must be included exactly once.");
  }
  // Existing REQUIRED batch transaction and exact mapped-allocation checks commit all remaining children or none.
  return batches.confirm(tenant,store,email,r.requestId(),r.revision(),r.choices(),context);
 }
 @Transactional public void visibility(UUID tenant,UUID store,String email,Visibility r){if(r==null)throw new IllegalArgumentException("Review the complete shipping plan first.");authorize(tenant,store,email);lock(tenant,store);var g=group(tenant,store,r.parent());for(String id:g.shipments())stock.lockShipment(tenant,store,id);g=group(tenant,store,r.parent());if(!g.identity().equals(r.identity())||!g.revision().equals(r.revision()))throw new IllegalArgumentException("Shipping plan changed. Review all shipments again.");for(String id:g.shipments())stock.removed(tenant,store,id,email,r.removed());}
}
