package com.nextaicommerce.platform.sync;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Reuses durable existing checkpoints/evidence, always under the caller's tenant transaction. */
class FbaSyncHistory {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    FbaSyncHistory(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    void initialize(AmazonSyncStore.Job job,ObjectNode cursor){
        if(cursor.has("shipmentRead")||!cursor.isEmpty())return; // Legacy cursors keep their exact query/token contract.
        var previous=jdbc.query("""
            SELECT (j.cursor || jsonb_build_object('legacyWindowEnd',r.window_end))::text FROM marketplace_sync_jobs j
            JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND job_type='FBA_INBOUND' AND j.id<>?
              AND cursor->>'itemsRefreshOnly' IS DISTINCT FROM 'true' AND cursor->>'parentsOnly' IS DISTINCT FROM 'true'
              AND (cursor->'shipmentRead'->>'historyComplete'='true' OR
                   (cursor->>'shipmentsComplete'='true' AND coalesce((cursor->>'pages')::int,0)>0))
            ORDER BY j.created_at DESC LIMIT 1
            """,(r,n)->json.readTree(r.getString(1)),job.tenantId(),job.connectionId(),job.id());
        previous=previous.stream().filter(FbaShipmentReadPlan::trustedHistory).toList();
        Instant end=job.windowEnd();
        // Recurring jobs may have a zero-width finance window; shipment freshness is independent.
        if(end.isBefore(Instant.parse("2000-01-01T00:00:00Z")))throw new IllegalStateException("Invalid FBA read window");
        Instant watermark=null;if(!previous.isEmpty())try{watermark=Instant.parse(previous.getFirst().path("shipmentRead").path("coveredThrough").asText());}catch(DateTimeException ignored){try{watermark=Instant.parse(previous.getFirst().path("legacyWindowEnd").asText());}catch(DateTimeException missing){}}
        FbaShipmentReadPlan.initialize(cursor,!previous.isEmpty(),watermark,end);
    }
    boolean nextOpenBatch(AmazonSyncStore.Job job,ObjectNode cursor){
        var plan=cursor.withObject("shipmentRead");
        if(!"OPEN".equals(plan.path("stage").asText())||plan.has("openBatch"))return true;
        var ids=jdbc.query("""
            SELECT shipment_id FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=?
              AND (shipment_status IS NULL OR shipment_status NOT IN ('CLOSED','CANCELLED','DELETED'))
              AND (updated_date IS NULL OR updated_date<?) AND shipment_id>?
            ORDER BY shipment_id LIMIT 50
            """,(r,n)->r.getString(1),job.tenantId(),job.connectionId(),java.sql.Timestamp.from(Instant.parse(plan.path("windowStart").asText())),plan.path("openAfter").asText(""));
        if(ids.isEmpty()){plan.put("stage","DONE");if(!plan.path("unresolvedOpen").asBoolean())plan.put("coveredThrough",plan.path("windowEnd").asText());return false;}
        var batch=plan.putArray("openBatch");ids.forEach(batch::add);plan.putArray("openSeen");plan.put("openAfter",ids.getLast());return true;
    }
    Map<String,JsonNode> metadata(AmazonSyncStore.Job job,JsonNode rows){
        var result=new HashMap<String,JsonNode>();var ids=new ArrayList<String>();
        for(var row:rows){String id=row.path("ShipmentId").asText();if(!id.matches("[A-Za-z0-9-]{1,100}"))throw new IllegalStateException("Invalid Amazon shipment ID");ids.add(id);}
        if(!ids.isEmpty())jdbc.query("SELECT shipment_id,raw_payload::text FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=ANY(string_to_array(?,','))",r->{result.put(r.getString(1),json.readTree(r.getString(2)));},job.tenantId(),job.connectionId(),String.join(",",ids));
        return result;
    }
    Map<String,JsonNode> parentVersions(AmazonSyncStore.Job job){
        var result=new HashMap<String,JsonNode>();
        jdbc.query("SELECT payload::text FROM amazon_source_documents WHERE tenant_id=? AND marketplace_connection_id=? AND source_type='FBA_PARENT_RELATIONSHIPS' AND normalized_at IS NOT NULL ORDER BY received_at,id",r->{
            var doc=json.readTree(r.getString(1));var version=FbaParentPlanImport.verifiedVersion(job,doc);
            if(version!=null)result.put(version.path("inboundPlanId").asText(),version);
        },job.tenantId(),job.connectionId());return result;
    }
    JsonNode parentBaseline(AmazonSyncStore.Job job){
        var rows=jdbc.query("""
            SELECT (j.cursor->'parentImport' || jsonb_build_object('legacyWindowEnd',r.window_end))::text
            FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND' AND j.id<>?
              AND j.cursor->'parentImport'->>'coverage'='API_DISCOVERY_COMPLETE' AND j.cursor->>'parentsComplete'='true'
            ORDER BY j.created_at DESC LIMIT 1
            """,(r,n)->json.readTree(r.getString(1)),job.tenantId(),job.connectionId(),job.id());
        return rows.isEmpty()||!FbaParentPlanImport.complete((ObjectNode)rows.getFirst())?null:rows.getFirst();
    }
    boolean unresolvedOpenParents(AmazonSyncStore.Job job){
        var ids=jdbc.query("SELECT shipment_id FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND (shipment_status IS NULL OR shipment_status NOT IN ('CLOSED','CANCELLED','DELETED'))",(r,n)->r.getString(1),job.tenantId(),job.connectionId());
        if(ids.isEmpty())return false;
        var links=new com.nextaicommerce.platform.fba.FbaShipmentParents(jdbc,json).read(job.tenantId(),job.connectionId(),ids);
        return links.links().size()!=ids.size()||!links.ambiguous().isEmpty();
    }
}
