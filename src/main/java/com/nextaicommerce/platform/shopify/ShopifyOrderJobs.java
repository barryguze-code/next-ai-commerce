package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Existing durable sync job/run infrastructure, scoped leases and explicit bounded Orders reads. */
@Repository
public class ShopifyOrderJobs {
    static final String TYPE="SHOPIFY_ORDERS";
    public record Job(UUID tenant,UUID connection,UUID id,UUID run,String owner,int fence,Map<String,Object> state){
        public String cursor(){return (String)state.get("after");}
        public Instant revision(){return Instant.parse(state.get("connectionRevision").toString());}
        public Instant start(){return Instant.parse(state.get("start").toString());}
        public Instant end(){return Instant.parse(state.get("end").toString());}
    }
    public record Status(UUID id,String state,String message,long checked,Instant availableAt,Instant checkedAt){
        public boolean running(){return Set.of("QUEUED","WAITING","RUNNING").contains(state);}
    }
    static class LostLease extends IllegalStateException{LostLease(){super("Orders lease or source authorization changed; no page published.");}}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final WorkspaceAccessRepository access;
    private final ShopifyConnectionService connections;private final ShopifyOrderRepository observations;private final ShopifyOrderInventory inventory;
    private final boolean enabled;private final ObjectMapper json=new ObjectMapper();private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public ShopifyOrderJobs(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access,ShopifyConnectionService connections,
                            ShopifyOrderRepository observations,ShopifyOrderInventory inventory,@Value("${app.shopify.orders.enabled:false}") boolean enabled){
        this(jdbc,tx,access,connections,observations,inventory,enabled,Clock.systemUTC());
    }
    ShopifyOrderJobs(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access,ShopifyConnectionService connections,
                     ShopifyOrderRepository observations,ShopifyOrderInventory inventory,boolean enabled,Clock clock){
        this.jdbc=jdbc;this.tx=tx;this.access=access;this.connections=connections;this.observations=observations;this.inventory=inventory;this.enabled=enabled;this.clock=clock;
    }
    public boolean enabled(){return enabled;}
    public boolean catalogueReady(UUID tenant,UUID connection){return Boolean.TRUE.equals(tx.execute(s->{tenant(tenant);return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type='SHOPIFY_CATALOG_VARIANTS' AND status='COMPLETED')",Boolean.class,tenant,connection);}));}
    public boolean initialized(UUID tenant,UUID connection){if(!enabled)return false;return Boolean.TRUE.equals(tx.execute(s->{tenant(tenant);return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shopify_order_controls WHERE tenant_id=? AND marketplace_connection_id=? AND baseline_completed_at IS NOT NULL)",Boolean.class,tenant,connection);}));}
    void tenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    void authorize(UUID tenant,UUID connection,String actor){
        authorizeActor(tenant,connection,actor);
        if(!connections.setupState(tenant,connection).ordersPermitted())throw new org.springframework.security.access.AccessDeniedException("Verify the approved Shopify Orders read permissions first.");
    }
    public void authorizeActor(UUID tenant,UUID connection,String actor){
        boolean admin=access.isCurrentPlatformAdmin(actor);
        if(!access.canOperateAccount(tenant,actor)||access.listAccountOptions(actor,admin).stream().filter(a->tenant.equals(a.id()))
                .flatMap(a->a.stores().stream()).noneMatch(s->connection.equals(s.id())&&"SHOPIFY".equals(s.channel())))
            throw new org.springframework.security.access.AccessDeniedException("Shopify store access unavailable.");
    }
    public void enablePolling(UUID tenant,UUID connection,String actor){if(!enabled)throw new IllegalStateException("Shopify Orders sync is disabled.");tx.executeWithoutResult(s->{authorize(tenant,connection,actor);tenant(tenant);
        jdbc.update("INSERT INTO shopify_order_controls(tenant_id,marketplace_connection_id,polling_enabled,actor_email,next_sync_at) VALUES(?,?,true,?,clock_timestamp()+interval '5 minutes') ON CONFLICT(tenant_id,marketplace_connection_id) DO UPDATE SET polling_enabled=true,actor_email=EXCLUDED.actor_email,next_sync_at=EXCLUDED.next_sync_at,last_error=NULL",tenant,connection,actor);
    });}
    public record Due(UUID tenant,UUID connection,String actor){}
    public List<Due> due(){if(!enabled)return List.of();var result=new ArrayList<Due>();
        for(UUID tenant:jdbc.queryForList("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",UUID.class))tx.executeWithoutResult(s->{tenant(tenant);
            jdbc.query("UPDATE shopify_order_controls control SET next_sync_at=clock_timestamp()+interval '5 minutes' FROM marketplace_connections c WHERE control.tenant_id=? AND control.polling_enabled AND control.actor_email IS NOT NULL AND control.next_sync_at<=clock_timestamp() AND c.tenant_id=control.tenant_id AND c.id=control.marketplace_connection_id AND c.channel='SHOPIFY' AND c.status='ACTIVE' RETURNING control.marketplace_connection_id,control.actor_email",rs->{result.add(new Due(tenant,rs.getObject(1,UUID.class),rs.getString(2)));},tenant);
        });return List.copyOf(result);
    }
    public void pausePolling(UUID tenant,UUID connection,String message){tx.executeWithoutResult(s->{tenant(tenant);jdbc.update("UPDATE shopify_order_controls SET polling_enabled=false,last_error=? WHERE tenant_id=? AND marketplace_connection_id=?",message,tenant,connection);});}
    public UUID enqueue(UUID tenant,UUID connection,String actor){
        if(!enabled)throw new IllegalStateException("Shopify Orders sync is disabled.");
        return tx.execute(s->{authorize(tenant,connection,actor);tenant(tenant);
            var rows=jdbc.queryForList("SELECT updated_at FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='SHOPIFY' AND status='ACTIVE' FOR UPDATE",tenant,connection);
            if(rows.isEmpty())throw new IllegalArgumentException("Verify the selected Shopify store first.");
            var active=jdbc.queryForList("SELECT id,cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND status IN ('QUEUED','WAITING','RUNNING') FOR UPDATE",tenant,connection,TYPE);
            if(!active.isEmpty()){
                var old=active.getFirst();var state=read(old.get("cursor").toString());
                if(Objects.equals(state.get("connectionRevision"),((Timestamp)rows.getFirst().get("updated_at")).toInstant().toString())
                    &&clock.instant().isBefore(Instant.parse(state.get("deadline").toString())))return (UUID)old.get("id");
                jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=clock_timestamp(),user_message='Previous read window or authorization ended; preserved all observations and allocations' WHERE tenant_id=? AND marketplace_connection_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?)",tenant,connection,tenant,old.get("id"));
                jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',lease_owner=NULL,lease_expires_at=NULL,completed_at=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,old.get("id"));
            }
            Instant end=clock.instant();var previous=jdbc.queryForList("SELECT window_end FROM marketplace_sync_runs WHERE tenant_id=? AND marketplace_connection_id=? AND sync_profile='SHOPIFY_ORDERS' AND status='COMPLETED' ORDER BY completed_at DESC LIMIT 1",tenant,connection);
            Instant start=previous.isEmpty()?end.minus(Duration.ofDays(30)):((Timestamp)previous.getFirst().get("window_end")).toInstant().minusSeconds(300);
            if(start.isBefore(end.minus(Duration.ofDays(59))))throw new IllegalStateException("Shopify Orders gap exceeds the installed read window. Retained allocations require review before resuming.");
            UUID run=UUID.randomUUID(),job=UUID.randomUUID();var state=new LinkedHashMap<String,Object>();
            state.put("actor",actor);state.put("connectionRevision",((Timestamp)rows.getFirst().get("updated_at")).toInstant().toString());state.put("queryRevision",ShopifyOrderClient.REVISION);
            state.put("start",start.toString());state.put("end",end.toString());state.put("deadline",end.plusSeconds(3600).toString());state.put("requests",0);state.put("costReserved",0);state.put("retries",0);state.put("seen",List.of());state.put("cursors",List.of());
            jdbc.update("INSERT INTO shopify_order_controls(tenant_id,marketplace_connection_id) VALUES(?,?) ON CONFLICT DO NOTHING",tenant,connection);
            jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end,current_stage,user_message) VALUES(?,?,?,'INCREMENTAL','SHOPIFY_ORDERS','QUEUED',?,?,'SHOPIFY_ORDERS','Shopify Orders queued')",run,tenant,connection,Timestamp.from(start),Timestamp.from(end));
            jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,max_attempts,cursor) VALUES(?,?,?,?,?,1,1000,?::jsonb)",job,tenant,connection,run,TYPE,json.writeValueAsString(state));return job;
        });
    }
    public Job claim(UUID tenant,UUID connection,UUID id){if(!enabled)return null;return tx.execute(s->{tenant(tenant);String owner=UUID.randomUUID().toString();
        var rows=jdbc.queryForList("""
            UPDATE marketplace_sync_jobs j SET status='RUNNING',lease_owner=?,lease_expires_at=clock_timestamp()+interval '90 seconds',attempt_count=attempt_count+1,
                cursor=cursor||jsonb_build_object('fence',coalesce((cursor->>'fence')::int,0)+1),started_at=coalesce(started_at,clock_timestamp())
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type=? AND j.status IN ('QUEUED','WAITING','RUNNING')
              AND j.available_at<=clock_timestamp() AND (j.lease_expires_at IS NULL OR j.lease_expires_at<clock_timestamp())
              AND EXISTS(SELECT 1 FROM marketplace_sync_runs r WHERE r.tenant_id=j.tenant_id AND r.marketplace_connection_id=j.marketplace_connection_id AND r.id=j.sync_run_id AND r.status IN ('QUEUED','RUNNING'))
            RETURNING sync_run_id,cursor::text
            """,owner,tenant,connection,id,TYPE);
        if(rows.isEmpty())return null;var state=read(rows.getFirst().get("cursor").toString());
        jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING',started_at=coalesce(started_at,clock_timestamp()) WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,connection,rows.getFirst().get("sync_run_id"));
        return new Job(tenant,connection,id,(UUID)rows.getFirst().get("sync_run_id"),owner,((Number)state.get("fence")).intValue(),state);
    });}
    @SuppressWarnings("unchecked") private Map<String,Object> read(String value){return json.readValue(value,LinkedHashMap.class);}
    private Map<String,Object> guard(Job j){
        authorize(j.tenant(),j.connection(),j.state().get("actor").toString());tenant(j.tenant());
        var rows=jdbc.queryForList("""
            SELECT j.cursor::text FROM marketplace_sync_jobs j
            JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.marketplace_connection_id=j.marketplace_connection_id AND r.id=j.sync_run_id
            JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
            WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type=? AND j.status='RUNNING' AND j.lease_owner=?
              AND (j.cursor->>'fence')::int=? AND j.lease_expires_at>clock_timestamp() AND r.status='RUNNING'
              AND c.channel='SHOPIFY' AND c.status='ACTIVE' AND c.updated_at=? FOR UPDATE OF j,c
            """,j.tenant(),j.connection(),j.id(),TYPE,j.owner(),j.fence(),Timestamp.from(j.revision()));
        if(rows.isEmpty())throw new LostLease();var state=read(rows.getFirst().get("cursor").toString());
        if(!ShopifyOrderClient.REVISION.equals(state.get("queryRevision")))throw new LostLease();return state;
    }
    private void save(Job j,Map<String,Object> state){jdbc.update("UPDATE marketplace_sync_jobs SET cursor=?::jsonb WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(state),j.tenant(),j.connection(),j.id());}
    public void reserve(Job j){tx.executeWithoutResult(s->{var state=guard(j);int requests=((Number)state.get("requests")).intValue();
        if(requests>=1000||!clock.instant().isBefore(Instant.parse(state.get("deadline").toString())))throw new IllegalArgumentException("Partial Orders import reached its bounded read limit; existing evidence and allocations were retained.");
        state.put("requests",requests+1);state.put("costReserved",((Number)state.get("costReserved")).intValue()+1000);save(j,state);
    });}
    public boolean publish(Job j,String raw,ShopifyOrderClient.Page page){return Boolean.TRUE.equals(tx.execute(s->{inventory.lock(j.tenant());var state=guard(j);
        publicationDeadline(state);
        @SuppressWarnings("unchecked") var seen=new ArrayList<String>((List<String>)state.get("seen"));
        for(var o:page.orders())if(seen.contains(o.gid()))throw new IllegalArgumentException("Repeated order identity in this bounded scan.");else seen.add(o.gid());
        @SuppressWarnings("unchecked") var cursors=new ArrayList<String>((List<String>)state.get("cursors"));
        if(page.more()&&(page.next()==null||page.next().isBlank()||page.next().equals(j.cursor())||cursors.contains(page.next())))throw new IllegalArgumentException("Repeated Shopify Orders cursor.");
        observations.publish(j.tenant(),j.connection(),j.id(),j.cursor(),raw,page);
        // Verify the lease again after all warehouse work; an expired worker rolls the entire publication back.
        guard(j);publicationDeadline(state);state.put("seen",seen);state.put("after",page.next());state.put("retries",0);if(page.more())cursors.add(page.next());state.put("cursors",cursors);
        if(!page.more()){
            jdbc.update("UPDATE shopify_order_controls SET baseline_completed_at=coalesce(baseline_completed_at,clock_timestamp()),updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=?",j.tenant(),j.connection());
            // Activation is explicit. Historical orders remain reporting-only unless the separate,
            // exact-store bootstrap scope authorizes their remaining-only cutover below.
            if(inventory.enabled())jdbc.update("UPDATE shopify_order_controls SET inventory_enabled=true,activated_at=clock_timestamp(),updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND baseline_completed_at IS NOT NULL AND activated_at IS NULL AND NOT inventory_enabled",j.tenant(),j.connection());
            inventory.bootstrapRemaining(j.tenant(),j.connection(),j.id());
            guard(j);publicationDeadline(state); // Cutover and reservations roll back with a lost lease.
            finish(j,state,"COMPLETED","COMPLETED","Shopify Orders sync complete");return false;
        }
        if(((Number)state.get("requests")).intValue()>=1000){finish(j,state,"FAILED","COMPLETED_WITH_WARNINGS","Partial Orders import reached its bounded read limit");return false;}
        long delay=page.restoreRate()>0&&page.available()<1000?(long)Math.ceil((1000-page.available())/page.restoreRate()):1;
        waitFor(j,state,clock.instant().plusSeconds(Math.max(1,delay)),"Reading next Shopify order");return true;
    }));}
    private void publicationDeadline(Map<String,Object> state){if(!clock.instant().isBefore(Instant.parse(state.get("deadline").toString())))throw new IllegalArgumentException("Orders read deadline ended; this page and its warehouse changes were not published.");}
    public boolean retry(Job j,Instant at){return Boolean.TRUE.equals(tx.execute(s->{var state=guard(j);int retries=((Number)state.get("retries")).intValue()+1;state.put("retries",retries);
        if(retries>3||!at.isBefore(Instant.parse(state.get("deadline").toString()))){finish(j,state,"FAILED","COMPLETED_WITH_WARNINGS","Orders retry or read-window limit reached; records retained");return false;}
        waitFor(j,state,at,"Shopify Orders paused; retrying the same page");return true;
    }));}
    public void fail(Job j,String raw,String message){tx.executeWithoutResult(s->{var state=guard(j);if(raw!=null)observations.archive(j.tenant(),j.connection(),j.id(),j.cursor(),raw);finish(j,state,"FAILED","COMPLETED_WITH_WARNINGS",message);});}
    private void waitFor(Job j,Map<String,Object> state,Instant at,String message){save(j,state);jdbc.update("UPDATE marketplace_sync_jobs SET status='WAITING',available_at=?,lease_owner=NULL,lease_expires_at=NULL,records_processed=?,error_message=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",Timestamp.from(at),((List<?>)state.get("seen")).size(),message,j.tenant(),j.connection(),j.id());}
    private void finish(Job j,Map<String,Object> state,String status,String runStatus,String message){save(j,state);
        if(!"COMPLETED".equals(status))jdbc.update("UPDATE shopify_order_controls SET polling_enabled=false,last_error=? WHERE tenant_id=? AND marketplace_connection_id=?",message,j.tenant(),j.connection());
        jdbc.update("UPDATE marketplace_sync_jobs SET status=?,lease_owner=NULL,lease_expires_at=NULL,completed_at=clock_timestamp(),records_processed=?,error_message=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",status,((List<?>)state.get("seen")).size(),message,j.tenant(),j.connection(),j.id());
        jdbc.update("UPDATE marketplace_sync_runs SET status=?,completed_at=clock_timestamp(),progress_percent=CASE WHEN ?='COMPLETED' THEN 100 ELSE 0 END,user_message=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",runStatus,status,message,j.tenant(),j.connection(),j.run());
    }
    public Status status(UUID tenant,UUID connection){if(!enabled)return null;return tx.execute(s->{tenant(tenant);return jdbc.query("SELECT id,status,error_message,records_processed,available_at,completed_at FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? ORDER BY created_at DESC LIMIT 1",(r,n)->new Status(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getLong(4),r.getTimestamp(5).toInstant(),r.getTimestamp(6)==null?null:r.getTimestamp(6).toInstant()),tenant,connection,TYPE).stream().findFirst().orElse(null);});}
    public void cancel(UUID tenant,UUID connection,String actor){tx.executeWithoutResult(s->{authorize(tenant,connection,actor);tenant(tenant);
        jdbc.update("UPDATE shopify_order_controls SET polling_enabled=false,last_error='Sync cancelled' WHERE tenant_id=? AND marketplace_connection_id=?",tenant,connection);
        var runs=jdbc.queryForList("UPDATE marketplace_sync_jobs SET status='SKIPPED',lease_owner=NULL,lease_expires_at=NULL,completed_at=clock_timestamp(),error_message='Cancelled; prior observations and allocations retained' WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND status IN ('QUEUED','WAITING','RUNNING') RETURNING sync_run_id",UUID.class,tenant,connection,TYPE);
        for(UUID run:runs)jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED',completed_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,connection,run);
    });}
    /** A revoked actor can terminate only its own still-current lease, never publish source or stock. */
    public void pauseRevoked(Job j){tx.executeWithoutResult(s->{tenant(j.tenant());
        int changed=jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',lease_owner=NULL,lease_expires_at=NULL,completed_at=clock_timestamp(),error_message='Store access or authorization changed; prior data and allocations retained' WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND job_type=? AND status='RUNNING' AND lease_owner=? AND (cursor->>'fence')::int=? AND lease_expires_at>clock_timestamp()",j.tenant(),j.connection(),j.id(),TYPE,j.owner(),j.fence());
        if(changed==1)jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",j.tenant(),j.connection(),j.run());
        if(changed==1)jdbc.update("UPDATE shopify_order_controls SET polling_enabled=false,last_error='Store access or authorization changed' WHERE tenant_id=? AND marketplace_connection_id=?",j.tenant(),j.connection());
    });}
}
