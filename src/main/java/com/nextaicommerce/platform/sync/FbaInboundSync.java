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
    private final AmazonReportNormalizer normalizer;private final FbaSyncHistory history;
    public FbaInboundSync(AmazonSpApiClient amazon,AmazonSyncStore store,JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AmazonReportNormalizer normalizer){
        this.amazon=amazon;this.store=store;this.jdbc=jdbc;this.tx=tx;this.json=json;this.normalizer=normalizer;this.history=new FbaSyncHistory(jdbc,json);
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.fba.FbaDepletionWorker depletionWorker;
    private void afterCompletedRead(AmazonSyncStore.Job job){if(depletionWorker!=null)depletionWorker.afterCompletedImport(job.tenantId(),job.connectionId(),job.id());}
    public void processPage(AmazonSyncStore.Job job){
        ObjectNode cursor=tx.execute(s->{scope(job);return (ObjectNode)json.readTree(jdbc.queryForObject(
            "SELECT cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",String.class,job.tenantId(),job.id()));});
        tx.executeWithoutResult(s->{scope(job);String before=cursor.toString();history.initialize(job,cursor);
            if(!before.equals(cursor.toString()))jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb) WHERE tenant_id=? AND id=?",cursor.toString(),job.tenantId(),job.id());
        });
        int pages=cursor.path("pages").asInt();
        boolean parentsOnly=cursor.path("parentsOnly").asBoolean();
        if(parentsOnly&&!"FBA_MANUAL".equals(job.profile()))throw new IllegalStateException("Parent-only reads require an explicit manual job.");
        if(parentsOnly&&cursor.path("parentsComplete").asBoolean()){tx.executeWithoutResult(s->{scope(job);store.complete(job,cursor.path("records").asLong());});return;}
        if(("FBA_MANUAL".equals(job.profile())||"FBA_INBOUND".equals(job.profile()))&&(parentsOnly||cursor.path("shipmentsComplete").asBoolean()&&!cursor.path("itemsRefreshOnly").asBoolean())&&!cursor.path("parentsComplete").asBoolean()){
            importParents(job,cursor);return;
        }
        if(!admitRead(job,cursor))return;
        if(cursor.has("shipmentRead")&&!cursor.path("shipmentsComplete").asBoolean()&&cursor.path("pending").isEmpty()&&cursor.path("next").asText("").isBlank()&&"OPEN".equals(cursor.path("shipmentRead").path("stage").asText())){
            boolean available=tx.execute(s->{scope(job);return history.nextOpenBatch(job,cursor);});
            if(!available){cursor.put("shipmentsComplete",true);tx.executeWithoutResult(s->{scope(job);saveParentCursor(job,cursor,3);});return;}
        }
        if(cursor.path("shipmentsComplete").asBoolean()&&!cursor.path("feesComplete").asBoolean()){importShipmentFees(job,cursor,pages);afterCompletedRead(job);return;}
        var pending=new ArrayList<String>();cursor.path("pending").forEach(n->pending.add(n.asText()));
        String shipment=pending.isEmpty()?null:pending.getFirst();
        String token=cursor.path(shipment==null?"next":"itemNext").asText("");
        // A resumed legacy checkpoint cannot establish complete membership: restart only its item read.
        if(shipment!=null && !token.isBlank() && !cursor.path("itemEvidence").isArray()){token="";cursor.put("itemNext","");}
        if(shipment!=null && token.isBlank())cursor.putArray("itemEvidence");
        String path;
        if(shipment==null){
            path=FbaShipmentReadPlan.path(job.marketplaceId(),cursor);
        }else path=token.isBlank()?"/fba/inbound/v0/shipments/"+encode(shipment)+"/items":
            "/fba/inbound/v0/shipmentItems?MarketplaceId="+encode(job.marketplaceId())+"&QueryType=NEXT_TOKEN&NextToken="+encode(token);
        var response=amazon.get(job.tenantId(),job.connectionId(),path);
        cursor.put("consecutiveFailures",0);
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
            if(shipment==null){var address=row.putObject("ShipFromAddress");for(String key:List.of("City","StateOrProvinceCode","PostalCode","CountryCode"))if(input.path("ShipFromAddress").has(key))address.set(key,input.path("ShipFromAddress").path(key));}
            records.add(row);
        }
        String next=payload.path("NextToken").asText("");
        if(shipment!=null){
            var evidence=(tools.jackson.databind.node.ArrayNode)cursor.path("itemEvidence");
            var seen=new HashSet<String>();evidence.forEach(i->seen.add(i.path("SellerSKU").asText()));
            for(var row:records){
                if(!shipment.equals(row.path("ShipmentId").asText()) || row.path("SellerSKU").asText("").isBlank()
                    || !row.path("QuantityShipped").canConvertToInt() || row.path("QuantityShipped").asInt()<0
                    || !row.path("QuantityReceived").canConvertToInt() || row.path("QuantityReceived").asInt()<0
                    || !seen.add(row.path("SellerSKU").asText()))throw new IllegalStateException("Incomplete or conflicting FBA item evidence.");
                evidence.add(row.deepCopy());
            }
        }
        if(shipment==null){
            var previous=FbaShipmentReadPlan.isIncremental(cursor)?tx.execute(s->{scope(job);return history.metadata(job,records);}):Map.<String,JsonNode>of();
            pending.clear();for(JsonNode row:records){String id=row.path("ShipmentId").asText("");if(id.isBlank())throw new IllegalStateException("Amazon shipment ID missing.");
                boolean required=FbaShipmentReadPlan.needsItems(row,previous.get(id),FbaShipmentReadPlan.isIncremental(cursor));if(required)pending.add(id);
                if("OPEN".equals(cursor.path("shipmentRead").path("stage").asText()))cursor.withObject("shipmentRead").withArray("openSeen").add(id);
            }
            if(!next.isBlank()){
                for(var oldToken:cursor.withArray("shipmentSeenTokens"))if(next.equals(oldToken.asText()))throw new IllegalStateException("Repeated FBA shipment pagination token; incomplete coverage retained");
                cursor.withArray("shipmentSeenTokens").add(next);
            }else cursor.remove("shipmentSeenTokens");
            cursor.put("next",next);
        }else {cursor.put("itemNext",next);if(next.isBlank())pending.removeFirst();}
        var remaining=cursor.putArray("pending");pending.forEach(remaining::add);
        cursor.put("pages",pages+1);
        boolean shipmentsDone=pending.isEmpty()&&cursor.path("next").asText("").isBlank();
        if(shipmentsDone&&cursor.has("shipmentRead")){
            var plan=cursor.withObject("shipmentRead");
            if("OPEN".equals(plan.path("stage").asText())){
                var returned=new HashSet<String>();plan.path("openSeen").forEach(value->returned.add(value.asText()));
                for(var expected:plan.path("openBatch"))if(!returned.contains(expected.asText()))plan.put("unresolvedOpen",true);
            }
            shipmentsDone=FbaShipmentReadPlan.complete(cursor);
        }
        if(shipmentsDone)cursor.put("shipmentsComplete",true);
        boolean done=shipmentsDone&&cursor.path("feesComplete").asBoolean();
        tx.executeWithoutResult(s->{
            scope(job);
            long count=store.saveJsonPage(job,"fba-inbound-page-"+pages,sanitized.toString(),response.requestId(),source->
                shipment==null?saveShipments(job,records):saveItems(job,shipment,records));
            if(shipment!=null){
                if(next.isBlank()){
                    var metadata=jdbc.query("SELECT raw_payload::text FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",(r,n)->json.readTree(r.getString(1)),job.tenantId(),job.connectionId(),shipment);
                    String revision=metadata.isEmpty()?"":FbaShipmentReadPlan.revision(metadata.getFirst());
                    jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=raw_payload || jsonb_build_object('itemsCheckedAt',now()::text,'itemEvidence',?::jsonb,'itemEvidenceVersion',1,'itemEvidenceJob',?::text,'itemsMetadataRevision',?::text) WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",cursor.path("itemEvidence").toString(),job.id().toString(),revision,job.tenantId(),job.connectionId(),shipment);
                }
                else jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=raw_payload - 'itemEvidence' - 'itemEvidenceVersion' WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",job.tenantId(),job.connectionId(),shipment);
            }
            long total=cursor.path("records").asLong()+count;cursor.put("records",total);
            jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),records_processed=?,status='WAITING',error_code=NULL,error_message=NULL,available_at=now()+?*interval '1 second',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),total,requestDelay(response),job.tenantId(),job.id());
            if(done)store.complete(job,total);
        });
        if(done&&!cursor.path("itemsRefreshOnly").asBoolean())afterCompletedRead(job);
    }
    private boolean admitRead(AmazonSyncStore.Job job,ObjectNode cursor){
        var now=java.time.Instant.now();var budget=cursor.withObject("readBudget");
        java.time.Instant start;try{start=java.time.Instant.parse(budget.path("start").asText());}catch(java.time.DateTimeException missing){start=now;}
        if(!budget.has("start")||!now.isBefore(start.plusSeconds(3600))){budget.put("start",now.toString()).put("requests",0);start=now;}
        if(budget.path("requests").asInt()>=600){
            cursor.put("paused","FBA_MANUAL".equals(job.profile())).put("pauseReason","READ_BUDGET");var resume=start.plusSeconds(3600);
            tx.executeWithoutResult(s->{scope(job);jdbc.update("UPDATE marketplace_sync_jobs SET cursor=?::jsonb,status='WAITING',available_at=?,lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),java.sql.Timestamp.from(resume),job.tenantId(),job.id());});return false;
        }
        budget.put("requests",budget.path("requests").asInt()+1);cursor.remove("paused");cursor.remove("pauseReason");return true;
    }
    private void importParents(AmazonSyncStore.Job job,ObjectNode cursor){
        if(!admitRead(job,cursor))return;
        var state=cursor.withObject("parentImport");
        if(!state.has("reuseInitialized"))tx.executeWithoutResult(s->{scope(job);boolean legacy=!state.isEmpty();
            var versions=state.putObject("knownVersions");history.parentVersions(job).forEach(versions::set);
            var baseline=history.parentBaseline(job);state.put("incremental",baseline!=null&&!legacy).put("reuseInitialized",true);
            java.time.Instant watermark=null;
            if(baseline!=null)try{watermark=java.time.Instant.parse(baseline.path("coveredThrough").asText(baseline.path("legacyWindowEnd").asText()));}catch(java.time.DateTimeException missing){}
            state.put("cutoff",FbaShipmentReadPlan.windowStart(job.windowEnd(),watermark).toString());
            state.put("unresolvedOpenParents",history.unresolvedOpenParents(job));
        });
        AmazonSpApiClient.ApiResponse response;
        try{response=amazon.getAt(job.tenantId(),job.connectionId(),AmazonSpApiClient.endpoint(job.marketplaceId()),FbaParentPlanImport.path(state));}
        catch(AmazonSpApiClient.AmazonApiException ex){
            if(ex.status()!=400&&ex.status()!=403&&ex.status()!=404)throw ex;
            // Permission/history/token unavailability does not erase previously verified relationships.
            state.put("coverage",state.path("completedPlans").asInt()==0?"UNAVAILABLE":"PARTIAL").put("httpStatus",ex.status());
            state.put("coverageReason",ex.status()==403?"AUTHORIZATION_DENIED":ex.status()==404?"PLAN_OR_HISTORY_NOT_AVAILABLE":"REQUEST_OR_PAGINATION_REJECTED");cursor.put("parentsComplete",true);
            tx.executeWithoutResult(s->{scope(job);saveParentCursor(job,cursor,3);completeParentOnly(job,cursor);});return;
        }
        var document=FbaParentPlanImport.accept(job,state,response.json(),response.requestId());
        cursor.put("consecutiveFailures",0);
        if(FbaParentPlanImport.complete(state)){cursor.put("parentsComplete",true);state.put("coverage",state.path("scopeAmbiguousPlans").asInt()>0||state.path("uncertainOrder").asBoolean()?"PARTIAL":"API_DISCOVERY_COMPLETE");}
        else state.put("coverage","IN_PROGRESS");
        if("API_DISCOVERY_COMPLETE".equals(state.path("coverage").asText()))state.put("coveredThrough",job.windowEnd().toString());
        tx.executeWithoutResult(s->{scope(job);
            if(document!=null)store.saveFbaParentEvidence(job,document.toString(),response.requestId());
            saveParentCursor(job,cursor,requestDelay(response));
            completeParentOnly(job,cursor);
        });
    }
    private void completeParentOnly(AmazonSyncStore.Job job,ObjectNode cursor){
        if(cursor.path("parentsOnly").asBoolean()&&cursor.path("parentsComplete").asBoolean())store.complete(job,cursor.path("records").asLong());
    }
    private void saveParentCursor(AmazonSyncStore.Job job,ObjectNode cursor,long delay){
        jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),status='WAITING',available_at=now()+?*interval '1 second',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),delay,job.tenantId(),job.id());
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
                    raw_payload=EXCLUDED.raw_payload || coalesce((
                        SELECT jsonb_object_agg(key,value) FROM jsonb_each(amazon_fba_shipments.raw_payload)
                        WHERE key IN ('feesCheckedAt','itemsCheckedAt','itemEvidence','itemEvidenceVersion','itemEvidenceJob','itemsMetadataRevision')
                    ),'{}'::jsonb),updated_at=now()
                """,job.tenantId(),job.connectionId(),row.path("ShipmentId").asText(),row.path("ShipmentName").asText(null),
                row.path("ShipmentStatus").asText(null),row.path("DestinationFulfillmentCenterId").asText(null),row.path("LabelPrepType").asText(null),creationDate(row),timestamp(row,"LastUpdatedDate"),row.toString());count++;
        }return count;
    }
    private void importShipmentFees(AmazonSyncStore.Job job,ObjectNode cursor,int pages){
        // Tokens belong to an exact query contract; never reuse the legacy per-shipment query token.
        if(cursor.path("feeQueryRevision").asInt()!=2){cursor.remove("feeNext");cursor.put("feeQueryRevision",2);}
        String token=cursor.path("feeNext").asText("");
        String path=financePath(job,token);
        AmazonSpApiClient.ApiResponse response;
        try{response=amazon.get(job.tenantId(),job.connectionId(),path);}
        catch(AmazonSpApiClient.AmazonApiException ex){
            if(ex.status()!=403&&ex.status()!=400)throw ex;
            // A resumed pagination token can be rejected; restart this read-only scan once.
            if(ex.status()==400&&!token.isBlank()&&cursor.path("feeRestartCount").asInt()==0){
                cursor.put("feeRestartCount",1);cursor.remove("feeNext");cursor.put("pages",pages+1);
                tx.executeWithoutResult(s->{scope(job);jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),status='WAITING',available_at=now()+interval '60 seconds',lease_owner=NULL,lease_expires_at=NULL,error_message='Restarting rejected Amazon finance pagination; existing charges retained' WHERE tenant_id=? AND id=?",cursor.toString(),job.tenantId(),job.id());});
                return;
            }
            cursor.put("feeFailureStatus",ex.status());
            // Finance permission is independent of shipment visibility. Continue inventory import.
            cursor.put("feesUnavailable",true);cursor.put("feesComplete",true);cursor.remove("feeNext");cursor.put("pages",pages+1);
            org.slf4j.LoggerFactory.getLogger(FbaInboundSync.class).warn("FBA shipment finance access denied for connection={}; shipment import continues, fees remain unknown",job.connectionId());
            tx.executeWithoutResult(s->{scope(job);jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),status='WAITING',available_at=now()+interval '2 seconds',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),job.tenantId(),job.id());if(cursor.path("pending").isEmpty()&&cursor.path("next").asText("").isBlank())store.complete(job,cursor.path("records").asLong());});
            return;
        }
        if(!response.json().path("payload").path("transactions").isArray())throw new IllegalStateException("Amazon shipment charges response did not contain transactions.");
        cursor.put("consecutiveFailures",0);
        String next=response.json().path("payload").path("nextToken").asText("");
        cursor.put("feeNext",next);cursor.put("pages",pages+1);
        if(next.isBlank())cursor.put("feesComplete",true);
        tx.executeWithoutResult(s->{scope(job);
            long count=store.saveJsonPage(job,"fba-fees-"+pages,normalizer.scopedShipmentFinances(job,response.json()).toString(),response.requestId(),source->normalizer.normalizeShipmentFinances(job,response.json(),source));

            long total=cursor.path("records").asLong()+count;cursor.put("records",total);
            jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb),records_processed=?,status='WAITING',error_code=NULL,error_message=NULL,available_at=now()+?*interval '1 second',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",cursor.toString(),total,Math.max(10,requestDelay(response)),job.tenantId(),job.id());
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
    private void scope(AmazonSyncStore.Job job){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,job.tenantId().toString());
        if(job.leaseOwner()!=null&&jdbc.queryForList("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=? AND status='RUNNING' AND lease_owner=? AND lease_expires_at>clock_timestamp() FOR UPDATE",UUID.class,job.tenantId(),job.id(),job.leaseOwner()).isEmpty())throw new AmazonSyncStore.LostOrderLease();
    }
    static long requestDelay(AmazonSpApiClient.ApiResponse response){return response.rateLimit()==null||!Double.isFinite(response.rateLimit())||response.rateLimit()<=0?3:Math.max(3,(long)Math.ceil(1.0/response.rateLimit()));}
    static String financePath(AmazonSyncStore.Job job,String token){
        // Finances supports date windows, not FBA shipment IDs as an ORDER_ID filter.
        var end=job.windowEnd();var start=job.windowStart().isBefore(end.minus(java.time.Duration.ofDays(180)))?end.minus(java.time.Duration.ofDays(180)):job.windowStart();
        return "/finances/2024-06-19/transactions?postedAfter="+encode(start.toString())+"&postedBefore="+encode(end.toString())+
            ("&marketplaceId="+encode(job.marketplaceId()))+
            (token==null||token.isBlank()?"":"&nextToken="+encode(token));
    }
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
