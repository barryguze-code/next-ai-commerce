package com.nextaicommerce.platform.sync;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit inventory-only reads. Never schedules or invokes Orders, shipments or marketplace writes. */
@Service
public class FbaInventoryRefresh {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ApplicationEventPublisher events;private final ObjectMapper json;private final boolean enabled;
 public FbaInventoryRefresh(JdbcTemplate jdbc,TransactionTemplate tx,ApplicationEventPublisher events,ObjectMapper json,@Value("${app.amazon.fba-manual-enabled:false}") boolean enabled){this.jdbc=jdbc;this.tx=tx;this.events=events;this.json=json;this.enabled=enabled;}
 public record Request(UUID tenant,UUID store,UUID job){}
 public record State(boolean enabled,String status,String message,Instant observedAt){public boolean busy(){return List.of("QUEUED","RUNNING").contains(status);}}
 public State state(UUID tenant,UUID store){return tx.execute(s->{scope(tenant);Timestamp latest=jdbc.queryForObject("SELECT max(snapshot_at) FROM amazon_inventory_snapshots WHERE tenant_id=? AND marketplace_connection_id=?",Timestamp.class,tenant,store);Instant observed=latest==null?null:latest.toInstant();return jdbc.query("""
  SELECT j.status,j.error_message,j.lease_expires_at FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
  WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='INVENTORY_SNAPSHOT' AND r.sync_profile='FBA_MANUAL'
  ORDER BY j.created_at DESC LIMIT 1
  """,r->{if(!r.next())return new State(enabled,"IDLE","",observed);String status=r.getString(1);Timestamp lease=r.getTimestamp(3);if(List.of("QUEUED","RUNNING").contains(status)&&(lease==null||lease.toInstant().isBefore(Instant.now())))status="PAUSED";return new State(enabled,status,"PAUSED".equals(status)?"Inventory refresh stopped. Retry to request a fresh read.":Objects.toString(r.getString(2),""),observed);},tenant,store);});}
 public UUID request(UUID tenant,UUID store){if(!enabled)throw new IllegalStateException("Manual FBA reads are disabled in this environment.");UUID id=tx.execute(s->{scope(tenant);
  var market=jdbc.query("SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='AMAZON' AND status='ACTIVE' FOR UPDATE",(r,n)->r.getString(1),tenant,store);if(market.isEmpty())throw new IllegalArgumentException("Choose an active Amazon store.");
  var active=jdbc.query("SELECT j.id,j.lease_expires_at,r.sync_profile FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='INVENTORY_SNAPSHOT' AND j.status IN ('QUEUED','RUNNING','WAITING') FOR UPDATE OF j",(r,n)->new Object[]{r.getObject(1,UUID.class),r.getTimestamp(2),r.getString(3)},tenant,store);
  for(var a:active){if(!"FBA_MANUAL".equals(a[2]))throw new IllegalStateException("An Amazon inventory collection is already pending.");if(a[1] instanceof Timestamp lease&&lease.toInstant().isAfter(Instant.now()))return (UUID)a[0];jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',completed_at=now(),error_message='Previous inventory read expired; no incomplete data was published.' WHERE tenant_id=? AND id=?",tenant,a[0]);}
  UUID run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end,current_stage,user_message) VALUES (?,?,'RECONCILIATION','FBA_MANUAL',now(),now(),'INVENTORY_SNAPSHOT','Reading Amazon FBA inventory') RETURNING id",UUID.class,tenant,store);
  return jdbc.queryForObject("INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,required_for_ready,lease_owner,lease_expires_at) VALUES (?,?,?,'INVENTORY_SNAPSHOT',1,false,?,now()+interval '15 minutes') RETURNING id",UUID.class,tenant,store,run,"inventory-"+UUID.randomUUID());
 });events.publishEvent(new Request(tenant,store,id));return id;}
 String claim(Request request){return tx.execute(s->{scope(request.tenant());var scopes=jdbc.query("""
  SELECT c.marketplace_identifier FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
  WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='INVENTORY_SNAPSHOT' AND r.sync_profile='FBA_MANUAL' AND c.channel='AMAZON' AND c.status='ACTIVE' AND j.status='QUEUED' AND j.lease_expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs x WHERE x.tenant_id=j.tenant_id AND x.sync_run_id=j.sync_run_id AND x.id<>j.id) FOR UPDATE OF j
  """,(r,n)->r.getString(1),request.tenant(),request.store(),request.job());if(scopes.isEmpty())return null;jdbc.update("UPDATE marketplace_sync_jobs SET status='RUNNING',started_at=now(),attempt_count=attempt_count+1 WHERE tenant_id=? AND id=?",request.tenant(),request.job());return scopes.getFirst();});}
 void failed(Request r){tx.executeWithoutResult(s->{scope(r.tenant());jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',completed_at=now(),error_message='Amazon inventory could not be refreshed. Existing stock is retained; retry the refresh.',lease_expires_at=NULL WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND job_type='INVENTORY_SNAPSHOT' AND status='RUNNING' AND lease_expires_at>clock_timestamp()",r.tenant(),r.store(),r.job());});}
 public int publish(Request r,String market,JsonNode pages){var rows=FbaInventorySnapshot.validate(market,pages);return tx.execute(s->{scope(r.tenant());var owned=jdbc.queryForList("SELECT j.id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='INVENTORY_SNAPSHOT' AND r.sync_profile='FBA_MANUAL' AND c.marketplace_identifier=? AND c.channel='AMAZON' AND j.status='RUNNING' AND j.lease_expires_at>clock_timestamp() FOR UPDATE OF j",UUID.class,r.tenant(),r.store(),r.job(),market);if(owned.isEmpty())throw new IllegalStateException("Inventory read lease expired; existing data retained.");
  String body=pages.toString();String hash;try{hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  UUID source=jdbc.queryForObject("INSERT INTO amazon_source_documents(tenant_id,marketplace_connection_id,sync_job_id,source_type,source_key,content_type,sha256,payload,record_count) VALUES (?,?,?,'INVENTORY_SNAPSHOT','FBA_INVENTORY_API','application/json',?,CAST(? AS jsonb),?) ON CONFLICT(tenant_id,marketplace_connection_id,source_type,sha256) DO UPDATE SET id=amazon_source_documents.id RETURNING id",UUID.class,r.tenant(),r.store(),r.job(),hash,body,rows.size());
  for(var row:rows)jdbc.update("""
   INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fnsku,asin,condition_type,fulfillable_quantity,inbound_working_quantity,inbound_shipped_quantity,inbound_receiving_quantity,reserved_quantity,unfulfillable_quantity,researching_quantity,total_quantity,snapshot_at,raw_payload,source_document_id)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,snapshot_at) DO NOTHING
   """,r.tenant(),r.store(),market,row.sku(),row.fnsku(),row.asin(),row.condition(),row.available(),row.working(),row.shipped(),row.receiving(),row.reserved(),row.unsellable(),row.researching(),row.total(),Timestamp.from(row.observedAt()),row.raw(),source);
  jdbc.update("UPDATE amazon_source_documents SET normalized_at=now() WHERE tenant_id=? AND id=?",r.tenant(),source);
  jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED',completed_at=now(),records_processed=?,lease_owner=NULL,lease_expires_at=NULL,error_message=NULL WHERE tenant_id=? AND id=?",rows.size(),r.tenant(),r.job());
  jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED',completed_at=now(),progress_percent=100,user_message='FBA inventory refreshed' WHERE tenant_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?)",r.tenant(),r.tenant(),r.job());return rows.size();});}
 private void scope(UUID t){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,t.toString());}
}
