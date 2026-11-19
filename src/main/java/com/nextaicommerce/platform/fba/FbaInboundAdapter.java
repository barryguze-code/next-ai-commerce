package com.nextaicommerce.platform.fba;

import java.util.*;import java.net.URLEncoder;import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;import com.nextaicommerce.platform.sync.AmazonSpApiClient;

/** Typed outbound seam. Real creation/confirmation/charges stay disabled in Local UAT. */
@Component
public class FbaInboundAdapter {
 public enum Operation {CREATE,PACKING_OPTIONS,CONFIRM_PACKING,PACKING_INFORMATION,PLACEMENT_OPTIONS,CONFIRM_PLACEMENT,TRANSPORT_OPTIONS,CONFIRM_TRANSPORT,CANCEL,STATUS}
 private final AmazonSpApiClient amazon;private final boolean enabled,globalWrites,charges;
 public FbaInboundAdapter(AmazonSpApiClient amazon,@Value("${app.fba.shipment-write-enabled:false}") boolean enabled,@Value("${app.amazon.write-enabled:false}") boolean globalWrites,@Value("${app.fba.charge-write-enabled:false}") boolean charges){this.amazon=amazon;this.enabled=enabled;this.globalWrites=globalWrites;this.charges=charges;}
 public boolean enabled(){return enabled&&globalWrites;}
 public String unavailable(){return "Amazon creation unavailable in Local UAT. Drafts and source allocations stay local; no Amazon shipment or issued labels have been created.";}
 static String id(String id){if(id==null||id.isBlank()||id.length()>160)throw new IllegalArgumentException("A verified Amazon operation identity is required.");return URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20");}
 static String path(Operation operation,String plan,String option){String base="/inbound/fba/2024-03-20/inboundPlans";if(operation==Operation.CREATE)return base;if(operation==Operation.STATUS)return "/inbound/fba/2024-03-20/operations/"+id(plan);base+="/"+id(plan);return base+switch(operation){case PACKING_OPTIONS->"/packingOptions";case CONFIRM_PACKING->"/packingOptions/"+id(option)+"/confirmation";case PACKING_INFORMATION->"/packingInformation";case PLACEMENT_OPTIONS->"/placementOptions";case CONFIRM_PLACEMENT->"/placementOptions/"+id(option)+"/confirmation";case TRANSPORT_OPTIONS->"/transportationOptions";case CONFIRM_TRANSPORT->"/transportationOptions/confirmation";case CANCEL->"/cancellation";default->throw new IllegalArgumentException("Unsupported operation");};}
 public JsonNode invoke(UUID tenant,UUID connection,Operation operation,String plan,String option,String body,boolean paidConfirmation){
  if(!enabled())throw new IllegalStateException(unavailable());
  if((operation==Operation.CONFIRM_TRANSPORT||operation==Operation.CONFIRM_PLACEMENT||operation==Operation.CONFIRM_PACKING)&&(!charges||!paidConfirmation))throw new IllegalStateException("A current reviewed option and explicit paid confirmation are required; charges remain disabled.");
  String path=path(operation,plan,option);
  return operation==Operation.STATUS?amazon.get(tenant,connection,path).json():operation==Operation.CANCEL?amazon.put(tenant,connection,path,body).json():amazon.post(tenant,connection,path,body).json();
 }

 public enum Read {PLAN,PACKING_OPTIONS,PLACEMENT_OPTIONS,TRANSPORT_OPTIONS,SHIPMENT,SHIPMENT_ITEMS,SHIPMENT_BOXES,PACKING_GROUP_ITEMS}
 /** Typed read paths only; Local UAT cannot start API/credential work through this seam. */
 public JsonNode read(UUID tenant,UUID connection,Read read,String plan,String child,String page){
  if(!enabled())throw new IllegalStateException(unavailable());
  return amazon.get(tenant,connection,readPath(read,plan,child,page)).json();
 }
 public JsonNode read(UUID tenant,UUID connection,Read read,String plan,String child,String page,String placementOptionId){
  if(!enabled())throw new IllegalStateException(unavailable());
  if(read!=Read.TRANSPORT_OPTIONS)throw new IllegalArgumentException("Placement filter is only valid for transportation options.");
  return amazon.get(tenant,connection,readPath(read,plan,child,page,placementOptionId)).json();
 }
 static String readPath(Read read,String plan,String child,String page,String placementOptionId){
  if(read!=Read.TRANSPORT_OPTIONS)throw new IllegalArgumentException("Placement filter is only valid for transportation options.");
  String path=readPath(read,plan,child,page);return path+(path.contains("?")?"&":"?")+"placementOptionId="+id(placementOptionId);
 }
 static String readPath(Read read,String plan,String child,String page){
  String base="/inbound/fba/2024-03-20/inboundPlans/"+id(plan);
  String path=base+switch(read){case PLAN->"";case PACKING_OPTIONS->"/packingOptions";case PLACEMENT_OPTIONS->"/placementOptions";case TRANSPORT_OPTIONS->"/transportationOptions";case SHIPMENT->"/shipments/"+id(child);case SHIPMENT_ITEMS->"/shipments/"+id(child)+"/items";case SHIPMENT_BOXES->"/shipments/"+id(child)+"/boxes";case PACKING_GROUP_ITEMS->"/packingGroups/"+id(child)+"/items";};
  if(page!=null&&!page.isBlank()){if(page.length()>1024)throw new IllegalArgumentException("Pagination token too long.");path+="?paginationToken="+URLEncoder.encode(page,StandardCharsets.UTF_8).replace("+","%20");}return path;
 }
 public enum LabelPage {PackageLabel_Letter_2,PackageLabel_Letter_4,PackageLabel_Thermal}
 public JsonNode labels(UUID tenant,UUID connection,String confirmationId,LabelPage page,List<String> verifiedBoxIds){
  if(!enabled())throw new IllegalStateException(unavailable());
  if(verifiedBoxIds==null||verifiedBoxIds.isEmpty()||verifiedBoxIds.size()>1000)throw new IllegalArgumentException("Verified shipment box identities are required.");
  String boxes=verifiedBoxIds.stream().map(FbaInboundAdapter::id).reduce((a,b)->a+","+b).orElseThrow();
  return amazon.get(tenant,connection,"/fba/inbound/v0/shipments/"+id(confirmationId)+"/labels?PageType="+Objects.requireNonNull(page).name()+"&LabelType=UNIQUE&PackageLabelsToPrint="+boxes).json();
 }
 public JsonNode billOfLading(UUID tenant,UUID connection,String confirmationId){
  if(!enabled())throw new IllegalStateException(unavailable());
  return amazon.get(tenant,connection,billOfLadingPath(confirmationId)).json();
 }
 static String billOfLadingPath(String confirmationId){return "/fba/inbound/v0/shipments/"+id(confirmationId)+"/billOfLading";}
}
