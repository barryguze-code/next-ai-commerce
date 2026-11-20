package com.nextaicommerce.platform.sync;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit FBA-only queue requests. Never enables the recurring or Orders workers. */
@Service
public class FbaManualSync {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final FbaManualWorker worker;private final boolean enabled;
    public FbaManualSync(JdbcTemplate jdbc,TransactionTemplate tx,FbaManualWorker worker,@Value("${app.amazon.fba-manual-enabled:false}") boolean enabled){this.jdbc=jdbc;this.tx=tx;this.worker=worker;this.enabled=enabled;}
    public record State(boolean enabled,boolean running,String status,String message,Instant checkedAt){
        public String lastCheck(){return checkedAt==null?"Amazon shipment check · Not completed": "Last Amazon shipment check · "+java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy · HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(checkedAt);}
    }
    public State state(UUID tenant,UUID connection){return tx.execute(s->{scope(tenant);return jdbc.query("""
        SELECT j.id,j.status,j.error_message,j.completed_at,j.cursor->>'feesUnavailable' fees_unavailable,j.cursor->>'itemsRefreshOnly' items_only,j.cursor->>'parentsOnly' parents_only,j.cursor->'parentImport'->>'coverage' parent_coverage,j.cursor->>'paused' paused,j.cursor->'shipmentRead'->>'unresolvedOpen' unresolved_open,j.lease_expires_at,r.sync_profile FROM marketplace_sync_jobs j
        JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
        WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND'
        ORDER BY j.created_at DESC LIMIT 1
        """,r->{if(!r.next())return new State(enabled,false,"NOT_STARTED","",null);String status=r.getString("status");var lease=r.getTimestamp("lease_expires_at");boolean running=activeState(status,r.getString("sync_profile"),"true".equals(r.getString("paused")),worker.running(r.getObject("id",UUID.class)),lease==null?null:lease.toInstant(),Instant.now());
            String message=running?("true".equals(r.getString("items_only"))?"Refreshing selected shipment items…":"Importing shipments and inbound fees…"):List.of("FAILED","SKIPPED").contains(status)?"Previous Amazon shipment sync could not complete. Existing data is retained.":"";if("true".equals(r.getString("fees_unavailable")))message="Shipments imported; Amazon fees were unavailable. Existing charges are retained.";
            String coverage=r.getString("parent_coverage");boolean parentsOnly="true".equals(r.getString("parents_only"));
            if(parentsOnly)message=parentMessage(status,coverage);
            if("COMPLETED".equals(status)&&(parentsOnly&&!"API_DISCOVERY_COMPLETE".equals(coverage)||"PARTIAL".equals(coverage)||"UNAVAILABLE".equals(coverage))){status="COMPLETED_WITH_WARNINGS";message=parentMessage(status,coverage);}
            if(!running&&List.of("QUEUED","RUNNING","WAITING").contains(status))message="Shipment sync paused. Sync now resumes the saved checkpoint.";
            if("COMPLETED".equals(status)&&"true".equals(r.getString("unresolved_open"))){status="COMPLETED_WITH_WARNINGS";message="Some older shipment updates could not be verified. Existing data is retained.";}
            return new State(enabled&&!running,running,status,message==null?"":message,r.getTimestamp("completed_at")==null?null:r.getTimestamp("completed_at").toInstant());},tenant,connection);});}
    static boolean activeState(String status,String profile,boolean paused,boolean workerActive,Instant lease,Instant now){
        if(!List.of("QUEUED","RUNNING","WAITING").contains(status))return false;
        if(!"FBA_MANUAL".equals(profile))return !paused;
        // A crashed manual activation has no resident worker; an unexpired claim still owns its fence.
        return workerActive||!paused&&lease!=null&&lease.isAfter(now);
    }
    static String parentMessage(String status,String coverage){
        if(List.of("QUEUED","RUNNING","WAITING").contains(status))return "Refreshing parent shipment links…";
        if("COMPLETED".equals(status)&&"API_DISCOVERY_COMPLETE".equals(coverage))return "Parent shipment links refreshed.";
        if("PARTIAL".equals(coverage))return "Some parent shipment links could not be verified. Existing links are retained.";
        return "Parent shipment links could not be refreshed. Existing links are retained.";
    }
    private void resumePaused(UUID tenant,UUID id){
        var json=new tools.jackson.databind.ObjectMapper();
        var saved=jdbc.query("SELECT cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND id=? AND cursor->>'paused'='true' FOR UPDATE",(r,n)->(tools.jackson.databind.node.ObjectNode)json.readTree(r.getString(1)),tenant,id);
        if(saved.isEmpty())return;var cursor=saved.getFirst();boolean budget=FbaShipmentReadPlan.resume(cursor,Instant.now());
        jdbc.update("UPDATE marketplace_sync_jobs SET cursor=?::jsonb,available_at=CASE WHEN ? THEN now() ELSE available_at END WHERE tenant_id=? AND id=?",cursor.toString(),budget,tenant,id);
    }
    public UUID request(UUID tenant,UUID connection){
        if(!enabled)throw new IllegalStateException("FBA reads are disabled in this environment.");
        UUID job=tx.execute(s->{scope(tenant);
            lockActiveStore(tenant,connection);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND' AND j.status IN ('QUEUED','WAITING','RUNNING') AND r.status IN ('QUEUED','RUNNING') AND (r.sync_profile IS DISTINCT FROM 'FBA_MANUAL' OR j.cursor->>'itemsRefreshOnly'='true'))",Boolean.class,tenant,connection)))throw new IllegalStateException("An existing Amazon synchronization is importing FBA shipments. Let it finish before starting another.");
            var existing=jdbc.query("SELECT j.id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND r.sync_profile='FBA_MANUAL' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs sibling WHERE sibling.tenant_id=j.tenant_id AND sibling.sync_run_id=j.sync_run_id AND sibling.id<>j.id) AND j.job_type='FBA_INBOUND' AND j.status IN ('QUEUED','WAITING','RUNNING') AND r.status IN ('QUEUED','RUNNING') ORDER BY j.created_at LIMIT 1",(r,n)->r.getObject(1,UUID.class),tenant,connection);
            if(!existing.isEmpty()){
                resumePaused(tenant,existing.getFirst());return existing.getFirst();
            }
            // Explicit retry resumes only a saved FBA throttle checkpoint, never auth/data failures.
            var paused=jdbc.query("SELECT j.id,j.sync_run_id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND r.sync_profile='FBA_MANUAL' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs sibling WHERE sibling.tenant_id=j.tenant_id AND sibling.sync_run_id=j.sync_run_id AND sibling.id<>j.id) AND j.job_type='FBA_INBOUND' AND j.cursor->>'itemsRefreshOnly' IS DISTINCT FROM 'true' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs newer WHERE newer.tenant_id=j.tenant_id AND newer.marketplace_connection_id=j.marketplace_connection_id AND newer.job_type='FBA_INBOUND' AND newer.created_at>j.created_at) AND j.status='FAILED' AND r.status='COMPLETED_WITH_WARNINGS' AND j.error_message LIKE '%HTTP 429%' AND j.completed_at>now()-interval '48 hours' ORDER BY j.created_at DESC LIMIT 1",(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},tenant,connection);
            if(!paused.isEmpty()){
                UUID[] checkpoint=paused.getFirst();
                jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',completed_at=NULL,available_at=now()+interval '60 seconds',error_message='Waiting for Amazon rate-limit cooldown',lease_owner=NULL,lease_expires_at=NULL,cursor=jsonb_set(cursor,'{consecutiveFailures}','0'::jsonb) WHERE tenant_id=? AND id=?",tenant,checkpoint[0]);
                jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',completed_at=NULL,user_message='Resuming saved FBA checkpoint after Amazon cooldown' WHERE tenant_id=? AND id=?",tenant,checkpoint[1]);
                return checkpoint[0];
            }
            // One owner-triggered finance-only recovery for a previously unavailable scan.
            var unavailable=jdbc.query("SELECT j.id,j.sync_run_id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND r.sync_profile='FBA_MANUAL' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs sibling WHERE sibling.tenant_id=j.tenant_id AND sibling.sync_run_id=j.sync_run_id AND sibling.id<>j.id) AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs newer WHERE newer.tenant_id=j.tenant_id AND newer.marketplace_connection_id=j.marketplace_connection_id AND newer.job_type='FBA_INBOUND' AND newer.created_at>j.created_at) AND j.job_type='FBA_INBOUND' AND j.status='COMPLETED' AND cursor->>'feesUnavailable'='true' AND coalesce((cursor->>'feeRestartCount')::int,0)=0 AND j.completed_at<now()-interval '5 minutes' AND j.completed_at>now()-interval '48 hours' ORDER BY j.created_at DESC LIMIT 1",(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},tenant,connection);
            if(!unavailable.isEmpty()){
                UUID[] checkpoint=unavailable.getFirst();
                jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',completed_at=NULL,available_at=now()+interval '60 seconds',error_message='Restarting unavailable Amazon finance scan',lease_owner=NULL,lease_expires_at=NULL,cursor=(cursor-ARRAY['feeNext','feesComplete','feesUnavailable','feeFailureStatus']::text[])||'{\"feeRestartCount\":1,\"consecutiveFailures\":0}'::jsonb WHERE tenant_id=? AND id=?",tenant,checkpoint[0]);
                jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',completed_at=NULL,user_message='Resuming FBA fee scan; shipment quantities retained' WHERE tenant_id=? AND id=?",tenant,checkpoint[1]);
                return checkpoint[0];
            }
            Boolean recent=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type='FBA_INBOUND' AND cursor->>'itemsRefreshOnly' IS DISTINCT FROM 'true' AND status='COMPLETED' AND completed_at>now()-interval '5 minutes')",Boolean.class,tenant,connection);
            if(Boolean.TRUE.equals(recent))throw new IllegalStateException("Amazon shipments were recently synced. Try again in five minutes.");
            Instant end=Instant.now().minusSeconds(180),start=end.minus(Duration.ofDays(180));
            UUID run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end,current_stage,user_message) VALUES (?,?,'RECONCILIATION','FBA_MANUAL',?,?,'FBA_INBOUND','Importing Amazon shipments and inbound fees') RETURNING id",UUID.class,tenant,connection,Timestamp.from(start),Timestamp.from(end));
            return jdbc.queryForObject("INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,required_for_ready) VALUES (?,?,?,'FBA_INBOUND',1,false) RETURNING id",UUID.class,tenant,connection,run);
        });
        worker.start(tenant,connection,job);return job;
    }
    /** Explicit relationship-only read. Existing item-only exclusion also prevents depletion dispatch. */
    public UUID requestParents(UUID tenant,UUID connection){
        if(!enabled)throw new IllegalStateException("FBA reads are disabled in this environment.");
        UUID job=tx.execute(s->{scope(tenant);lockActiveStore(tenant,connection);
            var active=jdbc.query("SELECT j.id,j.cursor->>'parentsOnly' parent_only FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND' AND j.status IN ('QUEUED','WAITING','RUNNING') AND r.status IN ('QUEUED','RUNNING') FOR UPDATE OF j",(r,n)->Map.entry(r.getObject(1,UUID.class),"true".equals(r.getString(2))),tenant,connection);
            if(active.size()==1&&active.getFirst().getValue()){resumePaused(tenant,active.getFirst().getKey());return active.getFirst().getKey();}
            if(!active.isEmpty())throw new IllegalStateException("An existing FBA read is running; let it finish before refreshing parent links.");
            var paused=jdbc.query("SELECT j.id,j.sync_run_id FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND' AND r.sync_profile='FBA_MANUAL' AND j.cursor->>'parentsOnly'='true' AND j.status='FAILED' AND j.error_message LIKE '%HTTP 429%' AND j.completed_at>now()-interval '48 hours' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs newer WHERE newer.tenant_id=j.tenant_id AND newer.marketplace_connection_id=j.marketplace_connection_id AND newer.job_type='FBA_INBOUND' AND newer.created_at>j.created_at) ORDER BY j.created_at DESC LIMIT 1 FOR UPDATE OF j",(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},tenant,connection);
            if(!paused.isEmpty()){
                var previous=paused.getFirst();
                jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',completed_at=NULL,error_code=NULL,error_message=NULL,available_at=now(),cursor=jsonb_set(cursor,'{consecutiveFailures}','0'::jsonb) WHERE tenant_id=? AND id=?",tenant,previous[0]);
                jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',completed_at=NULL WHERE tenant_id=? AND id=?",tenant,previous[1]);return previous[0];
            }
            UUID run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end,current_stage,user_message) VALUES (?,?,'RECONCILIATION','FBA_MANUAL',now(),now(),'FBA_INBOUND','Reading Amazon parent relationships only') RETURNING id",UUID.class,tenant,connection);
            return jdbc.queryForObject("INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,required_for_ready,cursor) VALUES (?,?,?,'FBA_INBOUND',1,false,'{\"parentsOnly\":true,\"itemsRefreshOnly\":true,\"shipmentsComplete\":true,\"feesComplete\":true}'::jsonb) RETURNING id",UUID.class,tenant,connection,run);
        });worker.start(tenant,connection,job);return job;
    }
    public UUID requestItems(UUID tenant, UUID connection, List<String> shipments) {
        if (!enabled) throw new IllegalStateException("FBA reads are disabled in this environment.");
        if (shipments == null || shipments.isEmpty() || shipments.size() > 25)
            throw new IllegalArgumentException("Choose one to 25 shipments.");
        var selected = new TreeSet<String>();
        for (String shipment : shipments) {
            if (shipment == null || shipment.isBlank() || shipment.length() > 80)
                throw new IllegalArgumentException("Choose valid shipment IDs.");
            selected.add(shipment);
        }
        String[] ids = selected.toArray(String[]::new);
        UUID job = tx.execute(status -> {
            scope(tenant);
            lockActiveStore(tenant, connection);
            // Explicit item refresh can recover an abandoned, dedicated FBA run after
            // its lease and cooldown expire. Never retire a live lease, retry wait,
            // or a run containing other synchronization jobs. Checkpoints are retained.
            var abandoned = jdbc.query("""
                SELECT j.id,j.sync_run_id FROM marketplace_sync_jobs j
                JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
                WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND'
                  AND j.status='RUNNING' AND r.status='RUNNING'
                  AND r.sync_profile IN ('FBA_INBOUND','FBA_MANUAL')
                  AND j.lease_expires_at<clock_timestamp()-interval '5 minutes'
                  AND j.available_at<=clock_timestamp()
                  AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs sibling
                    WHERE sibling.tenant_id=j.tenant_id AND sibling.sync_run_id=j.sync_run_id AND sibling.id<>j.id)
                FOR UPDATE OF j,r
                """,(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},tenant,connection);
            for(var stale:abandoned){
                if(worker.running(stale[0]))continue;
                jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',completed_at=now(),lease_owner=NULL,lease_expires_at=NULL,error_message='Expired FBA worker superseded by explicit shipment-item refresh' WHERE tenant_id=? AND id=?",tenant,stale[0]);
                jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=now(),user_message='Expired FBA worker superseded by explicit shipment-item refresh' WHERE tenant_id=? AND id=?",tenant,stale[1]);
            }
            boolean busy = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM marketplace_sync_jobs j
                  JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
                  WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FBA_INBOUND'
                    AND j.status IN ('QUEUED','WAITING','RUNNING') AND r.status IN ('QUEUED','RUNNING'))
                """, Boolean.class, tenant, connection));
            if (busy) throw new IllegalStateException("An FBA sync is already in progress. Let it finish before refreshing shipment items.");
            var found = jdbc.queryForList("""
                SELECT shipment_id FROM amazon_fba_shipments
                WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=ANY(?::text[])
                ORDER BY shipment_id FOR SHARE
                """, String.class, tenant, connection, ids);
            if (found.size() != ids.length)
                throw new IllegalArgumentException("One or more shipments are unavailable in this store.");
            UUID run = jdbc.queryForObject("""
                INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,
                    window_start,window_end,current_stage,user_message)
                VALUES (?,?,'RECONCILIATION','FBA_MANUAL',now(),now(),'FBA_INBOUND','Refreshing selected shipment items')
                RETURNING id
                """, UUID.class, tenant, connection);
            return jdbc.queryForObject("""
                INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,
                    sequence_number,required_for_ready,cursor)
                VALUES (?,?,?,'FBA_INBOUND',1,false,jsonb_build_object('pending',to_jsonb(?::text[]),
                    'next','','itemNext','','pages',0,'feesComplete',true,'itemsRefreshOnly',true))
                RETURNING id
                """, UUID.class, tenant, connection, run, ids);
        });
        worker.start(tenant, connection, job);
        return job;
    }
    private void lockActiveStore(UUID tenant, UUID connection) {
        var active=jdbc.query("SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='AMAZON' AND status='ACTIVE' FOR UPDATE",(r,n)->r.getString(1),tenant,connection);
        if(active.isEmpty())throw new IllegalArgumentException("Choose an active Amazon store.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_connection_credentials WHERE tenant_id=? AND marketplace_connection_id=?)",Boolean.class,tenant,connection)))throw new IllegalStateException("Connect Amazon credentials in Local UAT before syncing this store.");
    }
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
}
