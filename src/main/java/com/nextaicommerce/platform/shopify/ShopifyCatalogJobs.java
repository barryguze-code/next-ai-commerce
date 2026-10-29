package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable bounded queue. Every publication is fenced by live lease, connection revision and permissions. */
@Repository
public class ShopifyCatalogJobs {
    static final String TYPE="SHOPIFY_CATALOG_VARIANTS";
    static final int MAX_VARIANTS=10000,MAX_REQUESTS=1000;
    public record Job(UUID tenant,UUID connection,UUID id,UUID run,String owner,int fence,Map<String,Object> state) {
        public String cursor(){return Objects.toString(state.get("after"),null);}
        public Instant revision(){return Instant.parse(state.get("connectionRevision").toString());}
    }
    public record Status(UUID id,String state,String message,long checked,Instant availableAt,Instant checkedAt){
        public Status(UUID id,String state,String message,long checked,Instant availableAt){this(id,state,message,checked,availableAt,null);}
        public boolean running(){return Set.of("QUEUED","RUNNING","WAITING").contains(state);}
        public String statusLabel(){return running()?"Syncing":"COMPLETED".equals(state)?"Synced":"Needs attention";}
        public String lastCheckLabel(){return checkedAt==null?"No completed Shopify SKU check":"Last Shopify SKU check · "+java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy · HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(checkedAt);}
    }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final WorkspaceAccessRepository access;private final ShopifyCatalogRepository observations;
    private final ObjectMapper json=new ObjectMapper();private final boolean enabled;
    @Value("${app.shopify.orders.enabled:false}") private boolean ordersEnabled;
    public ShopifyCatalogJobs(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access,ShopifyCatalogRepository observations,@Value("${app.shopify.catalog.enabled:false}") boolean enabled){this.jdbc=jdbc;this.tx=tx;this.access=access;this.observations=observations;this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    void tenant(UUID id){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());}
    public void authorize(UUID tenant,UUID connection,String email){
        boolean admin=access.isCurrentPlatformAdmin(email);
        if(!access.canOperateAccount(tenant,email)||access.listAccountOptions(email,admin).stream().filter(a->tenant.equals(a.id())).flatMap(a->a.stores().stream()).noneMatch(s->s.id().equals(connection)&&"SHOPIFY".equals(s.channel())))throw new org.springframework.security.access.AccessDeniedException("Shopify store access unavailable.");
    }
    public UUID enqueue(UUID tenant,UUID connection,String actor){
        if(!enabled)throw new IllegalStateException("Shopify SKU sync is not enabled in this environment.");
        return tx.execute(s->{authorize(tenant,connection,actor);tenant(tenant);
            var c=jdbc.queryForList("SELECT updated_at FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='SHOPIFY' AND status='ACTIVE' FOR UPDATE",tenant,connection);if(c.isEmpty())throw new IllegalArgumentException("Verify this Shopify connection first.");
            var active=jdbc.queryForList("SELECT id,sync_run_id,cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND status IN ('QUEUED','RUNNING','WAITING') FOR UPDATE",tenant,connection,TYPE);
            if(!active.isEmpty()){
                var old=active.getFirst();var state=read(old.get("cursor").toString());
                if(resumable(state,((Timestamp)c.getFirst().get("updated_at")).toInstant(),Instant.now()))return (UUID)old.get("id");
                finish(new Job(tenant,connection,(UUID)old.get("id"),(UUID)old.get("sync_run_id"),null,0,state),state,"FAILED","COMPLETED_WITH_WARNINGS","Previous sync window ended; imported records were kept.");
            }
            var prior=jdbc.queryForList("SELECT id,sync_run_id,status,cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",tenant,connection,TYPE);
            if(!prior.isEmpty()&&!"COMPLETED".equals(prior.getFirst().get("status"))){
                var old=prior.getFirst();var state=read(old.get("cursor").toString());
                if(state.containsKey("after")&&!Objects.toString(state.get("after"),"").isBlank()&&resumable(state,((Timestamp)c.getFirst().get("updated_at")).toInstant(),Instant.now())){
                    if(((Number)state.get("requests")).intValue()>=MAX_REQUESTS||((List<?>)state.get("seen")).size()>=MAX_VARIANTS)throw new IllegalArgumentException("This initialization reached its overall safety limit. Its saved position and imported records are retained for review.");
                    rollWindow(state,Instant.now(),true);state.put("actor",actor);
                    jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',max_attempts=1000,cursor=?::jsonb,available_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL,completed_at=NULL,error_message='Continuing Shopify catalogue from its saved position' WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(state),tenant,connection,old.get("id"));
                    jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',completed_at=NULL,user_message='Continuing Shopify catalogue from its saved position' WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,connection,old.get("sync_run_id"));return (UUID)old.get("id");
                }
                if(state.containsKey("after")&&((List<?>)state.getOrDefault("seen",List.of())).size()>0&&Objects.equals(state.get("connectionRevision"),((Timestamp)c.getFirst().get("updated_at")).toInstant().toString()))throw new IllegalArgumentException("The overall initialization window ended. Its saved position and imported records are retained for review.");
            }
            UUID run=UUID.randomUUID(),job=UUID.randomUUID();Instant now=Instant.now();var cursor=new LinkedHashMap<String,Object>();cursor.put("actor",actor);cursor.put("deadline",now.plusSeconds(300).toString());cursor.put("overallDeadline",now.plusSeconds(3600).toString());cursor.put("windowRequests",0);cursor.put("windowCostReserved",0);cursor.put("windowStartSeen",0);cursor.put("connectionRevision",((Timestamp)c.getFirst().get("updated_at")).toInstant().toString());cursor.put("queryRevision",ShopifyCatalogClient.REVISION);cursor.put("requests",0);cursor.put("costReserved",0);cursor.put("seen",List.of());cursor.put("cursors",List.of());cursor.put("pageRetries",0);
            jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end,current_stage,user_message) VALUES(?,?,?,'INCREMENTAL','SHOPIFY_CATALOG','QUEUED',?,?,'SHOPIFY_CATALOG_VARIANTS','Shopify SKU sync queued')",run,tenant,connection,Timestamp.from(now),Timestamp.from(now));
            jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,max_attempts,cursor) VALUES(?,?,?,?,?,1,1000,?::jsonb)",job,tenant,run,connection,TYPE,json.writeValueAsString(cursor));return job;
        });
    }
    static boolean resumable(Map<String,Object> state,Instant revision,Instant now){
        return now.isBefore(overallDeadline(state))&&ShopifyCatalogClient.REVISION.equals(state.get("queryRevision"))&&revision.toString().equals(state.get("connectionRevision"));
    }
    static Instant overallDeadline(Map<String,Object> state){return state.containsKey("overallDeadline")?Instant.parse(state.get("overallDeadline").toString()):Instant.parse(state.get("deadline").toString()).plusSeconds(3300);}
    /** Reset only a bounded work window. Cursor, evidence generation and cumulative budgets never reset. */
    static void rollWindow(Map<String,Object> state,Instant now,boolean force){
        state.putIfAbsent("overallDeadline",overallDeadline(state).toString());
        state.putIfAbsent("windowRequests",state.get("requests"));state.putIfAbsent("windowCostReserved",state.get("costReserved"));state.putIfAbsent("windowStartSeen",0);
        if(force||!now.isBefore(Instant.parse(state.get("deadline").toString()))||((Number)state.get("windowRequests")).intValue()>=40||((Number)state.get("windowCostReserved")).intValue()>=20000||((List<?>)state.get("seen")).size()-((Number)state.get("windowStartSeen")).intValue()>=200){
            state.put("windowRequests",0);state.put("windowCostReserved",0);state.put("windowStartSeen",((List<?>)state.get("seen")).size());state.put("deadline",now.plusSeconds(300).toString());
        }
    }
    public Job claim(UUID tenant,UUID connection,UUID id){return tx.execute(s->{tenant(tenant);String owner=UUID.randomUUID().toString();
        var rows=jdbc.queryForList("""
            UPDATE marketplace_sync_jobs j SET status='RUNNING',lease_owner=?,lease_expires_at=clock_timestamp()+interval '90 seconds',attempt_count=attempt_count+1,
            cursor=cursor||jsonb_build_object('fence',coalesce((cursor->>'fence')::int,0)+1),started_at=coalesce(started_at,clock_timestamp())
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type=? AND j.status IN ('QUEUED','WAITING','RUNNING')
            AND j.available_at<=clock_timestamp() AND (j.lease_expires_at IS NULL OR j.lease_expires_at<clock_timestamp())
            AND EXISTS(SELECT 1 FROM marketplace_sync_runs r WHERE r.id=j.sync_run_id AND r.tenant_id=j.tenant_id AND r.marketplace_connection_id=j.marketplace_connection_id AND r.status IN ('QUEUED','RUNNING'))
            RETURNING sync_run_id,cursor::text
            """,owner,tenant,connection,id,TYPE);
        if(rows.isEmpty())return null;var state=read(rows.getFirst().get("cursor").toString());
        jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',started_at=coalesce(started_at,clock_timestamp()),current_stage=?,user_message='Reading Shopify products and variants' WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",TYPE,tenant,connection,rows.getFirst().get("sync_run_id"));
        var claimed=new Job(tenant,connection,id,(UUID)rows.getFirst().get("sync_run_id"),owner,((Number)state.get("fence")).intValue(),state);rollWindow(state,Instant.now(),false);saveState(claimed,state);return claimed;
    });}
    @SuppressWarnings("unchecked") Map<String,Object> read(String text){return json.readValue(text,LinkedHashMap.class);}
    private Map<String,Object> guard(Job j){
        authorize(j.tenant(),j.connection(),j.state().get("actor").toString());tenant(j.tenant());
        if(jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='SHOPIFY' AND status='ACTIVE' AND updated_at=? FOR UPDATE",UUID.class,j.tenant(),j.connection(),Timestamp.from(j.revision())).isEmpty())throw new LostLease();
        var rows=jdbc.queryForList("""
            SELECT j.cursor::text FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.marketplace_connection_id=j.marketplace_connection_id AND r.id=j.sync_run_id
            JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type=? AND j.status='RUNNING' AND j.lease_owner=?
            AND j.lease_expires_at>clock_timestamp() AND (j.cursor->>'fence')::int=? AND r.status='RUNNING' AND c.channel='SHOPIFY' AND c.status='ACTIVE' AND c.updated_at=?
            FOR UPDATE OF j
            """,j.tenant(),j.connection(),j.id(),TYPE,j.owner(),j.fence(),Timestamp.from(j.revision()));
        if(rows.isEmpty())throw new LostLease();var state=read(rows.getFirst().get("cursor").toString());
        if(!ShopifyCatalogClient.REVISION.equals(state.get("queryRevision")))throw new IllegalArgumentException("Sync query changed. Start a new sync; existing records were kept.");return state;
    }
    public static class LostLease extends IllegalStateException {LostLease(){super("Sync lease or authorization changed. No page was published.");}}
    public void reserve(Job j){tx.executeWithoutResult(s->{var state=guard(j);
        if(!Instant.now().isBefore(overallDeadline(state))||((Number)state.get("requests")).intValue()>=MAX_REQUESTS||((List<?>)state.get("seen")).size()>=MAX_VARIANTS)throw new IllegalArgumentException("Partial import: overall initialization safety limit reached. Its saved position and imported records were kept.");
        if(!Instant.now().isBefore(Instant.parse(state.get("deadline").toString()))||((Number)state.get("windowRequests")).intValue()>=40||((Number)state.get("windowCostReserved")).intValue()+1000>20000)throw new IllegalStateException("Checkpointing the next bounded Shopify work window.");
        state.put("requests",((Number)state.get("requests")).intValue()+1);state.put("costReserved",((Number)state.get("costReserved")).intValue()+1000);state.put("windowRequests",((Number)state.get("windowRequests")).intValue()+1);state.put("windowCostReserved",((Number)state.get("windowCostReserved")).intValue()+1000);saveState(j,state);
    });}
    public void publish(Job j,String body,ShopifyCatalogClient.Page page,String currency,Runnable completeMapping){tx.executeWithoutResult(s->{var state=guard(j);
        if(!Instant.now().isBefore(overallDeadline(state)))throw new IllegalArgumentException("Partial import: overall initialization time limit reached. Its saved position and imported records were kept.");
        @SuppressWarnings("unchecked") var seen=new ArrayList<String>((List<String>)state.get("seen"));
        for(var v:page.variants()){if(seen.contains(v.gid()))throw new IllegalArgumentException("Shopify repeated a variant across catalogue pages. Import stopped without truncating the evidence.");seen.add(v.gid());}
        if(seen.size()>MAX_VARIANTS||seen.size()-((Number)state.get("windowStartSeen")).intValue()>200)throw new IllegalArgumentException("Shopify exceeded the bounded variant limit. Its saved position and imported records were kept.");
        @SuppressWarnings("unchecked") var cursors=new ArrayList<String>((List<String>)state.getOrDefault("cursors",List.of()));
        if(page.more()&&(page.next()==null||page.next().isBlank()||page.next().equals(j.cursor())||cursors.contains(page.next())))throw new IllegalArgumentException("Shopify repeated a page cursor. Existing observations were kept.");
        if(page.more())cursors.add(page.next());state.put("cursors",cursors);
        observations.publish(j.tenant(),j.connection(),j.id(),j.run(),j.cursor(),body,page,currency);
        state.put("seen",seen);state.put("after",page.next());state.put("pageRetries",0);
        if(!page.more()){completeMapping.run();finish(j,state,"COMPLETED","COMPLETED",ordersEnabled?"Shopify SKU sync complete":"Shopify SKU sync complete; Orders are not enabled");}
        else if(seen.size()>=MAX_VARIANTS||((Number)state.get("requests")).intValue()>=MAX_REQUESTS)finish(j,state,"FAILED","COMPLETED_WITH_WARNINGS","Partial import: overall initialization limit reached. Its saved position and imported records were kept; automatic matching awaits a complete scan.");
        else{
            long delay=page.restoreRate()>0&&page.available()<500?(long)Math.ceil((500-page.available())/page.restoreRate()):1;
            state.put("nextDelay",Math.max(1,delay));waitState(j,state,Instant.now().plusSeconds(Math.max(1,delay)),"Reading next Shopify SKU page");
        }
    });}
    public void retry(Job j,Instant retryAt){tx.executeWithoutResult(s->{var state=guard(j);int attempts=((Number)state.get("pageRetries")).intValue()+1;state.put("pageRetries",attempts);
        if(attempts>3||!retryAt.isBefore(overallDeadline(state)))finish(j,state,"FAILED","COMPLETED_WITH_WARNINGS","Shopify retry limit or sync window reached. Imported records were kept.");
        else waitState(j,state,retryAt,"Shopify requested a pause; retrying this page");
    });}
    public void fail(Job j,String body,String message){tx.executeWithoutResult(s->{var state=guard(j);if(body!=null)observations.archive(j.tenant(),j.connection(),j.id(),j.cursor(),body);finish(j,state,"FAILED","COMPLETED_WITH_WARNINGS",message);});}
    void saveState(Job j,Map<String,Object> state){jdbc.update("UPDATE marketplace_sync_jobs SET cursor=?::jsonb WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(state),j.tenant(),j.connection(),j.id());}
    void waitState(Job j,Map<String,Object> state,Instant when,String message){saveState(j,state);jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',available_at=?,records_processed=jsonb_array_length(cursor->'seen'),lease_owner=NULL,lease_expires_at=NULL,error_message=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",Timestamp.from(when),message,j.tenant(),j.connection(),j.id());jdbc.update("UPDATE marketplace_sync_runs SET user_message=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",message,j.tenant(),j.connection(),j.run());}
    void finish(Job j,Map<String,Object> state,String jobStatus,String runStatus,String message){saveState(j,state);jdbc.update("UPDATE marketplace_sync_jobs SET status=?,records_processed=?,completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL,error_message=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",jobStatus,((List<?>)state.get("seen")).size(),message,j.tenant(),j.connection(),j.id());jdbc.update("UPDATE marketplace_sync_runs SET status=?,user_message=?,progress_percent=CASE WHEN ?='COMPLETED' THEN 100 ELSE 0 END,completed_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",runStatus,message,runStatus,j.tenant(),j.connection(),j.run());}
    public Status status(UUID tenant,UUID connection){return tx.execute(s->{tenant(tenant);return jdbc.query("SELECT j.id,CASE WHEN (j.status='RUNNING' AND j.lease_expires_at<clock_timestamp()) OR (j.status='QUEUED' AND j.created_at<clock_timestamp()-interval '30 seconds') OR (j.status='WAITING' AND j.available_at<clock_timestamp()-interval '30 seconds') THEN 'INTERRUPTED' ELSE j.status END,CASE WHEN (j.status='RUNNING' AND j.lease_expires_at<clock_timestamp()) OR (j.status='QUEUED' AND j.created_at<clock_timestamp()-interval '30 seconds') OR (j.status='WAITING' AND j.available_at<clock_timestamp()-interval '30 seconds') THEN 'Sync paused. Resume to continue safely; imported records were kept.' ELSE coalesce(j.error_message,r.user_message) END,greatest(j.records_processed,jsonb_array_length(coalesce(j.cursor->'seen','[]'::jsonb))),j.available_at,j.completed_at FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.marketplace_connection_id=j.marketplace_connection_id AND r.id=j.sync_run_id WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type=? ORDER BY j.created_at DESC LIMIT 1",(r,n)->new Status(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getLong(4),r.getTimestamp(5).toInstant(),r.getTimestamp(6)==null?null:r.getTimestamp(6).toInstant()),tenant,connection,TYPE).stream().findFirst().orElse(null);});}
    /** Operational termination only: a revoked actor cannot publish evidence or mappings. */
    public void pauseRevoked(Job j){tx.executeWithoutResult(s->{tenant(j.tenant());
        int changed=jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',lease_owner=NULL,lease_expires_at=NULL,completed_at=clock_timestamp(),error_message='Store access changed. An authorized user can start a new sync.' WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND job_type=? AND status='RUNNING' AND lease_owner=? AND (cursor->>'fence')::int=? AND lease_expires_at>clock_timestamp()",j.tenant(),j.connection(),j.id(),TYPE,j.owner(),j.fence());
        if(changed==1)jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=clock_timestamp(),user_message='Store access changed. Imported records were kept.' WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",j.tenant(),j.connection(),j.run());
    });}
    public void cancel(UUID tenant,UUID connection,String actor){tx.executeWithoutResult(s->{authorize(tenant,connection,actor);tenant(tenant);var runs=jdbc.queryForList("UPDATE marketplace_sync_jobs SET status='SKIPPED',lease_owner=NULL,lease_expires_at=NULL,error_message='Cancelled; imported records kept',completed_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND status IN ('QUEUED','WAITING','RUNNING') RETURNING sync_run_id",UUID.class,tenant,connection,TYPE);for(UUID run:runs)jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED',user_message='Cancelled; imported records kept',completed_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,connection,run);});}
}
