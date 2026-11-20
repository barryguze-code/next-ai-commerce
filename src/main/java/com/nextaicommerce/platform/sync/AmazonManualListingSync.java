package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Requests an import through the existing background scheduler; never publishes prices or stock. */
@Service
public class AmazonManualListingSync {
    private com.nextaicommerce.platform.config.LocalIntegrationSafety localSafety=new com.nextaicommerce.platform.config.LocalIntegrationSafety(false);
    @org.springframework.beans.factory.annotation.Autowired void configureLocalSafety(com.nextaicommerce.platform.config.LocalIntegrationSafety safety){this.localSafety=safety;}
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${app.amazon.sync-enabled:true}") private boolean workerEnabled=true;
    @org.springframework.beans.factory.annotation.Value("${app.amazon.recurring-enabled:true}") private boolean recurringEnabled=true;
    public AmazonManualListingSync(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Availability(boolean allowed, String message,boolean running,Instant lastCompleted,String stage) {
        public Availability(boolean allowed,String message,boolean running,Instant lastCompleted){this(allowed,message,running,lastCompleted,running?"RUNNING":"IDLE");}
        public Availability(boolean allowed,String message){this(allowed,message,false,null);}
        public String statusLabel(){return "DISABLED".equals(stage)?"● Automatic sync off":"QUEUED".equals(stage)?"● Queued":running?"● Refreshing":lastCompleted==null?"● Not synced":"● Synced";}
        public String lastCheck(){return lastCompleted==null?"Amazon SKU check · Not yet completed":"Last Amazon SKU check · "+
            java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy · HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC).format(lastCompleted);}
    }

    @Transactional(readOnly=true)
    public Availability availability(UUID tenant, UUID connection) {
        setTenant(tenant);
        return jdbc.query("""
            SELECT c.status,
              EXISTS(SELECT 1 FROM marketplace_sync_jobs j WHERE j.tenant_id=c.tenant_id
                AND j.marketplace_connection_id=c.id AND j.job_type='LISTINGS_SNAPSHOT'
                AND j.status IN ('QUEUED','RUNNING','WAITING')),
              (SELECT max(j.completed_at) FROM marketplace_sync_jobs j WHERE j.tenant_id=c.tenant_id
                AND j.marketplace_connection_id=c.id AND j.job_type='LISTINGS_SNAPSHOT' AND j.status='COMPLETED'),
              EXISTS(SELECT 1 FROM marketplace_sync_schedules s WHERE s.tenant_id=c.tenant_id
                AND s.marketplace_connection_id=c.id AND s.schedule_key='LISTINGS' AND s.enabled AND s.next_run_at<=now()),
              EXISTS(SELECT 1 FROM marketplace_sync_jobs j WHERE j.tenant_id=c.tenant_id
                AND j.marketplace_connection_id=c.id AND j.job_type='LISTINGS_SNAPSHOT'
                AND j.status='RUNNING' AND j.lease_expires_at>clock_timestamp())
            FROM marketplace_connections c WHERE c.tenant_id=? AND c.id=? AND c.channel='AMAZON'
            """,rs->{
                if(!rs.next() || !"ACTIVE".equals(rs.getString(1))) return new Availability(false,"Verify the Amazon connection before syncing.");
                var completed=rs.getTimestamp(3);
                Instant last=completed==null?null:completed.toInstant();
                return status(workerEnabled,recurringEnabled||localSafety.local(),localSafety.local()?false:rs.getBoolean(2),rs.getBoolean(5),localSafety.local()?false:rs.getBoolean(4),last);
            },tenant,connection);
    }

    static Availability status(boolean worker,boolean recurring,boolean pending,boolean live,boolean due,Instant last){
        if(!worker||!recurring)return new Availability(false,"Automatic Amazon listing collection is disabled in this environment. Existing listings are retained.",false,last,"DISABLED");
        if(live)return new Availability(false,"Amazon SKU sync is running. Refresh to see updated listings.",true,last,"RUNNING");
        if(pending||due)return new Availability(false,"Amazon SKU sync is queued; it has not started processing.",false,last,"QUEUED");
        if(last!=null&&last.plusSeconds(300).isAfter(Instant.now()))return new Availability(false,"Amazon SKUs were recently synced. Try again in five minutes.",false,last);
        return new Availability(true,"Refresh listings from Amazon.",false,last);
    }

    @Transactional
    public void request(UUID tenant, UUID connection) {
        setTenant(tenant);
        // Serializes manual requests. The scheduler takes the same schedule row lock.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,53))",rs->{},connection);
        jdbc.query("SELECT schedule_key FROM marketplace_sync_schedules WHERE tenant_id=? AND marketplace_connection_id=? AND schedule_key='LISTINGS' FOR UPDATE",rs->{},tenant,connection);
        var current=availability(tenant,connection);
        if(!current.allowed()) throw new IllegalStateException(current.message());
        if(localSafety.local()){
            localSafety.requireManualRead();
            UUID run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end,current_stage,user_message) VALUES (?,?,'RECONCILIATION','LISTINGS',now(),now(),'LISTINGS_SNAPSHOT','Manually refreshing Amazon listings') RETURNING id",UUID.class,tenant,connection);
            jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,required_for_ready) VALUES (?,?,?,'LISTINGS_SNAPSHOT',1,false),(?,?,?,'FINAL_RECONCILIATION',2,false)",tenant,run,connection,tenant,run,connection);
            localSafety.allowRun(run);return;
        }
        jdbc.update("""
            INSERT INTO marketplace_sync_schedules(tenant_id,marketplace_connection_id,schedule_key,
                run_type,cadence_seconds,lookback_seconds,priority,next_run_at)
            VALUES(?,?,'LISTINGS','RECONCILIATION',86400,0,70,now())
            ON CONFLICT(tenant_id,marketplace_connection_id,schedule_key) DO UPDATE
                SET enabled=true,next_run_at=now(),updated_at=now()
            """,tenant,connection);
    }

    private void setTenant(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    }
}
