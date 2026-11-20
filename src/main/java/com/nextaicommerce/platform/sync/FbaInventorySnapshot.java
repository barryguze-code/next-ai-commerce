package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates a complete, marketplace-scoped inventory read before any publication. */
public final class FbaInventorySnapshot {
 private FbaInventorySnapshot(){}
 public record Row(String sku,String fnsku,String asin,String condition,int available,int working,int shipped,int receiving,int reserved,int unsellable,int researching,int total,Instant observedAt,String raw){}
 public static List<Row> validate(String marketplace,JsonNode pages){
  if(marketplace==null||marketplace.isBlank()||!pages.isArray()||pages.isEmpty()||pages.size()>100)throw new IllegalArgumentException("Inventory pages are missing.");
  var rows=new ArrayList<Row>();var skus=new HashSet<String>();var tokens=new HashSet<String>();Instant previous=null;
  for(int index=0;index<pages.size();index++){
   var page=pages.get(index);Instant observed=Instant.parse(page.path("observedAt").asText());
   if(observed.isAfter(Instant.now().plusSeconds(60))||observed.isBefore(Instant.now().minusSeconds(3600))||previous!=null&&observed.isBefore(previous))throw new IllegalArgumentException("Inventory observation time is invalid or stale.");previous=observed;
   var response=page.path("response");var payload=response.path("payload");var granularity=payload.path("granularity");
   if(!marketplace.equals(granularity.path("granularityId").asText())||!"Marketplace".equals(granularity.path("granularityType").asText())||!payload.path("inventorySummaries").isArray()||response.hasNonNull("errors"))throw new IllegalArgumentException("Inventory response scope or shape is invalid.");
   String next=response.path("pagination").path("nextToken").asText("");
   if(index==pages.size()-1?!next.isBlank():next.isBlank()||!tokens.add(next))throw new IllegalArgumentException("Inventory pagination is incomplete or repeated.");
   for(var item:payload.path("inventorySummaries")){
    String sku=item.path("sellerSku").asText("");if(sku.isBlank()||!skus.add(sku))throw new IllegalArgumentException("Inventory SKU is missing or ambiguous.");
    var d=item.path("inventoryDetails");int available=quantity(d,"fulfillableQuantity"),working=quantity(d,"inboundWorkingQuantity"),shipped=quantity(d,"inboundShippedQuantity"),receiving=quantity(d,"inboundReceivingQuantity"),reserved=quantity(d.path("reservedQuantity"),"totalReservedQuantity"),unsellable=quantity(d.path("unfulfillableQuantity"),"totalUnfulfillableQuantity"),researching=quantity(d.path("researchingQuantity"),"totalResearchingQuantity"),total=quantity(item,"totalQuantity");
    // Preserve exact API evidence and expose the established report reader's quantity keys.
    ObjectNode raw=((ObjectNode)item).deepCopy();raw.put("afn-fulfillable-quantity",available);raw.put("afn-inbound-working-quantity",working);raw.put("afn-inbound-shipped-quantity",shipped);raw.put("afn-inbound-receiving-quantity",receiving);raw.put("afn-reserved-quantity",reserved);raw.put("afn-unsellable-quantity",unsellable);raw.put("afn-researching-quantity",researching);raw.put("afn-total-quantity",total);
    var transfer=d.path("reservedQuantity").path("pendingTransshipmentQuantity");if(!transfer.isMissingNode())raw.put("afn-fc-transfer-quantity",quantity(d.path("reservedQuantity"),"pendingTransshipmentQuantity"));
    rows.add(new Row(sku,item.path("fnSku").asText(null),item.path("asin").asText(null),item.path("condition").asText(null),available,working,shipped,receiving,reserved,unsellable,researching,total,observed,raw.toString()));
   }
  }return List.copyOf(rows);
 }
 private static int quantity(JsonNode node,String key){var n=node.path(key);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<0)throw new IllegalArgumentException("Inventory quantity is missing or invalid: "+key);return n.intValue();}
}
