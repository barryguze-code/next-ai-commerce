package com.nextaicommerce.platform.sync;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The existing async/queue infrastructure, limited to the exact explicitly requested FBA job. */
@Component
public class FbaManualWorker {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final FbaInboundSync inbound;private final AmazonSyncStore store;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    public FbaManualWorker(JdbcTemplate jdbc,TransactionTemplate tx,FbaInboundSync inbound,AmazonSyncStore store){this.jdbc=jdbc;this.tx=tx;this.inbound=inbound;this.store=store;}
    public boolean running(UUID id){return active.contains(id);}
    @Async public void start(UUID tenant,UUID connection,UUID id){
        if(!active.add(id))return;
        try{
            // Finite activation; after restart/budget exhaustion Sync now resumes the stored checkpoint.
            Instant deadline=Instant.now().plus(Duration.ofMinutes(30));
            while(Instant.now().isBefore(deadline)&&!Thread.currentThread().isInterrupted()){
                var job=claim(tenant,connection,id);
                if(job==null){if(!Boolean.TRUE.equals(tx.execute(s->{scope(tenant);return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.id=? AND r.sync_profile='FBA_MANUAL' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs sibling WHERE sibling.tenant_id=j.tenant_id AND sibling.sync_run_id=j.sync_run_id AND sibling.id<>j.id))",Boolean.class,tenant,id);})))break;String state=tx.execute(s->{scope(tenant);return jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",String.class,tenant,id);});if(!List.of("QUEUED","WAITING","RUNNING").contains(state))break;}
                else try{inbound.processPage(job);}catch(AmazonSyncStore.LostOrderLease e){break;}
                catch(RuntimeException e){
                    tx.executeWithoutResult(s->{scope(tenant);var owned=jdbc.queryForList("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=? AND lease_owner=? AND lease_expires_at>clock_timestamp() FOR UPDATE",UUID.class,tenant,id,job.leaseOwner());if(owned.isEmpty())return;
                        int consecutive=jdbc.queryForObject("SELECT coalesce((cursor->>'consecutiveFailures')::int,0)+1 FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",Integer.class,tenant,id);
                        boolean throttled=e instanceof AmazonSpApiClient.AmazonApiException api&&api.status()==429;
                        boolean permanent=e instanceof IllegalStateException||e instanceof AmazonSpApiClient.AmazonApiException api&&(api.status()==401||api.status()==403||api.status()==400);
                        String message="Amazon FBA read "+(throttled?"is waiting for Amazon rate-limit cooldown":"could not complete")+" ("+(e instanceof AmazonSpApiClient.AmazonApiException api?"HTTP "+api.status():e.getClass().getSimpleName())+"). Existing data is retained.";
                        if(permanent||consecutive>=8){
                            jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',failure_count=failure_count+1,error_code=?,error_message=?,completed_at=now(),lease_owner=NULL,lease_expires_at=NULL,cursor=jsonb_set(cursor,'{consecutiveFailures}',to_jsonb(?::int)) WHERE tenant_id=? AND id=?",e.getClass().getSimpleName(),message,consecutive,tenant,id);
                            jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=now(),user_message='FBA read needs attention; existing data retained' WHERE tenant_id=? AND id=?",tenant,job.runId());
                        }else{
                            Duration delay=Duration.ofSeconds(Math.min(900,60L*(1L<<Math.min(4,consecutive-1))));
                            if(e instanceof AmazonSpApiClient.AmazonApiException api&&api.retryAfter()!=null&&api.retryAfter().compareTo(delay)>0)delay=api.retryAfter();
                            jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',available_at=?,failure_count=failure_count+1,error_code=?,error_message=?,lease_owner=NULL,lease_expires_at=NULL,cursor=jsonb_set(cursor,'{consecutiveFailures}',to_jsonb(?::int)) WHERE tenant_id=? AND id=?",java.sql.Timestamp.from(Instant.now().plus(delay)),e.getClass().getSimpleName(),message,consecutive,tenant,id);
                        }
                    });
                }
                try{Thread.sleep(2200);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }finally{active.remove(id);}
    }
    AmazonSyncStore.Job claim(UUID tenant,UUID connection,UUID id){return tx.execute(s->{scope(tenant);
        var jobs=jdbc.query("""
            SELECT j.*,r.window_start,r.window_end,r.sync_profile,c.marketplace_identifier FROM marketplace_sync_jobs j
            JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
            JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='FBA_INBOUND'
              AND r.sync_profile='FBA_MANUAL' AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs sibling WHERE sibling.tenant_id=j.tenant_id AND sibling.sync_run_id=j.sync_run_id AND sibling.id<>j.id) AND c.channel='AMAZON' AND c.status='ACTIVE' AND r.status IN ('QUEUED','RUNNING')
              AND j.available_at<=now() AND ((j.status IN ('QUEUED','WAITING') AND (j.lease_expires_at IS NULL OR j.lease_expires_at<now())) OR (j.status='RUNNING' AND j.lease_expires_at<now()))
            FOR UPDATE OF j SKIP LOCKED
            """,(r,n)->new AmazonSyncStore.Job(id,tenant,r.getObject("sync_run_id",UUID.class),connection,"FBA_INBOUND",r.getString("sync_profile"),r.getString("marketplace_identifier"),r.getTimestamp("window_start").toInstant(),r.getTimestamp("window_end").toInstant(),null,r.getInt("attempt_count"),r.getInt("failure_count"),false,null,"fba-manual-"+UUID.randomUUID()),tenant,connection,id);
        if(jobs.isEmpty())return null;var job=jobs.getFirst();
        jdbc.update("UPDATE marketplace_sync_jobs SET status='RUNNING',lease_owner=?,lease_expires_at=now()+interval '3 minutes',attempt_count=attempt_count+1,started_at=coalesce(started_at,now()) WHERE tenant_id=? AND id=?",job.leaseOwner(),tenant,id);
        jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',started_at=coalesce(started_at,now()) WHERE tenant_id=? AND id=?",tenant,job.runId());return job;
    });}
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
}
