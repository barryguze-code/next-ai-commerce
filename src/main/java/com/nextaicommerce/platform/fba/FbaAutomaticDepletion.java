package com.nextaicommerce.platform.fba;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.web.MemberAccessService;

/** Bounded local ledger work after a completed FBA read. No Amazon calls or startup enqueue. */
@Service
public class FbaAutomaticDepletion {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final FbaDepletionRepository depletion;
 private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
 private final MemberAccessService access;private final boolean enabled;
 public FbaAutomaticDepletion(JdbcTemplate jdbc,TransactionTemplate tx,FbaDepletionRepository depletion,MemberAccessService access,
   @Value("${app.fba.depletion.automatic-enabled:false}") boolean enabled){this.jdbc=jdbc;this.tx=tx;this.depletion=depletion;this.access=access;this.enabled=enabled;}
 public boolean enabled(){return enabled;}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private void lock(UUID tenant){jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,tenant.toString());}
 public record Setting(UUID warehouse,String name,boolean automatic,Instant enabledAfter,long revision){}
 public List<Setting> settings(UUID tenant,UUID connection){return tx.execute(st->{scope(tenant);return jdbc.query("""
  SELECT w.id,w.name,coalesce(s.automatic_enabled,false),s.enabled_after,coalesce(s.revision,0)
  FROM warehouses w LEFT JOIN fba_warehouse_depletion_settings s ON s.tenant_id=w.tenant_id AND s.warehouse_id=w.id AND s.marketplace_connection_id=?
  WHERE w.tenant_id=? AND w.status='ACTIVE' ORDER BY w.is_default DESC,lower(w.name)
  """,(r,n)->new Setting(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant(),r.getLong(5)),connection,tenant);});}
 public void configure(UUID tenant,UUID connection,UUID warehouse,String email,boolean automatic,long revision){tx.executeWithoutResult(st->{
  scope(tenant);lock(tenant);if(automatic&&!enabled)throw new IllegalArgumentException("Automatic depletion is off in this environment.");var member=access.current(tenant,email);
  if(!access.activePlatformAdministrator(email)&&(member==null||!Set.of("OWNER","ADMIN").contains(member.role())||member.restricted()&&!member.stores().contains(connection)))throw new org.springframework.security.access.AccessDeniedException("Only an authorized account administrator can change depletion settings.");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouses w JOIN marketplace_connections c ON c.tenant_id=w.tenant_id WHERE w.tenant_id=? AND w.id=? AND w.status='ACTIVE' AND c.id=? AND c.channel='AMAZON' AND c.status='ACTIVE')",Boolean.class,tenant,warehouse,connection)))throw new IllegalArgumentException("Choose an active warehouse and Amazon store.");
  long current=jdbc.queryForObject("SELECT coalesce((SELECT revision FROM fba_warehouse_depletion_settings WHERE tenant_id=? AND marketplace_connection_id=? AND warehouse_id=?),0)",Long.class,tenant,connection,warehouse);
  if(current!=revision)throw new IllegalArgumentException("Settings changed. Reload and try again.");
  jdbc.update("""
   INSERT INTO fba_warehouse_depletion_settings(tenant_id,marketplace_connection_id,warehouse_id,automatic_enabled,enabled_after,updated_by)
   VALUES (?,?,?,?,CASE WHEN ? THEN clock_timestamp() END,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
   ON CONFLICT(tenant_id,marketplace_connection_id,warehouse_id) DO UPDATE SET automatic_enabled=excluded.automatic_enabled,
    enabled_after=CASE WHEN excluded.automatic_enabled AND NOT fba_warehouse_depletion_settings.automatic_enabled THEN clock_timestamp() ELSE fba_warehouse_depletion_settings.enabled_after END,
    updated_by=excluded.updated_by,updated_at=clock_timestamp(),revision=fba_warehouse_depletion_settings.revision+1
   """,tenant,connection,warehouse,automatic,automatic,email);
 });}
 static Instant authoritativeCreation(String value){try{return value==null?null:OffsetDateTime.parse(value).toInstant();}catch(java.time.format.DateTimeParseException e){return null;}}
 private record Candidate(String shipment,UUID warehouse,long revision,Instant cutoff,UUID actor,Instant seen,String created){}
 /** Only a fully completed, tenant/store-qualified FBA read may request ledger work. */
 public UUID enqueue(UUID tenant,UUID connection,UUID importJob){if(!enabled)return null;return tx.execute(st->{scope(tenant);lock(tenant);
  int recorded=jdbc.update("""
   UPDATE marketplace_sync_jobs j SET cursor=CASE WHEN jsonb_exists(j.cursor,'depletionDispatch') THEN j.cursor
    ELSE jsonb_set(j.cursor,'{depletionDispatch}','"PENDING"'::jsonb) END
   FROM marketplace_sync_runs r,marketplace_connections c
   WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='FBA_INBOUND'
    AND j.status='COMPLETED' AND coalesce(j.cursor->>'itemsRefreshOnly','false')<>'true' AND r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
    AND r.status NOT IN ('CANCELLED','FAILED') AND c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
    AND c.status='ACTIVE' AND c.channel='AMAZON'
   """,tenant,connection,importJob);
  return recorded==1?nextBatchLocked(tenant,connection):null;
 });}
 public UUID nextBatch(UUID tenant,UUID connection){if(!enabled)return null;return tx.execute(st->{
  scope(tenant);lock(tenant);return nextBatchLocked(tenant,connection);
 });}
 private UUID nextBatchLocked(UUID tenant,UUID connection){
  recover(tenant,connection);
  var active=jdbc.queryForList("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type='FBA_WAREHOUSE_DEPLETION' AND status IN('QUEUED','WAITING','RUNNING') LIMIT 1",UUID.class,tenant,connection);
  if(!active.isEmpty())return active.getFirst();
  var imports=jdbc.queryForList("""
   SELECT j.id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
   JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
   WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND' AND j.status='COMPLETED'
    AND j.cursor->>'depletionDispatch'='PENDING' AND r.status NOT IN('CANCELLED','FAILED') AND c.status='ACTIVE' AND c.channel='AMAZON'
   ORDER BY j.completed_at,j.id LIMIT 25
   """,UUID.class,tenant,connection);
  for(UUID importJob:imports){
   UUID batch=createBatch(tenant,connection,importJob);
   if(batch!=null)return batch;
   jdbc.update("UPDATE marketplace_sync_jobs SET cursor=jsonb_set(cursor,'{depletionDispatch}','\"DONE\"'::jsonb) WHERE tenant_id=? AND id=?",tenant,importJob);
  }
  return null;
 }
 public boolean pending(UUID tenant,UUID connection){if(!enabled)return false;return tx.execute(st->{scope(tenant);return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND' AND j.status='COMPLETED' AND j.cursor->>'depletionDispatch'='PENDING' AND r.status NOT IN('CANCELLED','FAILED') AND c.status='ACTIVE' AND c.channel='AMAZON')",Boolean.class,tenant,connection));});}
 private UUID createBatch(UUID tenant,UUID connection,UUID importJob){
  var candidates=jdbc.query("""
   SELECT s.shipment_id,w.id,p.revision,p.enabled_after,p.updated_by,s.created_at,
    coalesce(s.raw_payload->>'CreationDate',s.raw_payload->>'ShipmentCreatedDate') explicit_created
   FROM amazon_fba_shipments s JOIN warehouses w ON w.tenant_id=s.tenant_id AND w.status='ACTIVE'
    AND upper(w.country_code)=upper(s.raw_payload#>>'{ShipFromAddress,CountryCode}')
    AND nullif(upper(replace(btrim(w.postal_code),' ','')),'')=upper(replace(btrim(s.raw_payload#>>'{ShipFromAddress,PostalCode}'),' ',''))
   JOIN fba_warehouse_depletion_settings p ON p.tenant_id=s.tenant_id AND p.marketplace_connection_id=s.marketplace_connection_id AND p.warehouse_id=w.id AND p.automatic_enabled
   WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.created_at>p.enabled_after
    AND s.raw_payload->>'itemEvidenceVersion'='1' AND s.raw_payload->>'itemEvidenceJob'=?::text AND s.shipment_status IN('SHIPPED','IN_TRANSIT','DELIVERED','CHECKED_IN','RECEIVING','CLOSED')
    AND NOT EXISTS(SELECT 1 FROM fba_shipment_depletions d WHERE d.tenant_id=s.tenant_id AND d.marketplace_connection_id=s.marketplace_connection_id AND d.shipment_id=s.shipment_id)
    AND NOT EXISTS(SELECT 1 FROM fba_shipment_depletion_attempts a WHERE a.tenant_id=s.tenant_id AND a.marketplace_connection_id=s.marketplace_connection_id AND a.shipment_id=s.shipment_id)
   ORDER BY s.created_at,s.shipment_id LIMIT 25
   """,(r,n)->new Candidate(r.getString(1),r.getObject(2,UUID.class),r.getLong(3),r.getTimestamp(4).toInstant(),r.getObject(5,UUID.class),r.getTimestamp(6).toInstant(),r.getString(7)),tenant,connection,importJob.toString());
  if(candidates.isEmpty())return null;
  UUID run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end,current_stage,user_message) VALUES (?,?,'RECONCILIATION','FBA_MANUAL',now(),now(),'FBA_WAREHOUSE_DEPLETION','Reviewing future FBA depletion') RETURNING id",UUID.class,tenant,connection);
  UUID job=jdbc.queryForObject("INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,required_for_ready,max_attempts) VALUES (?,?,?,'FBA_WAREHOUSE_DEPLETION',1,false,3) RETURNING id",UUID.class,tenant,connection,run);
  for(var c:candidates){
   var created=authoritativeCreation(c.created);String reason=null;FbaDepletionRepository.Preview plan=null;
   if(created==null||!created.isAfter(c.cutoff)||created.isAfter(Instant.now().plusSeconds(300)))reason="Historical or unverified shipment date";
   else {plan=depletion.preview(tenant,connection,c.shipment);if(!Objects.equals(plan.warehouse(),c.warehouse))reason="No unique warehouse ZIP match";else if(plan.lines().stream().anyMatch(FbaDepletionRepository.Line::modified))reason="Adjusted shipment requires manual review";else if(!plan.ready())reason=String.join(" ",plan.blockers());}
   jdbc.update("INSERT INTO fba_shipment_depletion_attempts(tenant_id,marketplace_connection_id,shipment_id,warehouse_id,job_id,import_job_id,source_revision,allocations,setting_revision,enabled_after,actor_id,status,reason,completed_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,CASE WHEN ? THEN clock_timestamp() END) ON CONFLICT(tenant_id,marketplace_connection_id,shipment_id) DO NOTHING",tenant,connection,c.shipment,c.warehouse,job,importJob,plan==null?null:plan.revision(),json.writeValueAsString(plan==null?List.of():plan.allocations()),c.revision,java.sql.Timestamp.from(c.cutoff),c.actor,reason==null?"QUEUED":"NEEDS_REVIEW",reason==null?null:reason.substring(0,Math.min(500,reason.length())),reason!=null);
  }
  return job;
 }
 private void recover(UUID tenant,UUID connection){
  var abandoned=jdbc.query("""
   SELECT j.id,j.sync_run_id,r.status FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
   WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_WAREHOUSE_DEPLETION' AND j.status IN('QUEUED','RUNNING','WAITING')
    AND (r.status IN('CANCELLED','FAILED') OR j.attempt_count>=j.max_attempts AND j.lease_expires_at<clock_timestamp()) FOR UPDATE OF j,r
   """,(r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)},tenant,connection);
  for(var row:abandoned){jdbc.update("UPDATE fba_shipment_depletion_attempts SET status='NEEDS_REVIEW',reason='Automatic depletion stopped; review manually',completed_at=clock_timestamp() WHERE tenant_id=? AND job_id=? AND status='QUEUED'",tenant,row[0]);jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",tenant,row[0]);if(!Set.of("CANCELLED","FAILED").contains(row[2]))jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=clock_timestamp(),user_message='FBA depletion needs review' WHERE tenant_id=? AND id=?",tenant,row[1]);}
 }
 public String claim(UUID tenant,UUID connection,UUID job){if(!enabled)return null;return tx.execute(st->{scope(tenant);lock(tenant);recover(tenant,connection);String owner="fba-depletion-"+UUID.randomUUID();var rows=jdbc.queryForList("""
  SELECT j.id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
  WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='FBA_WAREHOUSE_DEPLETION' AND r.sync_profile='FBA_MANUAL' AND r.status IN('QUEUED','RUNNING')
   AND j.attempt_count<j.max_attempts AND j.available_at<=clock_timestamp()
   AND ((j.status IN('QUEUED','WAITING') AND (j.lease_expires_at IS NULL OR j.lease_expires_at<clock_timestamp())) OR j.status='RUNNING' AND j.lease_expires_at<clock_timestamp()) FOR UPDATE OF j,r SKIP LOCKED
  """,UUID.class,tenant,connection,job);if(rows.isEmpty())return null;
  jdbc.update("UPDATE marketplace_sync_jobs SET status='RUNNING',attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=clock_timestamp()+interval '3 minutes',started_at=coalesce(started_at,clock_timestamp()) WHERE tenant_id=? AND id=?",owner,tenant,job);
  if(jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING' WHERE tenant_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?) AND status IN ('QUEUED','RUNNING')",tenant,tenant,job)!=1)throw new IllegalStateException("Depletion run is no longer claimable");
  return owner;
 });}
 public boolean processOne(UUID tenant,UUID connection,UUID job,String owner){if(!enabled)return false;return tx.execute(st->{scope(tenant);lock(tenant);owned(tenant,connection,job,owner);
  var attempts=jdbc.query("SELECT a.*,u.email FROM fba_shipment_depletion_attempts a JOIN app_users u ON u.id=a.actor_id WHERE a.tenant_id=? AND a.marketplace_connection_id=? AND a.job_id=? AND a.status='QUEUED' ORDER BY a.created_at,a.id LIMIT 1 FOR UPDATE OF a",(r,n)->new Object[]{r.getObject("id",UUID.class),r.getString("shipment_id"),r.getObject("warehouse_id",UUID.class),r.getLong("setting_revision"),r.getTimestamp("enabled_after").toInstant(),r.getString("email"),r.getString("source_revision"),r.getString("allocations")},tenant,connection,job);
  if(attempts.isEmpty()){finish(tenant,job,owner);return false;}var a=attempts.getFirst();String shipment=(String)a[1],email=(String)a[5],reason=null;UUID operation=null;
  var member=access.current(tenant,email);boolean authorized=access.activePlatformAdministrator(email)||(member!=null&&Set.of("OWNER","ADMIN","OPERATOR").contains(member.role())&&(!member.restricted()||member.stores().contains(connection)));
  boolean policy=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_warehouse_depletion_settings p JOIN marketplace_connections c ON c.tenant_id=p.tenant_id AND c.id=p.marketplace_connection_id WHERE p.tenant_id=? AND p.marketplace_connection_id=? AND p.warehouse_id=? AND p.automatic_enabled AND p.revision=? AND p.enabled_after=? AND c.status='ACTIVE')",Boolean.class,tenant,connection,a[2],a[3],java.sql.Timestamp.from((Instant)a[4])));
  if(!authorized)reason="Account or store access changed";else if(!policy)reason="Depletion settings changed";
  if(reason==null){var preview=depletion.preview(tenant,connection,shipment);
   var source=jdbc.queryForMap("SELECT created_at,coalesce(raw_payload->>'CreationDate',raw_payload->>'ShipmentCreatedDate') explicit_created FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",tenant,connection,shipment);
   Instant created=authoritativeCreation((String)source.get("explicit_created"));
   if(created==null||created.isAfter(Instant.now().plusSeconds(300))||!created.isAfter((Instant)a[4])||!((java.sql.Timestamp)source.get("created_at")).toInstant().isAfter((Instant)a[4]))reason="Historical or unverified shipment date";
   else if(!Objects.equals(preview.warehouse(),a[2]))reason="Warehouse match changed";
   else if(preview.depleted())reason="Already depleted";
   else if(preview.lines().stream().anyMatch(FbaDepletionRepository.Line::modified))reason="Adjusted shipment requires manual review";
   else if(!preview.ready())reason=String.join(" ",preview.blockers());
   else if(!Objects.equals(preview.revision(),a[6]))reason="Stock, shipment or mapping changed; review manually";
   else {var allocations=new ArrayList<FbaDepletionRepository.Allocation>();for(var value:json.readTree((String)a[7]))allocations.add(new FbaDepletionRepository.Allocation(UUID.fromString(value.path("layer").asText()),value.path("quantity").decimalValue()));operation=depletion.deplete(tenant,connection,shipment,email,(UUID)a[0],(String)a[6],allocations);}
  }
  owned(tenant,connection,job,owner);
  jdbc.update("UPDATE fba_shipment_depletion_attempts SET status=?,reason=?,depletion_id=?,completed_at=clock_timestamp() WHERE tenant_id=? AND id=?",operation==null?"NEEDS_REVIEW":"COMPLETED",reason==null?null:reason.substring(0,Math.min(500,reason.length())),operation,tenant,a[0]);
  jdbc.update("UPDATE marketplace_sync_jobs SET records_processed=records_processed+1,lease_expires_at=clock_timestamp()+interval '3 minutes' WHERE tenant_id=? AND id=? AND lease_owner=?",tenant,job,owner);return true;
 });}
 private void owned(UUID tenant,UUID connection,UUID job,String owner){if(owner==null||jdbc.queryForList("SELECT j.id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='FBA_WAREHOUSE_DEPLETION' AND j.status='RUNNING' AND r.status='RUNNING' AND j.lease_owner=? AND j.lease_expires_at>clock_timestamp() FOR UPDATE OF j,r",UUID.class,tenant,connection,job,owner).isEmpty())throw new IllegalStateException("Depletion worker lease expired");}
 private void finish(UUID tenant,UUID job,String owner){boolean blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_shipment_depletion_attempts WHERE tenant_id=? AND job_id=? AND status='NEEDS_REVIEW')",Boolean.class,tenant,job));jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=? AND lease_owner=?",tenant,job,owner);jdbc.update("UPDATE marketplace_sync_runs SET status=?,completed_at=clock_timestamp(),progress_percent=100,user_message=? WHERE tenant_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?)",blocked?"COMPLETED_WITH_WARNINGS":"COMPLETED",blocked?"FBA depletion needs review":"FBA depletion completed",tenant,tenant,job);}
 public void failed(UUID tenant,UUID connection,UUID job,String owner){tx.executeWithoutResult(st->{scope(tenant);owned(tenant,connection,job,owner);jdbc.update("UPDATE fba_shipment_depletion_attempts SET status='NEEDS_REVIEW',reason='Depletion interrupted; review manually',completed_at=clock_timestamp() WHERE tenant_id=? AND job_id=? AND status='QUEUED'",tenant,job);finish(tenant,job,owner);});}
}
