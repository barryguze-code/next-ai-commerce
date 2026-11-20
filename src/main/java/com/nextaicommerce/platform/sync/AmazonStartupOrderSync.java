package com.nextaicommerce.platform.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts one guarded 30-day order reconciliation after the application is ready.
 * A database advisory lock and active-run check keep clustered/restarting instances
 * from creating concurrent reports for the same Amazon connection.
 */
@Component
public class AmazonStartupOrderSync {
    private static final Logger log=LoggerFactory.getLogger(AmazonStartupOrderSync.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    AmazonStartupOrderSync(JdbcTemplate jdbc,TransactionTemplate transactions,
            @Value("${app.amazon.startup-order-reconciliation-enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.transactions=transactions;this.enabled=enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void enqueue(){
        if(!enabled)return;
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",
            (rs,row)->rs.getObject(1,UUID.class));
        for(UUID tenantId:tenants){
            try{transactions.executeWithoutResult(status->enqueueTenant(tenantId));}
            catch(RuntimeException ex){log.error("Application-start Amazon order check could not be scheduled — {}.",
                ex.getMessage(),ex);}
        }
    }

    private void enqueueTenant(UUID tenantId){
        setTenant(tenantId);
        List<Connection> connections=jdbc.query("""
            SELECT connection.id,connection.display_name FROM marketplace_connections connection
            WHERE connection.tenant_id=? AND connection.channel='AMAZON' AND connection.status='ACTIVE'
              AND EXISTS (SELECT 1 FROM marketplace_connection_credentials credential
                  WHERE credential.tenant_id=connection.tenant_id
                    AND credential.marketplace_connection_id=connection.id)
            ORDER BY connection.id
            """,(rs,row)->new Connection(rs.getObject(1,UUID.class),rs.getString(2)),tenantId);
        for(Connection connection:connections)enqueueConnection(tenantId,connection);
    }

    private void enqueueConnection(UUID tenantId,Connection connection){
        UUID connectionId=connection.id();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,41))",rs->{},connectionId);
        Integer active=jdbc.queryForObject("""
            SELECT count(*) FROM marketplace_sync_runs
            WHERE tenant_id=? AND marketplace_connection_id=? AND sync_profile='STARTUP_ORDERS'
              AND status IN ('QUEUED','RUNNING')
            """,Integer.class,tenantId,connectionId);
        if(active!=null&&active>0){
            log.info("Application-start 30-day order check skipped for {} — the same check is already running.",
                connection.name());
            return;
        }
        Integer recent=jdbc.queryForObject("""
            SELECT count(*) FROM marketplace_sync_jobs job
            JOIN marketplace_sync_runs run ON run.tenant_id=job.tenant_id AND run.id=job.sync_run_id
            WHERE job.tenant_id=? AND job.marketplace_connection_id=?
              AND job.job_type='ORDERS_30_DAY' AND job.status='COMPLETED'
              AND run.sync_profile IN ('STARTUP_ORDERS','ORDER_LIFECYCLE')
              AND run.status IN ('COMPLETED','COMPLETED_WITH_WARNINGS')
              AND job.completed_at>=now()-interval '24 hours'
            """,Integer.class,tenantId,connectionId);
        if(recent!=null&&recent>0){
            log.info("Application-start 30-day order check skipped for {} — a complete 30-day check "
                +"finished within the last 24 hours; the daily schedule remains active.",connection.name());
            return;
        }
        Instant end=Instant.now(),start=end.minusSeconds(30L*24*60*60);
        UUID runId=jdbc.queryForObject("""
            INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,
                window_start,window_end,current_stage,user_message)
            VALUES(?,?,'RECONCILIATION','STARTUP_ORDERS',?,?,
                'ORDERS_30_DAY','Refreshing the last 30 days of Amazon orders') RETURNING id
            """,UUID.class,tenantId,connectionId,Timestamp.from(start),Timestamp.from(end));
        jdbc.update("""
            INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,
                job_type,sequence_number,required_for_ready)
            VALUES(?,?,?,'ORDERS_30_DAY',1,false),(?,?,?,'FINAL_RECONCILIATION',2,false)
            """,tenantId,runId,connectionId,tenantId,runId,connectionId);
        log.info("Application-start 30-day order check queued for {} — no complete 30-day check was found "
            +"in the last 24 hours. The five-minute order refresh remains higher priority.",connection.name());
    }

    private void setTenant(UUID tenantId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
    }
    private record Connection(UUID id,String name){}
}
