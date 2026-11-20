package com.nextaicommerce.platform.fba;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Cached imported evidence only. Never synchronizes Amazon or changes operational quantities. */
@Repository
public class FbaChildObservations {
 private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
 private final JdbcTemplate jdbc;
 public FbaChildObservations(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Progress(String importedShipmentId,Long shipped,Long received,Instant observedAt,String issue){}
 @Transactional(readOnly=true) public Map<String,Progress> read(UUID tenant,UUID connection,Collection<FbaInboundWorkflow.Child> children){
  if(children.size()>1000)throw new IllegalArgumentException("Shipment observation lookup exceeds the plan budget.");var refs=new LinkedHashSet<String>();for(var child:children){refs.add(child.shipmentId());if(child.confirmationId()!=null)refs.add(child.confirmationId());}if(refs.isEmpty())return Map.of();
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());var args=new ArrayList<Object>();args.add(tenant);args.add(connection);args.addAll(refs);
  var rows=jdbc.queryForList("""
   SELECT s.shipment_id,jsonb_exists(s.raw_payload,'itemsCheckedAt') items_complete,
    s.raw_payload->>'itemsCheckedAt' observed_at,s.raw_payload->>'itemEvidenceVersion' evidence_version,
    (s.raw_payload->'itemEvidence')::text item_evidence,i.seller_sku,i.fnsku,i.quantity_shipped,i.quantity_received
   FROM amazon_fba_shipments s LEFT JOIN amazon_fba_shipment_items i
    ON i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id
   WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.shipment_id IN (
   """+String.join(",",Collections.nCopies(refs.size(),"?"))+ ") ORDER BY s.shipment_id,i.seller_sku",args.toArray());return project(children,rows);
 }
 static Instant observed(Object raw){if(raw==null)return null;String text=raw.toString().trim().replace(' ','T');if(text.matches(".*[+-][0-9]{2}$"))text+=":00";else if(text.matches(".*[+-][0-9]{4}$"))text=text.substring(0,text.length()-2)+":"+text.substring(text.length()-2);try{return Instant.parse(text);}catch(RuntimeException ignored){return null;}}
 static boolean completedEvidence(List<Map<String,Object>> rows){
  var first=rows.getFirst();if(!"1".equals(Objects.toString(first.get("evidence_version"),"")))return false;
  try{var evidence=JSON.readTree(Objects.toString(first.get("item_evidence"),""));if(!evidence.isArray()||evidence.size()!=rows.size())return false;var seen=new HashSet<String>();for(var item:evidence){String sku=item.path("SellerSKU").asText();if(!seen.add(sku))return false;var row=rows.stream().filter(r->sku.equals(r.get("seller_sku"))).findFirst().orElse(null);if(row==null||!item.path("QuantityShipped").isIntegralNumber()||!item.path("QuantityReceived").isIntegralNumber()||!item.path("QuantityShipped").canConvertToLong()||!item.path("QuantityReceived").canConvertToLong()||!(row.get("quantity_shipped") instanceof Number shipped)||!(row.get("quantity_received") instanceof Number received)||item.path("QuantityShipped").longValue()!=shipped.longValue()||item.path("QuantityReceived").longValue()!=received.longValue()||!item.path("FulfillmentNetworkSKU").asText("").equals(Objects.toString(row.get("fnsku"),"")))return false;}return true;}catch(RuntimeException invalid){return false;}
 }
 static Map<String,Progress> project(Collection<FbaInboundWorkflow.Child> children,List<Map<String,Object>> rows){
  var grouped=new HashMap<String,List<Map<String,Object>>>();for(var row:rows)grouped.computeIfAbsent(row.get("shipment_id").toString(),k->new ArrayList<>()).add(row);var result=new LinkedHashMap<String,Progress>();
  for(var child:children){var matching=new LinkedHashSet<String>();if(grouped.containsKey(child.shipmentId()))matching.add(child.shipmentId());if(child.confirmationId()!=null&&grouped.containsKey(child.confirmationId()))matching.add(child.confirmationId());if(matching.size()!=1){result.put(child.shipmentId(),new Progress(null,null,null,null,matching.isEmpty()?"Not imported":"Imported references need review"));continue;}String reference=matching.iterator().next();var evidence=grouped.get(reference);var first=evidence.getFirst();Instant at=observed(first.get("observed_at"));
   boolean complete=Boolean.TRUE.equals(first.get("items_complete"))&&at!=null&&completedEvidence(evidence);var skus=new HashSet<String>();long shipped=0,received=0;for(var row:evidence){String sku=(String)row.get("seller_sku");if(sku==null||!skus.add(sku)||!(row.get("quantity_shipped") instanceof Number s)||!(row.get("quantity_received") instanceof Number r)||s.longValue()<0||r.longValue()<0){complete=false;continue;}shipped=Math.addExact(shipped,s.longValue());received=Math.addExact(received,r.longValue());}complete&=skus.equals(child.skuUnits().keySet());result.put(child.shipmentId(),new Progress(reference,complete?shipped:null,complete?received:null,at,complete?null:"Imported item evidence incomplete or SKU composition differs"));
  }return Map.copyOf(result);
 }
}
