package com.nextaicommerce.platform.sync;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope;

/** Relationship-specific fencing/pacing over the existing queue, not a second queue. */
@Repository
public class AmazonVariationRelationshipJobs {
    public static final String TYPE="VARIATION_RELATIONSHIPS";
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;private final Clock clock;
    public AmazonVariationRelationshipJobs(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json){this(jdbc,tx,json,Clock.systemUTC());}
    public AmazonVariationRelationshipJobs(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AmazonVariationTrial trial){
        this(jdbc,tx,json,Clock.systemUTC());this.trial=Objects.requireNonNull(trial);
    }
    @Autowired public AmazonVariationRelationshipJobs(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AmazonVariationTrial trial,AmazonVariationCoverageGrant coverage){
        this(jdbc,tx,json,trial);this.coverage=Objects.requireNonNull(coverage);
    }
    private AmazonVariationCoverageGrant coverage;
    private boolean account(Lease lease){return coverage!=null&&coverage.covers(lease);}
    public UUID startCoverage(UUID key){if(coverage==null||trial==null||!trial.enabled())throw new IllegalStateException("Guarded coverage wiring is required");return coverage.start(key);}
    public void requestUncertain(Lease lease,UUID request){if(account(lease))coverage.uncertain(lease);else requestCompleted(lease,request);}
    public AmazonVariationRelationshipJobs(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,Clock clock){this.jdbc=jdbc;this.tx=tx;this.json=json;this.clock=clock;}
    public record Lease(AmazonSyncStore.Job job,String owner,int fence,JsonNode cursor,String seller) {}
    public Instant now(){return clock.instant();}
    static Timestamp time(Instant value){return Timestamp.from(value);}
    // Clock constructor retains the pre-trial synthetic A2 harness; Spring always requires the guarded constructor.
    private AmazonVariationTrial trial;
    public UUID startTrial(UUID key){if(trial==null)throw new IllegalStateException("Guarded trial wiring is required");return trial.start(key,this);}
    public java.time.Duration trialRemaining(Lease lease){return account(lease)?coverage.remaining(lease):trial==null?java.time.Duration.ofSeconds(45):trial.remaining(lease);}
    public boolean reserveOAuth(Lease lease,UUID request){return trial==null||Boolean.TRUE.equals(owned(lease,j->account(lease)?coverage.reserve(lease,true,request):trial.reserve(lease,true,request)));}
    public void requestCompleted(Lease lease,UUID request){if(account(lease))coverage.release(lease,request);else if(trial!=null)trial.release(lease,request);}
    boolean guarded(){return trial!=null;}
    boolean familyAllowed(Lease lease,String sku,JsonNode response){
        if(trial==null)return true;
        Set<String> identities=new HashSet<>();lease.cursor().path("pending").forEach(v->identities.add(v.asText()));lease.cursor().path("done").forEach(v->identities.add(v.asText()));
        if(sku.equals(response.path("sku").asText()))for(JsonNode group:response.path("relationships"))if(lease.job().marketplaceId().equals(group.path("marketplaceId").asText()))
            for(JsonNode relation:group.path("relationships"))if("VARIATION".equals(relation.path("type").asText()))for(String field:List.of("parentSkus","childSkus"))
                if(relation.path(field).isArray())for(JsonNode member:relation.path(field))if(member.isString()&&!member.asText().isEmpty())identities.add(member.asText());
        return identities.size()<=(account(lease)?AmazonVariationCoverageGrant.IDENTITIES:4);
    }
    void stopFamily(Lease lease){stop(lease,"FAMILY_LIMIT");}
    private void stop(Lease lease,String reason){if(account(lease))coverage.stop(lease,reason);else if(trial!=null)trial.stop(lease.job().id(),reason);}
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    public UUID enqueue(Scope scope,Collection<String> seeds){
        if(trial!=null)throw new IllegalStateException("Use the bounded trial operator start");
        return enqueueTrial(scope,seeds);
    }
    UUID enqueueTrial(Scope scope,Collection<String> seeds){
        if(trial!=null)AmazonVariationTrial.validate(scope,seeds);
        if(seeds.isEmpty()||seeds.size()>100)throw new IllegalArgumentException("Provide 1–100 exact SKUs");
        for(String sku:seeds)if(sku==null||sku.isEmpty()||sku.codePointCount(0,sku.length())>240)throw new IllegalArgumentException("Invalid SKU");
        return tx.execute(s->{scope(scope.tenant());
            var valid=jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND id=? AND marketplace_identifier=? AND channel='AMAZON' AND status='ACTIVE' FOR UPDATE",scope.tenant(),scope.connection(),scope.marketplace());
            if(valid.isEmpty())throw new IllegalArgumentException("Invalid active Amazon scope");
            var existing=jdbc.queryForList("SELECT id,cursor FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND status IN ('QUEUED','WAITING','RUNNING')",scope.tenant(),scope.connection(),TYPE);
            if(!existing.isEmpty()){
                var cursor=json.readTree(existing.getFirst().get("cursor").toString());Set<String> covered=new HashSet<>();
                cursor.path("pending").forEach(v->covered.add(v.asText()));cursor.path("done").forEach(v->covered.add(v.asText()));
                if(!covered.containsAll(seeds))throw new IllegalStateException("An active relationship job must finish before additional SKUs can be requested");
                return (UUID)existing.getFirst().get("id");
            }
            UUID run=UUID.randomUUID(),job=UUID.randomUUID();Instant now=now();
            jdbc.update("""
                INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end)
                VALUES(?,?,?,'RECONCILIATION',?,?,?)
                """,run,scope.tenant(),scope.connection(),TYPE,time(now),time(now));
            String cursor=json.writeValueAsString(Map.of("pending",new LinkedHashSet<>(seeds),"done",List.of(),"marketplace",scope.marketplace()));
            jdbc.update("""
                INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,
                    sequence_number,required_for_ready,cursor,available_at)
                VALUES(?,?,?,?,?,1,false,CAST(? AS jsonb),?)
                """,job,scope.tenant(),run,scope.connection(),TYPE,cursor,time(now));return job;
        });
    }
    private final java.util.concurrent.atomic.AtomicInteger rotation=new java.util.concurrent.atomic.AtomicInteger();
    public Lease claim(String worker){
        if(trial!=null){if(!trial.enabled())return null;trial.inspect(false);}
        var tenants=new ArrayList<>(jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(r,n)->r.getObject(1,UUID.class)));
        if(!tenants.isEmpty())Collections.rotate(tenants,Math.floorMod(rotation.getAndIncrement(),tenants.size()));
        for(UUID tenant:tenants){
            Lease lease=tx.execute(s->{scope(tenant);Instant now=now();
                var rows=jdbc.queryForList("""
                    SELECT j.*,c.marketplace_identifier,c.seller_identifier,r.window_start,r.window_end
                    FROM marketplace_sync_jobs j JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
                    JOIN marketplace_sync_runs r ON r.id=j.sync_run_id AND r.tenant_id=j.tenant_id
                    WHERE j.tenant_id=? AND j.job_type=? AND c.status='ACTIVE' AND c.channel='AMAZON'
                      /*TRIAL_SCOPE*/
                      AND j.cursor->>'marketplace'=c.marketplace_identifier
                      AND ((j.status IN ('QUEUED','WAITING') AND j.available_at<=? AND (j.lease_expires_at IS NULL OR j.lease_expires_at<=?))
                        OR (j.status='RUNNING' AND j.lease_expires_at<=?))
                    ORDER BY j.available_at,j.created_at,j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1
                    """.replace("/*TRIAL_SCOPE*/",trial==null?"":"AND (EXISTS(SELECT 1 FROM amazon_variation_trial t WHERE t.job_id=j.id AND t.tenant_id=j.tenant_id AND t.marketplace_connection_id=j.marketplace_connection_id AND t.marketplace_id=c.marketplace_identifier AND t.seller_identifier=c.seller_identifier AND t.state='RUNNING' AND t.deadline>clock_timestamp())"+(coverage!=null&&coverage.enabled()?" OR j.cursor->'approval'->'contract'->>'approval'='"+coverage.approval()+"' AND j.tenant_id='"+coverage.scope().tenant()+"' AND j.marketplace_connection_id='"+coverage.scope().connection()+"'":"")+")"),tenant,TYPE,time(now),time(now),time(now));
                if(rows.isEmpty())return null;var r=rows.getFirst();UUID id=(UUID)r.get("id");String owner=worker+":"+UUID.randomUUID();
                int fence=((Number)r.get("attempt_count")).intValue()+1;
                jdbc.update("UPDATE marketplace_sync_jobs SET status='RUNNING',attempt_count=?,lease_owner=?,lease_expires_at=?,started_at=coalesce(started_at,?) WHERE tenant_id=? AND id=?",fence,owner,time(now.plusSeconds(120)),time(now),tenant,id);
                jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',started_at=coalesce(started_at,?),current_stage=? WHERE tenant_id=? AND id=?",time(now),TYPE,tenant,r.get("sync_run_id"));
                var job=new AmazonSyncStore.Job(id,tenant,(UUID)r.get("sync_run_id"),(UUID)r.get("marketplace_connection_id"),TYPE,TYPE,
                    (String)r.get("marketplace_identifier"),((Timestamp)r.get("window_start")).toInstant(),((Timestamp)r.get("window_end")).toInstant(),null,fence,
                    ((Number)r.get("failure_count")).intValue(),false,null);
                var result=new Lease(job,owner,fence,json.readTree(r.get("cursor").toString()),(String)r.get("seller_identifier"));
                if(account(result)&&!coverage.allowed(result))return null;
                if(fence>10000){finishInside(result,true,"JOB_CLAIM_LIMIT");return null;}return result;
            });if(lease!=null)return lease;
        }return null;
    }
    public <T> T owned(Lease lease,Function<JdbcTemplate,T> action){return tx.execute(s->{scope(lease.job().tenantId());
        var rows=jdbc.queryForList("""
            SELECT j.id FROM marketplace_sync_jobs j JOIN marketplace_connections c
              ON c.id=j.marketplace_connection_id AND c.tenant_id=j.tenant_id
            WHERE j.tenant_id=? AND j.id=? AND j.marketplace_connection_id=? AND j.job_type=?
              AND j.status='RUNNING' AND j.lease_owner=? AND j.attempt_count=? AND j.lease_expires_at>?
              AND c.status='ACTIVE' AND c.channel='AMAZON' AND c.marketplace_identifier=? AND c.seller_identifier=?
            FOR UPDATE OF j
            """,lease.job().tenantId(),lease.job().id(),lease.job().connectionId(),TYPE,lease.owner(),lease.fence(),time(now()),lease.job().marketplaceId(),lease.seller());
        if(rows.isEmpty())return null;
        if(account(lease)){if(!coverage.allowed(lease))return null;}
        else if(trial!=null){trial.expire(lease.job().id());if(!trial.allowed(lease))return null;}
        Instant expires=jdbc.queryForObject("SELECT lease_expires_at FROM marketplace_sync_jobs WHERE id=?",Timestamp.class,lease.job().id()).toInstant();
        T result=action.apply(jdbc);
        if(!expires.isAfter(now()))throw new IllegalStateException("Relationship lease expired before publication commit");
        if(account(lease)?!coverage.withinDeadline(lease):trial!=null&&!trial.withinDeadline(lease.job().id()))throw new IllegalStateException("Relationship trial deadline expired before publication commit");
        return result;
    });}
    private void rateLock(){jdbc.execute("SELECT pg_advisory_xact_lock(7142198372401)");}
    private long counter(String sequence){return jdbc.queryForObject("SELECT last_value FROM "+sequence,Long.class);}
    private void set(String sequence,long value){jdbc.queryForObject("SELECT setval('"+sequence+"',?)",Long.class,value);}
    public boolean reserve(Lease lease){return reserve(lease,UUID.randomUUID());}
    public boolean reserve(Lease lease,UUID request){return Boolean.TRUE.equals(owned(lease,j->{
        rateLock();long next=counter("amazon_variation_next_request_ms"),now=now().toEpochMilli();
        if(next>now){if(trial!=null&&Duration.ofMillis(next-now).compareTo(trialRemaining(lease))>=0)stop(lease,"RETRY_OUTSIDE_WINDOW");
            else waitInside(lease,Instant.ofEpochMilli(next),lease.cursor());return false;}
        if(account(lease)?!coverage.reserve(lease,false,request):trial!=null&&!trial.reserve(lease,false,request))return false;
        set("amazon_variation_next_request_ms",Math.addExact(now,counter("amazon_variation_request_interval_ms")));return true;
    }));}
    public void observeRate(Double requestsPerSecond){
        if(requestsPerSecond==null||!Double.isFinite(requestsPerSecond)||requestsPerSecond<=0)return;
        rateLock();long interval=(long)Math.ceil(1000.0/requestsPerSecond);
        if(interval>Duration.ofDays(365).toMillis())throw new IllegalArgumentException("Relationship rate requires operator review");
        interval=Math.max(1000,interval);
        // Never accelerate automatically. A stricter observed operation limit survives restart.
        interval=Math.max(interval,counter("amazon_variation_request_interval_ms"));set("amazon_variation_request_interval_ms",interval);
        set("amazon_variation_next_request_ms",Math.max(counter("amazon_variation_next_request_ms"),Math.addExact(now().toEpochMilli(),interval)));
    }
    public void advanceInside(Lease lease,Collection<String> referenced){
        var pending=new LinkedHashSet<String>();lease.cursor().path("pending").forEach(v->pending.add(v.asText()));
        var done=new LinkedHashSet<String>();lease.cursor().path("done").forEach(v->done.add(v.asText()));
        String sku=pending.iterator().next();pending.remove(sku);done.add(sku);
        var rechecked=new LinkedHashSet<String>();lease.cursor().path("rechecked").forEach(v->rechecked.add(v.asText()));
        for(String member:referenced){if(!done.contains(member))pending.add(member);
            else if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_variation_classifications WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? AND observed_role='PARENT')",Boolean.class,lease.job().tenantId(),lease.job().connectionId(),lease.job().marketplaceId(),member))&&rechecked.add(member))pending.add(member);}

        var identities=new HashSet<>(done);identities.addAll(pending);
        if(identities.size()>(account(lease)?AmazonVariationCoverageGrant.IDENTITIES:trial==null?1000:4)||lease.fence()>10000){finishInside(lease,true,"FAMILY_LIMIT");return;}
        if(pending.isEmpty()){jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor || CAST(? AS jsonb) WHERE tenant_id=? AND id=?",json.writeValueAsString(Map.of("pending",pending,"done",done,"rechecked",rechecked,"marketplace",lease.job().marketplaceId())),lease.job().tenantId(),lease.job().id());var args=new ArrayList<Object>(List.of(lease.job().tenantId(),lease.job().connectionId(),lease.job().marketplaceId()));args.addAll(done);
            boolean unresolved=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_variation_classifications WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku IN ("+String.join(",",Collections.nCopies(done.size(),"?"))+") AND (classification IN ('UNKNOWN','CONFLICT') OR valid_until<=?))",Boolean.class,appendTime(args)));
            finishInside(lease,unresolved,unresolved?"UNRESOLVED_EVIDENCE":null);return;}
        var cursor=json.valueToTree(Map.of("pending",pending,"done",done,"rechecked",rechecked,"marketplace",lease.job().marketplaceId()));
        waitInside(lease,now().plusSeconds(1),cursor);
    }
    private Object[] appendTime(List<Object> args){args.add(time(now()));return args.toArray();}
    void waitInside(Lease lease,Instant at,JsonNode cursor){var workflow=cursor.deepCopy();((tools.jackson.databind.node.ObjectNode)workflow).remove("approval");jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',available_at=?,cursor=cursor || CAST(? AS jsonb),lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",time(at),workflow.toString(),lease.job().tenantId(),lease.job().id());}
    public void retryInside(Lease lease,int status,Duration retryAfter){
        int failures=lease.job().failures()+1;
        jdbc.update("UPDATE marketplace_sync_jobs SET failure_count=?,error_code=? WHERE tenant_id=? AND id=?",failures,"HTTP_"+status,lease.job().tenantId(),lease.job().id());
        if(status==401||status==403||failures>=8){finishInside(lease,true,status==401||status==403?"AUTHORIZATION_REQUIRED":"RETRY_EXHAUSTED");return;}
        long[] backoff={60,120,240,480,960,1800,3600};long exponential=backoff[Math.min(failures-1,6)];long jitter=Math.floorMod(lease.job().id().hashCode()+failures,16);
        Duration delay=Duration.ofSeconds(exponential+jitter);if(retryAfter!=null&&retryAfter.compareTo(delay)>0)delay=retryAfter;
        Instant at=now().plus(delay);
        if(trial!=null&&delay.compareTo(trialRemaining(lease))>=0){stop(lease,"RETRY_OUTSIDE_WINDOW");return;}
        if(status==429){rateLock();long current=counter("amazon_variation_request_interval_ms");
            set("amazon_variation_request_interval_ms",Math.max(current,Math.min(21600000,current>10800000?21600000:current*2)));set("amazon_variation_next_request_ms",Math.max(counter("amazon_variation_next_request_ms"),at.toEpochMilli()));}
        waitInside(lease,at,lease.cursor());
        jdbc.update("UPDATE amazon_variation_classifications SET next_attempt_at=? WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",time(at),lease.job().tenantId(),lease.job().connectionId(),lease.job().marketplaceId(),lease.cursor().path("pending").path(0).asText());
    }
    private void finishInside(Lease lease,boolean warning,String reason){
        if(account(lease))coverage.finish(lease,warning?(reason==null?"UNRESOLVED_EVIDENCE":reason):"COMPLETED");else if(trial!=null)trial.finish(lease.job().id(),warning,reason);
        jdbc.update("UPDATE marketplace_sync_jobs SET status=?,completed_at=?,lease_owner=NULL,lease_expires_at=NULL,error_code=? WHERE tenant_id=? AND id=?",warning?"SKIPPED":"COMPLETED",time(now()),reason,lease.job().tenantId(),lease.job().id());
        jdbc.update("UPDATE marketplace_sync_runs SET status=?,completed_at=?,progress_percent=100,user_message=? WHERE tenant_id=? AND id=?",warning?"COMPLETED_WITH_WARNINGS":"COMPLETED",time(now()),warning?"Relationship evidence remains unresolved":"Relationship acquisition completed",lease.job().tenantId(),lease.job().runId());
    }
}
