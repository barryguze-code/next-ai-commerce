package com.nextaicommerce.platform.sync;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AmazonManualOrderSync {
    private static final Duration COOLDOWN=Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    public AmazonManualOrderSync(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Availability(boolean allowed,Instant lastCompleted,Instant nextAllowed,UUID activeRun){}
    public record Progress(String state,int percent,String phase,String error,int changedRows){}

    @Transactional(readOnly=true)
    public Availability availability(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        Instant last=jdbc.query("""
            SELECT max(job.completed_at) FROM marketplace_sync_jobs job
            WHERE job.tenant_id=? AND job.marketplace_connection_id=?
              AND job.job_type IN ('ORDERS_API_DELTA','ORDERS_30_DAY') AND job.status='COMPLETED'
            """,rs->rs.next()&&rs.getTimestamp(1)!=null?rs.getTimestamp(1).toInstant():null,tenantId,connectionId);
        UUID active=jdbc.query("""
            SELECT run.id FROM marketplace_sync_runs run
            WHERE run.tenant_id=? AND run.marketplace_connection_id=? AND run.sync_profile='ORDER_CHANGES'
              AND run.status IN ('QUEUED','RUNNING') ORDER BY run.created_at DESC LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId,connectionId);
        Instant next=last==null?null:last.plus(COOLDOWN);
        return new Availability(active==null&&(next==null||!Instant.now().isBefore(next)),last,next,active);
    }

    @Transactional
    public UUID enqueue(UUID tenantId,UUID connectionId){
        setTenant(tenantId);jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,52))",rs->{},connectionId);
        Availability availability=availability(tenantId,connectionId);
        if(availability.activeRun()!=null)return availability.activeRun();
        if(!availability.allowed())throw new IllegalStateException("Amazon orders were checked less than five minutes ago.");
        Instant end=Instant.now().minusSeconds(120),fallback=end.minusSeconds(24*60*60L);
        Instant watermark=jdbc.query("""
            SELECT high_watermark FROM amazon_sync_watermarks
            WHERE tenant_id=? AND marketplace_connection_id=? AND dataset='ORDER_CHANGES'
            """,rs->rs.next()&&rs.getTimestamp(1)!=null?rs.getTimestamp(1).toInstant():null,tenantId,connectionId);
        Instant start=watermark==null||!watermark.isBefore(end)?fallback:watermark.minusSeconds(300);
        if(start.isBefore(fallback))start=fallback;
        UUID runId=jdbc.queryForObject("""
            INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,
                window_start,window_end,current_stage,user_message)
            VALUES(?,?,'INCREMENTAL','ORDER_CHANGES',?,?,'ORDERS_API_DELTA','Checking Amazon for new and changed orders')
            RETURNING id
            """,UUID.class,tenantId,connectionId,Timestamp.from(start),Timestamp.from(end));
        jdbc.update("""
            INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,required_for_ready)
            VALUES(?,?,?,'ORDERS_API_DELTA',1,false),(?,?,?,'FINAL_RECONCILIATION',2,false)
            """,tenantId,runId,connectionId,tenantId,runId,connectionId);
        return runId;
    }

    @Transactional(readOnly=true)
    public Progress progress(UUID tenantId,UUID connectionId,UUID runId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT run.status,run.user_message,
              (SELECT status FROM marketplace_sync_jobs WHERE tenant_id=run.tenant_id AND sync_run_id=run.id AND job_type='ORDERS_API_DELTA'),
              (SELECT status FROM marketplace_sync_jobs WHERE tenant_id=run.tenant_id AND sync_run_id=run.id AND job_type='FINAL_RECONCILIATION'),
              (SELECT records_processed FROM marketplace_sync_jobs WHERE tenant_id=run.tenant_id AND sync_run_id=run.id AND job_type='ORDERS_API_DELTA'),
              (SELECT error_message FROM marketplace_sync_jobs WHERE tenant_id=run.tenant_id AND sync_run_id=run.id AND status='FAILED' ORDER BY sequence_number LIMIT 1)
            FROM marketplace_sync_runs run WHERE run.tenant_id=? AND run.marketplace_connection_id=? AND run.id=?
            """,rs->{if(!rs.next())return null;String state=rs.getString(1),delta=rs.getString(3),finish=rs.getString(4);
                int percent="COMPLETED".equals(state)||"COMPLETED_WITH_WARNINGS".equals(state)?100:
                    "COMPLETED".equals(finish)?98:"COMPLETED".equals(delta)?82:"RUNNING".equals(delta)?35:8;
                String phase=percent==100?rs.getString(2):"COMPLETED".equals(delta)?"Refreshing inventory reservations":"RUNNING".equals(delta)?"Reading new and changed Amazon orders":"Waiting to start the Amazon order check";
                return new Progress(state,percent,phase,rs.getString(6),rs.getInt(5));},tenantId,connectionId,runId);
    }

    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
}
