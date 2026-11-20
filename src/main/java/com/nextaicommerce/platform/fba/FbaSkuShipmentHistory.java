package com.nextaicommerce.platform.fba;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Three cached shipment identities for one exact seller SKU; never a shipment-wide total. */
@Repository
public class FbaSkuShipmentHistory {
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaShipmentParents parents;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaDepletionRepository presentation;
 private final JdbcTemplate jdbc;
 public FbaSkuShipmentHistory(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Shipment(String shipment,String postal,Instant date,Integer sent,Integer amazonShipped,String source,String note){}
 @Transactional(readOnly=true) public List<Shipment> recent(UUID tenant,UUID store,String marketplace,String sku){
  if(sku==null||sku.isBlank()||sku.length()>240)throw new IllegalArgumentException("Choose a valid seller SKU.");
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  return rows(SQL,new Object[]{tenant,store,marketplace,sku});
 }
 private List<Shipment> rows(String sql,Object[] args){
  return jdbc.query(sql,(r,n)->{
   Integer raw=(Integer)r.getObject("quantity_shipped");boolean identity=r.getBoolean("identity_matches"),verified=r.getBoolean("verified");
   Integer corrected=(Integer)r.getObject("corrected"),local=(Integer)r.getObject("local_quantity");int origins=r.getInt("origins");
   var chosen=choose(identity,origins,local,corrected,verified,raw);
   return new Shipment(r.getString("shipment_id"),r.getString("postal"),r.getTimestamp("activity_at")==null?null:r.getTimestamp("activity_at").toInstant(),chosen.sent(),verified?raw:null,chosen.source(),chosen.note());
  },args);
 }
 public record ProductQuantity(String code,String name,FbaShipmentParents.Cases quantity){}
 public record Group(String parent,String name,boolean verified,String postal,Long sent,List<ProductQuantity> products,List<Shipment> children,Map<String,List<ProductQuantity>> childProducts){}
 @Transactional(readOnly=true) public List<Group> groups(UUID tenant,UUID store,String marketplace,String sku){
  if(sku==null||sku.isBlank()||sku.length()>240)throw new IllegalArgumentException("Choose a valid seller SKU.");
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  var references=jdbc.queryForList("""
   SELECT s.shipment_id FROM amazon_fba_shipments s JOIN amazon_fba_shipment_items i
    ON i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id
   JOIN marketplace_connections c ON c.tenant_id=s.tenant_id AND c.id=s.marketplace_connection_id
   WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND c.channel='AMAZON' AND c.marketplace_identifier=? AND i.seller_sku=?
   ORDER BY coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) DESC NULLS LAST,s.shipment_id DESC LIMIT 50001
   """,String.class,tenant,store,marketplace,sku);
  if(references.size()>50000)throw new IllegalArgumentException("Shipment history is too large to group safely.");
  if(references.isEmpty())return List.of();
  var projection=parents==null?new FbaShipmentParents.Projection(Map.of(),Set.of()):parents.read(tenant,store,references);
  var selected=FbaShipmentHierarchy.group(references,projection.links()).values().stream().limit(3).toList();
  var ids=selected.stream().flatMap(Collection::stream).toList();var args=new ArrayList<Object>(List.of(tenant,store,marketplace,sku));args.addAll(ids);
  String sql=SQL.replace("ORDER BY activity_at DESC NULLS LAST,s.shipment_id DESC LIMIT 3","AND s.shipment_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY activity_at DESC NULLS LAST,s.shipment_id DESC");
  var byId=new HashMap<String,Shipment>();rows(sql,args.toArray()).forEach(r->byId.put(r.shipment(),r));
  var components=presentation==null?List.<FbaDepletionRepository.ProductPresentation>of():presentation.presentation(tenant,store,List.of(sku)).stream().flatMap(p->p.products().stream()).toList();
  var result=new ArrayList<Group>();for(var group:selected){var children=group.stream().map(byId::get).filter(Objects::nonNull).toList();if(children.isEmpty())continue;var parent=projection.links().get(group.getFirst());Long sent=children.stream().allMatch(c->c.sent()!=null)?children.stream().mapToLong(c->c.sent()).sum():null;var postal=children.stream().map(c->Objects.toString(c.postal(),"—")).distinct().toList();String origin=postal.size()==1?postal.getFirst():"Multiple";
   var products=sent==null?List.<ProductQuantity>of():components.stream().map(c->new ProductQuantity(c.itemCode(),c.name(),FbaShipmentParents.cases(sent,c.eachPerSku(),c.masterCase()))).toList();
   var childProducts=new LinkedHashMap<String,List<ProductQuantity>>();for(var child:children)childProducts.put(child.shipment(),child.sent()==null?List.of():components.stream().map(c->new ProductQuantity(c.itemCode(),c.name(),FbaShipmentParents.cases(child.sent(),c.eachPerSku(),c.masterCase()))).toList());
   result.add(new Group(parent==null?null:parent.id(),parent==null?(projection.ambiguous().contains(group.getFirst())?"Parent needs review":"Parent unverified"):parent.name(),parent!=null,origin,sent,products,children,Map.copyOf(childProducts)));
  }return List.copyOf(result);
 }
 record Quantity(Integer sent,String source,String note){}
 static Quantity choose(boolean identity,int origins,Integer local,Integer corrected,boolean verified,Integer raw){
  if(!identity)return new Quantity(null,"Needs review","Shipment stock identity differs or is unavailable.");
  if(origins>1)return new Quantity(null,"Needs review","Local dispatch origins or shipment references are ambiguous; review this shipment.");
  if(origins==1&&local!=null)return new Quantity(local,"Local dispatch","Audited dispatched SKU units; Amazon inventory is unchanged.");
  if(corrected!=null)return new Quantity(corrected,"Local correction","Audited local quantity correction; Amazon inventory is unchanged.");
  return verified&&raw!=null?new Quantity(raw,"Amazon shipped","Verified Amazon shipment quantity for this SKU."):new Quantity(null,"Not verified","Sent quantity has not been verified for this SKU.");
 }
 static final String SQL="""
  WITH recent AS (
   SELECT s.*,coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) activity_at,i.seller_sku,i.fnsku,i.quantity_shipped
   FROM amazon_fba_shipments s JOIN amazon_fba_shipment_items i
    ON i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id
   JOIN marketplace_connections c ON c.tenant_id=s.tenant_id AND c.id=s.marketplace_connection_id
   WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND c.channel='AMAZON' AND c.marketplace_identifier=? AND i.seller_sku=?
   ORDER BY activity_at DESC NULLS LAST,s.shipment_id DESC LIMIT 3
  )
  SELECT s.shipment_id,s.activity_at,nullif(trim(s.raw_payload#>>'{ShipFromAddress,PostalCode}'),'') postal,s.quantity_shipped,
   stock.fnsku IS NOT NULL AND nullif(s.fnsku,'')=stock.fnsku identity_matches,
   s.shipment_status IN ('SHIPPED','IN_TRANSIT','DELIVERED','CHECKED_IN','RECEIVING','CLOSED')
    AND s.raw_payload->>'itemEvidenceVersion'='1'
    AND EXISTS(SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(s.raw_payload->'itemEvidence')='array' THEN s.raw_payload->'itemEvidence' ELSE '[]'::jsonb END) e
     WHERE e->>'SellerSKU'=s.seller_sku AND e->>'QuantityShipped'=s.quantity_shipped::text AND e->>'FulfillmentNetworkSKU'=s.fnsku) verified,
   CASE WHEN correction.source_revision=md5(s.raw_payload::text) AND correction.source_quantity=s.quantity_shipped THEN correction.depletion_quantity END corrected,
   local.origins,CASE WHEN local.quantity ~ '^[0-9]{1,7}$' THEN local.quantity::integer END local_quantity
  FROM recent s
  LEFT JOIN LATERAL(SELECT nullif(fnsku,'') fnsku FROM amazon_inventory_snapshots x
   JOIN marketplace_connections c ON c.tenant_id=x.tenant_id AND c.id=x.marketplace_connection_id AND c.marketplace_identifier=x.marketplace_id
   WHERE x.tenant_id=s.tenant_id AND x.marketplace_connection_id=s.marketplace_connection_id AND x.seller_sku=s.seller_sku ORDER BY x.snapshot_at DESC LIMIT 1) stock ON true
  LEFT JOIN LATERAL(SELECT o.source_revision,o.source_quantity,o.depletion_quantity FROM fba_shipment_depletion_overrides o
   WHERE o.tenant_id=s.tenant_id AND o.marketplace_connection_id=s.marketplace_connection_id AND o.shipment_id=s.shipment_id AND o.seller_sku=s.seller_sku ORDER BY o.revision DESC LIMIT 1) correction ON true
  LEFT JOIN LATERAL(SELECT CASE WHEN coalesce(bool_or(ref.mappings>1 OR
     (ref.mappings=1 AND EXISTS(SELECT 1 FROM amazon_fba_shipment_items alias_item
       WHERE alias_item.tenant_id=s.tenant_id AND alias_item.marketplace_connection_id=s.marketplace_connection_id
        AND alias_item.seller_sku=s.seller_sku AND alias_item.shipment_id=CASE WHEN s.shipment_id=a.result->>'shipment' THEN ref.confirmation ELSE a.result->>'shipment' END
        AND alias_item.shipment_id<>s.shipment_id))),false) THEN 2
    ELSE count(DISTINCT(a.plan_id::text||':'||(a.result->>'warehouse')||':'||(a.result->>'shipment'))) END origins,
   (array_agg(a.result->'operationalSkuUnits'->>s.seller_sku ORDER BY a.created_at DESC,a.request_id DESC))[1] quantity
   FROM fba_replenishment_actions a JOIN fba_replenishment_plans p ON p.tenant_id=a.tenant_id AND p.id=a.plan_id
   LEFT JOIN LATERAL(
    SELECT count(DISTINCT child.value->>'confirmationId') mappings,min(child.value->>'confirmationId') confirmation,
      bool_or(child.value->>'confirmationId'=s.shipment_id) matches_current
    FROM (
      SELECT origin.key warehouse,origin.value FROM jsonb_each(coalesce(p.checkpoint->'inboundOrigins','{}'::jsonb)) origin
      UNION ALL
      SELECT history.key warehouse,old.value FROM jsonb_each(coalesce(p.checkpoint->'inboundOriginHistory','{}'::jsonb)) history
       CROSS JOIN LATERAL jsonb_array_elements(history.value) old
    ) origin CROSS JOIN LATERAL jsonb_each(coalesce(origin.value#>'{origin,children}','{}'::jsonb)) child
    WHERE origin.warehouse=a.result->>'warehouse' AND child.key=a.result->>'shipment'
      AND child.value->>'shipmentId'=child.key AND nullif(child.value->>'confirmationId','') IS NOT NULL
   ) ref ON true
   WHERE a.tenant_id=s.tenant_id AND p.marketplace_connection_id=s.marketplace_connection_id
    AND a.kind IN ('CHILD_DISPATCH','CHILD_CORRECTION')
    AND (a.result->>'shipment'=s.shipment_id OR ref.matches_current)
    AND jsonb_exists(a.result->'operationalSkuUnits',s.seller_sku)) local ON true
  ORDER BY s.activity_at DESC NULLS LAST,s.shipment_id DESC
  """;
}
