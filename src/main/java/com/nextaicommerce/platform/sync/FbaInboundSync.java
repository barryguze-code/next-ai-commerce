package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One rate-limited request per queue claim. Page data and checkpoint commit together. */
@Component
public class FbaInboundSync {
    private final AmazonSpApiClient amazon;private final AmazonSyncStore store;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final AmazonReportNormalizer normalizer;
    public FbaInboundSync(AmazonSpApiClient amazon,AmazonSyncStore store,JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AmazonReportNormalizer normalizer){
        this.amazon=amazon;this.store=store;this.jdbc=jdbc;this.tx=tx;this.json=json;this.normalizer=normalizer;
    }
    public void processPage(AmazonSyncStore.Job job){
        ObjectNode cursor=tx.execute(s->{scope(job);return (ObjectNode)json.readTree(jdbc.queryForObject(
            "SELECT cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",String.class,job.tenantId(),job.id()));});
        int pages=cursor.path("pages").asInt();
        if(pages>=10000)throw new IllegalStateException("FBA import exceeded its page budget; review the checkpoint before retrying.");
        if(!cursor.path("feeShipment").asText("").isBlank()){importShipmentFees(job,cursor,pages);return;}
        var pending=new ArrayList<String>();cursor.path("pending").forEach(n->pending.add(n.asText()));
        String shipment=pending.isEmpty()?null:pending.getFirst();
        String token=cursor.path(shipment==null?"next":"itemNext").asText("");
        String path;
        if(shipment==null){
            path="/fba/inbound/v0/shipments?MarketplaceId="+encode(job.marketplaceId())+
                (token.isBlank()?"&QueryType=SHIPMENT&ShipmentStatusList=WORKING,READY_TO_SHIP,SHIPPED,IN_TRANSIT,DELIVERED,CHECKED_IN,RECEIVING,CLOSED,CANCELLED,DELETED,ERROR":"&QueryType=NEXT_TOKEN&NextToken="+encode(token));
        }else path=token.isBlank()?"/fba/inbound/v0/shipments/"+encode(shipment)+"/items":
            "/fba/inbound/v0/shipmentItems?MarketplaceId="+encode(job.marketplaceId())+"&QueryType=NEXT_TOKEN&NextToken="+encode(token);
        var response=amazon.get(job.tenantId(),job.connectionId(),path);
        JsonNode payload=response.json().path("payload");
        String field=shipment==null?"ShipmentData":"ItemData";
        if(!payload.path(field).isArray())throw new IllegalStateException("Amazon FBA response did not contain the expected records.");
        // Store only shipment operational metadata; no shipper name, phone or street address.
        var sanitized=json.createObjectNode();var records=sanitized.putArray(field);
        for(JsonNode input:payload.path(field)){
            var row=json.createObjectNode();
            for(String key:shipment==null?List.of("ShipmentId","ShipmentName","ShipmentStatus","DestinationFulfillmentCenterId","LabelPrepType","CreationDate","ShipmentCreatedDate","LastUpdatedDate"):List.of("ShipmentId","SellerSKU","FulfillmentNetworkSKU","QuantityShipped","QuantityReceived","QuantityInCase")){
                if(input.has(key))row.set(key,input.path(key));
            }
            if(shipment==null){var address=row.putObject("ShipFromAddress");for(String key:List.of("City","StateOrProvinceCode","CountryCode"))if(input.path("ShipFromAddress").has(key))address.set(key,input.path("ShipFromAddress").path(key));}
            records.add(row);
        }
        String next=payload.path("NextToken").asText("");
        if(shipment==null){
            pending.clear();for(JsonNode row:records){String id=row.path("ShipmentId").asText("");if(id.isBlank())throw new IllegalStateException("Amazon shipment ID missing.");pending.add(id);}
            cursor.put("next",next);
        }else {cursor.put("itemNext",next);if(next.isBlank())pending.removeFirst();}
        var remaining=cursor.putArray("pending");pending.forEach(remaining::add);
        cursor.put("pages",pages+1);
        if(shipment!=null&&next.isBlank()&&!cursor.path("feesUnavailable").asBoolean()){
            Boolean needsFees=tx.execute(s->{scope(job);return jdbc.queryForObject("SELECT NOT jsonb_exists(raw_payload,'feesCheckedAt') OR (raw_payload->>'feesCheckedAt')::timestamptz < now()-interval '24 hours' FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",Boolean.class,job.tenantId(),job.connectionId(),shipment);});
            if(Boolean.TRUE.equals(needsFees))cursor.put("feeShipment",shipment);
        }
        boolean done=pending.isEmpty()&&cursor.path("next").asText("").isBlank()&&cursor.path("feeShipment").asText("").isBlank();
        tx.executeWithoutResult(s->{
            scope(job);
            long count=store.saveJsonPage(job,"fba-inbound-page-"+pages,sanitized.toString(),response.requestId(),source->
                shipment==null?saveShipments(job,records):saveItems(job,shipment,records));
            long total=cursor.path("records").asLong()+count;cursor.put("records",total);
            jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),records_processed=?,status='WAITING',available_at=now()+interval '2 seconds',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),total,job.tenantId(),job.id());
            if(done)store.complete(job,total);
        });
    }
    long saveShipments(AmazonSyncStore.Job job,JsonNode rows){
        long count=0;for(JsonNode row:rows){
            jdbc.update("""
                INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_name,
                    shipment_status,destination_fulfillment_center,label_prep_type,created_date,updated_date,raw_payload)
                VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(tenant_id,marketplace_connection_id,shipment_id) DO UPDATE SET
                    shipment_name=EXCLUDED.shipment_name,shipment_status=EXCLUDED.shipment_status,
                    destination_fulfillment_center=EXCLUDED.destination_fulfillment_center,
                    label_prep_type=EXCLUDED.label_prep_type,
                    created_date=coalesce(EXCLUDED.created_date,amazon_fba_shipments.created_date),updated_date=EXCLUDED.updated_date,
                    raw_payload=EXCLUDED.raw_payload || CASE WHEN jsonb_exists(amazon_fba_shipments.raw_payload,'feesCheckedAt') THEN jsonb_build_object('feesCheckedAt',amazon_fba_shipments.raw_payload->>'feesCheckedAt') ELSE '{}'::jsonb END,updated_at=now()
                """,job.tenantId(),job.connectionId(),row.path("ShipmentId").asText(),row.path("ShipmentName").asText(null),
                row.path("ShipmentStatus").asText(null),row.path("DestinationFulfillmentCenterId").asText(null),row.path("LabelPrepType").asText(null),creationDate(row),timestamp(row,"LastUpdatedDate"),row.toString());count++;
        }return count;
    }
    private void importShipmentFees(AmazonSyncStore.Job job,ObjectNode cursor,int pages){
        String shipment=cursor.path("feeShipment").asText(),token=cursor.path("feeNext").asText("");
        // Amazon inbound charges identify their FBA shipment using ORDER_ID. No date window:
        // historical replenishment charges must not be limited to the recent-orders import.
        String path="/finances/2024-06-19/transactions?relatedIdentifierName=ORDER_ID&relatedIdentifierValue="+encode(shipment)+(token.isBlank()?"":"&nextToken="+encode(token));
        AmazonSpApiClient.ApiResponse response;
        try{response=amazon.get(job.tenantId(),job.connectionId(),path);}
        catch(AmazonSpApiClient.AmazonApiException ex){
            if(ex.status()!=403)throw ex;
            // Finance permission is independent of shipment visibility. Continue inventory import.
            cursor.put("feesUnavailable",true);cursor.remove("feeShipment");cursor.remove("feeNext");cursor.put("pages",pages+1);
            org.slf4j.LoggerFactory.getLogger(FbaInboundSync.class).warn("FBA shipment finance access denied for connection={}; shipment import continues, fees remain unknown",job.connectionId());
            tx.executeWithoutResult(s->{scope(job);jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),status='WAITING',available_at=now()+interval '2 seconds',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),job.tenantId(),job.id());if(cursor.path("pending").isEmpty()&&cursor.path("next").asText("").isBlank())store.complete(job,cursor.path("records").asLong());});
            return;
        }
        if(!response.json().path("payload").path("transactions").isArray())throw new IllegalStateException("Amazon shipment charges response did not contain transactions.");
        String next=response.json().path("payload").path("nextToken").asText("");
        cursor.put("feeNext",next);cursor.put("pages",pages+1);
        if(next.isBlank())cursor.remove("feeShipment");
        tx.executeWithoutResult(s->{scope(job);
            long count=store.saveJsonPage(job,"fba-fees-"+pages,response.body(),response.requestId(),source->normalizer.normalizeShipmentFinances(job,response.json(),source));
            if(next.isBlank())jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=jsonb_set(raw_payload,'{feesCheckedAt}',to_jsonb(now()::text)) WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",job.tenantId(),job.connectionId(),shipment);
            long total=cursor.path("records").asLong()+count;cursor.put("records",total);
            jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),records_processed=?,status='WAITING',available_at=now()+interval '2 seconds',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),total,job.tenantId(),job.id());
            if(next.isBlank()&&cursor.path("pending").isEmpty()&&cursor.path("next").asText("").isBlank())store.complete(job,total);
        });
    }
    long saveItems(AmazonSyncStore.Job job,String shipment,JsonNode rows){
        long count=0;for(JsonNode row:rows){
            if(!shipment.equals(row.path("ShipmentId").asText(shipment)))throw new IllegalStateException("Amazon returned an item for another shipment.");
            String sku=row.path("SellerSKU").asText("");if(sku.isBlank())throw new IllegalStateException("Amazon shipment SKU missing.");
            if(!row.path("QuantityShipped").canConvertToInt()||!row.path("QuantityReceived").canConvertToInt())throw new IllegalStateException("Amazon shipment quantities missing.");
            jdbc.update("""
                INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,
                    fnsku,quantity_shipped,quantity_received,quantity_in_case) VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,marketplace_connection_id,shipment_id,seller_sku) DO UPDATE SET
                    fnsku=EXCLUDED.fnsku,quantity_shipped=EXCLUDED.quantity_shipped,
                    quantity_received=EXCLUDED.quantity_received,quantity_in_case=EXCLUDED.quantity_in_case,updated_at=now()
                """,job.tenantId(),job.connectionId(),shipment,sku,row.path("FulfillmentNetworkSKU").asText(null),
                row.path("QuantityShipped").asInt(),row.path("QuantityReceived").asInt(),row.has("QuantityInCase")?row.path("QuantityInCase").asInt():null);count++;
        }return count;
    }
    private void scope(AmazonSyncStore.Job job){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,job.tenantId().toString());}
    static java.sql.Timestamp shipmentDate(JsonNode row){
        for(String key:List.of("CreationDate","ShipmentCreatedDate","LastUpdatedDate")){
            var date=timestamp(row,key);if(date!=null)return date;
        }return null;
    }
    private static java.sql.Timestamp creationDate(JsonNode row){
        var date=timestamp(row,"CreationDate");return date==null?timestamp(row,"ShipmentCreatedDate"):date;
    }
    private static java.sql.Timestamp timestamp(JsonNode row,String key){
        try{return java.sql.Timestamp.from(java.time.OffsetDateTime.parse(row.path(key).asText("")).toInstant());}
        catch(java.time.format.DateTimeParseException ex){return null;}
    }
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
}
