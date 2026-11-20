package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Frozen query contracts live in the existing tenant/store job checkpoint. */
final class FbaShipmentReadPlan {
    static final Set<String> TERMINAL=Set.of("CLOSED","CANCELLED","DELETED");
    static final String ALL_STATUSES="WORKING,READY_TO_SHIP,SHIPPED,IN_TRANSIT,DELIVERED,CHECKED_IN,RECEIVING,CLOSED,CANCELLED,DELETED,ERROR";
    static boolean resume(ObjectNode cursor,Instant now){
        if(!cursor.path("paused").asBoolean())return false;
        boolean budget="READ_BUDGET".equals(cursor.path("pauseReason").asText());
        cursor.remove("paused");cursor.remove("pauseReason");cursor.putObject("readBudget").put("start",now.toString()).put("requests",0);return budget;
    }
    static boolean trustedHistory(JsonNode cursor){
        if(cursor.path("itemsRefreshOnly").asBoolean()||cursor.path("parentsOnly").asBoolean())return false;
        var plan=cursor.path("shipmentRead");
        if(plan.isObject())return plan.path("revision").asInt()==1&&plan.path("historyComplete").asBoolean();
        return cursor.path("shipmentsComplete").asBoolean()&&cursor.path("pages").asInt()>0&&cursor.path("pending").isEmpty()&&cursor.path("next").asText("").isBlank();
    }
    static Instant windowStart(Instant end,Instant watermark){
        Instant recent=end.minus(Duration.ofDays(7));
        return watermark==null?recent:Collections.min(List.of(recent,watermark.minus(Duration.ofDays(1))));
    }
    static void initialize(ObjectNode cursor,boolean historyComplete,Instant watermark,Instant end){
        var plan=cursor.putObject("shipmentRead");plan.put("revision",1).put("mode",historyComplete?"INCREMENTAL":"BACKFILL")
            .put("stage",historyComplete?"RECENT":"BACKFILL").put("windowStart",windowStart(end,watermark).toString()).put("windowEnd",end.toString());
        if(historyComplete)plan.put("historyComplete",true);
        if(watermark!=null)plan.put("coveredThrough",watermark.toString());
    }
    static String path(String marketplace,ObjectNode cursor){
        String token=cursor.path("next").asText("");String base="/fba/inbound/v0/shipments?MarketplaceId="+encode(marketplace);
        if(!token.isBlank())return base+"&QueryType=NEXT_TOKEN&NextToken="+encode(token);
        var plan=cursor.path("shipmentRead");
        if("RECENT".equals(plan.path("stage").asText()))return base+"&QueryType=DATE_RANGE&LastUpdatedAfter="+encode(plan.path("windowStart").asText())+"&LastUpdatedBefore="+encode(plan.path("windowEnd").asText())+"&ShipmentStatusList="+ALL_STATUSES;
        if("OPEN".equals(plan.path("stage").asText())){
            var ids=new ArrayList<String>();plan.path("openBatch").forEach(id->ids.add(encode(id.asText())));
            if(ids.isEmpty()||ids.size()>50)throw new IllegalStateException("Invalid FBA open-shipment batch");
            // No status filter: an older open shipment may now be CLOSED/CANCELLED/DELETED.
            return base+"&QueryType=SHIPMENT&ShipmentIdList="+String.join(",",ids);
        }
        return base+"&QueryType=SHIPMENT&ShipmentStatusList="+ALL_STATUSES;
    }
    static boolean needsItems(JsonNode row,JsonNode existing,boolean incremental){
        if(!incremental||existing==null||existing.isMissingNode())return true;
        if(!TERMINAL.contains(row.path("ShipmentStatus").asText()))return true;
        // v0 headers do not guarantee an update revision; never infer unchanged received units.
        if(!FbaParentPlanImport.validTime(row.path("LastUpdatedDate").asText()))return true;
        return existing.path("itemEvidenceVersion").asInt()!=1||!existing.path("itemEvidence").isArray()
            ||!revision(row).equals(existing.path("itemsMetadataRevision").asText());
    }
    static String revision(JsonNode row){
        var fields=new ArrayList<String>();for(String key:List.of("ShipmentId","ShipmentStatus","LastUpdatedDate","DestinationFulfillmentCenterId","LabelPrepType"))fields.add(key+"="+row.path(key).toString());
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",fields).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static boolean isIncremental(ObjectNode cursor){return "INCREMENTAL".equals(cursor.path("shipmentRead").path("mode").asText());}
    static boolean complete(ObjectNode cursor){
        if(!cursor.path("pending").isEmpty()||!cursor.path("next").asText("").isBlank())return false;
        var plan=cursor.withObject("shipmentRead");String stage=plan.path("stage").asText();
        if("RECENT".equals(stage)){plan.put("stage","OPEN");return false;}
        if("OPEN".equals(stage)){plan.remove("openBatch");return false;}
        plan.put("historyComplete",true).put("coveredThrough",plan.path("windowEnd").asText());return true;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
}
