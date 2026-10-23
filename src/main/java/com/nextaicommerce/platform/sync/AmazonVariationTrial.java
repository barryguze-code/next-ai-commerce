package com.nextaicommerce.platform.sync;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope;

/** One durable, non-renewable local trial. No startup producer or external traffic. */
@Repository
public class AmazonVariationTrial {
    public static final UUID TENANT=UUID.fromString("74aa8c8b-089b-497a-91b4-994b2be8e95b");
    public static final UUID CONNECTION=UUID.fromString("9b72c443-cca7-4845-be40-7cc34339e446");
    public static final String MARKET="ATVPDKIKX0DER", SEED="4D-2PHN-6KX3";
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final boolean enabled;
    public AmazonVariationTrial(JdbcTemplate jdbc,TransactionTemplate tx,
            @Value("${app.amazon.variation.operator-enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.tx=tx;this.enabled=enabled;
    }
    static void validate(Scope scope,Collection<String> seeds) {
        if(!TENANT.equals(scope.tenant())||!CONNECTION.equals(scope.connection())||!MARKET.equals(scope.marketplace())
                ||seeds.size()!=1||!seeds.contains(SEED))throw new IllegalArgumentException("Only the approved KARACA seed and scope are permitted");
    }
    private void scope(){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,TENANT.toString());}
    public UUID start(UUID key,AmazonVariationRelationshipJobs jobs) {
        if(!enabled)throw new IllegalStateException("Trial operator controls are disabled");
        Objects.requireNonNull(key,"Idempotency key is required");
        return tx.execute(s->{scope();
            jdbc.queryForObject("SELECT id FROM marketplace_connections WHERE tenant_id=? AND id=? AND marketplace_identifier=? AND status='ACTIVE' AND channel='AMAZON' FOR UPDATE",UUID.class,TENANT,CONNECTION,MARKET);
            var existing=jdbc.queryForList("SELECT job_id,idempotency_key FROM amazon_variation_trial WHERE tenant_id=? AND marketplace_connection_id=?",TENANT,CONNECTION);
            if(!existing.isEmpty()) {
                if(!key.equals(existing.getFirst().get("idempotency_key")))throw new IllegalStateException("This trial already exists; a new key cannot reset it");
                return (UUID)existing.getFirst().get("job_id");
            }
            Integer active=jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type='VARIATION_RELATIONSHIPS' AND status IN ('QUEUED','WAITING','RUNNING')",Integer.class,TENANT,CONNECTION);
            if(active!=null&&active>0)throw new IllegalStateException("An existing relationship job must be resolved before the trial");
            UUID job=jobs.enqueueTrial(new Scope(TENANT,CONNECTION,MARKET),List.of(SEED));
            jdbc.update("""
                INSERT INTO amazon_variation_trial(tenant_id,marketplace_connection_id,marketplace_id,job_id,idempotency_key,seller_identifier,started_at,deadline)
                SELECT tenant_id,id,marketplace_identifier,?,?,seller_identifier,statement_timestamp(),statement_timestamp()+interval '5 minutes'
                FROM marketplace_connections WHERE tenant_id=? AND id=? AND seller_identifier IS NOT NULL AND seller_identifier<>''
                """,job,key,TENANT,CONNECTION);
            if(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_trial WHERE job_id=?",Integer.class,job)!=1)throw new IllegalStateException("Seller identity is required");
            return job;
        });
    }
    boolean enabled(){return enabled;}
    private boolean exactScope(AmazonVariationRelationshipJobs.Lease lease){return TENANT.equals(lease.job().tenantId())&&CONNECTION.equals(lease.job().connectionId())&&MARKET.equals(lease.job().marketplaceId());}
    /** Called with the job row locked; all mutation paths acquire job before trial. */
    boolean allowed(AmazonVariationRelationshipJobs.Lease lease) {
        if(!enabled||!exactScope(lease))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM amazon_variation_trial WHERE job_id=? AND tenant_id=? AND marketplace_connection_id=?
                AND marketplace_id=? AND seller_identifier=? AND state='RUNNING' AND deadline>clock_timestamp())
            """,Boolean.class,lease.job().id(),TENANT,CONNECTION,MARKET,lease.seller()));
    }
    boolean withinDeadline(UUID job){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT deadline>clock_timestamp() FROM amazon_variation_trial WHERE job_id=?",Boolean.class,job));}
    Duration remaining(AmazonVariationRelationshipJobs.Lease lease) {
        if(!enabled||!exactScope(lease))throw new AmazonSpApiClient.VariationDeferredException();
        return tx.execute(status->{scope();
        Double seconds=jdbc.queryForObject("SELECT extract(epoch FROM deadline-clock_timestamp())::float8 FROM amazon_variation_trial WHERE job_id=? AND state='RUNNING'",Double.class,lease.job().id());
        if(seconds==null||seconds<=0)throw new AmazonSpApiClient.VariationDeferredException();
        return Duration.ofMillis(Math.max(1,(long)(seconds*1000)));
        });
    }
    /** Reservation commits before dispatch; an orphaned request halts rather than risking overlap. */
    boolean reserve(AmazonVariationRelationshipJobs.Lease lease,boolean oauth,UUID request) {
        if(!allowed(lease))return false;
        var active=jdbc.queryForMap("SELECT request_id,request_owner,request_fence FROM amazon_variation_trial WHERE job_id=? FOR UPDATE",lease.job().id());
        if(active.get("request_id")!=null){
            if(!lease.owner().equals(active.get("request_owner"))||lease.fence()!=((Number)active.get("request_fence")).intValue())stop(lease.job().id(),"REQUEST_OUTCOME_UNKNOWN");
            return false;
        }
        String column=oauth?"oauth_reserved":"get_reserved";int cap=oauth?2:10;
        int updated=jdbc.update("UPDATE amazon_variation_trial SET "+column+"="+column+"+1,request_id=?,request_owner=?,request_fence=?,request_kind=? WHERE job_id=? AND state='RUNNING' AND deadline>clock_timestamp() AND "+column+"<?",request,lease.owner(),lease.fence(),oauth?"OAUTH":"GET",lease.job().id(),cap);
        if(updated==0)stop(lease.job().id(),oauth?"OAUTH_LIMIT":"GET_LIMIT");
        return updated==1;
    }
    void release(AmazonVariationRelationshipJobs.Lease lease,UUID request){
        if(!exactScope(lease))return;
        tx.executeWithoutResult(s->{scope();
            jdbc.queryForObject("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",UUID.class,TENANT,CONNECTION,lease.job().id());
            jdbc.update("UPDATE amazon_variation_trial SET request_id=NULL,request_owner=NULL,request_fence=NULL,request_kind=NULL WHERE tenant_id=? AND marketplace_connection_id=? AND job_id=? AND request_id=?",TENANT,CONNECTION,lease.job().id(),request);
        });
    }
    void stop(UUID job,String reason) {
        if(jdbc.update("UPDATE amazon_variation_trial SET state=?,finished_at=clock_timestamp() WHERE job_id=? AND state='RUNNING'",reason,job)==0)return;
        jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',error_code=?,completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL WHERE id=? AND tenant_id=? AND job_type=? AND status IN ('QUEUED','WAITING','RUNNING')",reason,job,TENANT,AmazonVariationRelationshipJobs.TYPE);
        jdbc.update("UPDATE marketplace_sync_runs SET status=?,completed_at=clock_timestamp(),user_message=? WHERE tenant_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=? AND tenant_id=?)",reason.equals("CANCELLED")?"CANCELLED":"COMPLETED_WITH_WARNINGS",reason,TENANT,job,TENANT);
    }
    void finish(UUID job,boolean warning,String reason) {
        jdbc.update("UPDATE amazon_variation_trial SET state=?,finished_at=clock_timestamp() WHERE job_id=? AND state='RUNNING'",warning?(reason==null?"UNRESOLVED_EVIDENCE":reason):"COMPLETED",job);
    }
    void expire(UUID job){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_variation_trial WHERE job_id=? AND state='RUNNING' AND deadline<=clock_timestamp())",Boolean.class,job)))stop(job,"DEADLINE");}
    public Map<String,Object> inspect(boolean cancel) {
        return tx.execute(s->{scope();var ids=jdbc.queryForList("SELECT job_id FROM amazon_variation_trial WHERE tenant_id=? AND marketplace_connection_id=?",UUID.class,TENANT,CONNECTION);
            if(ids.isEmpty())return Map.of("state","NOT_STARTED");UUID job=ids.getFirst();
            jdbc.queryForObject("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=? FOR UPDATE",UUID.class,TENANT,job);
            if(cancel)stop(job,"CANCELLED");else expire(job);
            return jdbc.queryForMap("SELECT job_id,state,deadline,get_reserved,oauth_reserved,10-get_reserved AS get_remaining,2-oauth_reserved AS oauth_remaining,request_id IS NOT NULL AS request_in_flight FROM amazon_variation_trial WHERE job_id=?",job);
        });
    }
}
