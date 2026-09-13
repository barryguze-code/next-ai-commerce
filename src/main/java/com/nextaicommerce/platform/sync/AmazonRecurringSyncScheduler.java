package com.nextaicommerce.platform.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AmazonRecurringSyncScheduler {
    private static final Logger log=LoggerFactory.getLogger(AmazonRecurringSyncScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    AmazonRecurringSyncScheduler(JdbcTemplate jdbc,TransactionTemplate transactions,
            @Value("${app.amazon.recurring-enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.transactions=transactions;this.enabled=enabled;
    }

    @Scheduled(fixedDelayString="${app.amazon.scheduler-delay-ms:60000}")
    public void enqueueDueWork() {
        if(!enabled)return;
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",
            (rs,row)->rs.getObject(1,UUID.class));
        for(UUID tenantId:tenants){
            try{transactions.executeWithoutResult(status->enqueueOne(tenantId));}
            catch(RuntimeException ex){log.error("A scheduled Amazon job could not be queued — {}.",ex.getMessage(),ex);}
        }
    }

    private void enqueueOne(UUID tenantId) {
        setTenant(tenantId);
        seedSchedules(tenantId);
        List<DueSchedule> due=jdbc.query("""
            SELECT schedule.marketplace_connection_id,schedule.schedule_key,schedule.run_type,
                   schedule.cadence_seconds,schedule.lookback_seconds,connection.display_name
            FROM marketplace_sync_schedules schedule
            JOIN marketplace_connections connection
              ON connection.tenant_id=schedule.tenant_id AND connection.id=schedule.marketplace_connection_id
            WHERE schedule.tenant_id=? AND schedule.enabled=true AND schedule.next_run_at<=now()
              AND connection.channel='AMAZON' AND connection.status='ACTIVE'
              AND EXISTS (SELECT 1 FROM marketplace_connection_credentials credential
                  WHERE credential.tenant_id=schedule.tenant_id
                    AND credential.marketplace_connection_id=schedule.marketplace_connection_id)
              AND NOT EXISTS (SELECT 1 FROM marketplace_sync_runs active_run
                  WHERE active_run.tenant_id=schedule.tenant_id
                    AND active_run.marketplace_connection_id=schedule.marketplace_connection_id
                    AND active_run.status IN ('QUEUED','RUNNING')
                    AND EXISTS (SELECT 1 FROM marketplace_sync_jobs unfinished
                      WHERE unfinished.tenant_id=active_run.tenant_id AND unfinished.sync_run_id=active_run.id
                        AND unfinished.status NOT IN ('COMPLETED','SKIPPED'))
                    AND (schedule.schedule_key<>'ORDER_CHANGES'
                      OR active_run.sync_profile='ORDER_CHANGES'))
            ORDER BY schedule.priority,schedule.next_run_at
            FOR UPDATE OF schedule SKIP LOCKED LIMIT 1
            """,(rs,row)->new DueSchedule(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getLong(4),rs.getLong(5),rs.getString(6)),tenantId);
        if(due.isEmpty())return;
        DueSchedule schedule=due.getFirst();
        AmazonRecurringPlan.Schedule plan=AmazonRecurringPlan.schedules().stream()
            .filter(candidate->candidate.key().equals(schedule.key())).findFirst()
            .orElseThrow(()->new IllegalStateException("Unknown Amazon schedule "+schedule.key()));
        // Amazon order changes can arrive shortly after Seller Central shows the order. Keep the
        // upper edge two minutes behind real time, and derive the lower edge from the last
        // successful watermark with overlap so worker delays can never create an unseen gap.
        Instant end="ORDER_CHANGES".equals(schedule.key())?Instant.now().minusSeconds(120):Instant.now();
        Instant start=windowStart(tenantId,schedule,end);
        UUID runId=jdbc.queryForObject("""
            INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,
                window_start,window_end,current_stage,user_message)
            VALUES(?,?,?,?,?,?,?,'Refreshing Amazon data') RETURNING id
            """,UUID.class,tenantId,schedule.connectionId(),schedule.runType(),schedule.key(),
            Timestamp.from(start),Timestamp.from(end),plan.jobs().getFirst());
        int sequence=1;
        for(String job:plan.jobs()){
            jdbc.update("""
                INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,
                    job_type,sequence_number,required_for_ready)
                VALUES(?,?,?,?,?,false)
                """,tenantId,runId,schedule.connectionId(),job,sequence++);
        }
        jdbc.update("""
            INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,
                job_type,sequence_number,required_for_ready)
            VALUES(?,?,?,'FINAL_RECONCILIATION',?,false)
            """,tenantId,runId,schedule.connectionId(),sequence);
        jdbc.update("""
            UPDATE marketplace_sync_schedules SET last_enqueued_at=now(),last_run_id=?,
                next_run_at=now()+make_interval(secs=>cadence_seconds),updated_at=now()
            WHERE tenant_id=? AND marketplace_connection_id=? AND schedule_key=?
            """,runId,tenantId,schedule.connectionId(),schedule.key());
        log.info("Scheduled Amazon job queued for {} — {}. Window: {} to {}. Next planned run: {} ({}).",
            schedule.storeName(),taskLabel(schedule.key()),start,end,
            Instant.now().plusSeconds(schedule.cadenceSeconds()),cadenceLabel(schedule.cadenceSeconds()));
    }

    private Instant windowStart(UUID tenantId,DueSchedule schedule,Instant end){
        if(schedule.lookbackSeconds()==0)return end;
        if(!"ORDER_CHANGES".equals(schedule.key()))return end.minusSeconds(schedule.lookbackSeconds());
        Instant watermark=jdbc.query("""
            SELECT high_watermark FROM amazon_sync_watermarks
            WHERE tenant_id=? AND marketplace_connection_id=? AND dataset='ORDER_CHANGES'
            """,rs->rs.next()?rs.getTimestamp(1).toInstant():null,tenantId,schedule.connectionId());
        Instant fallback=end.minusSeconds(schedule.lookbackSeconds());
        if(watermark==null||!watermark.isBefore(end))return fallback;
        Instant overlapped=watermark.minusSeconds(5*60L);
        Instant safetyFloor=end.minusSeconds(24*60*60L);
        return overlapped.isBefore(safetyFloor)?safetyFloor:overlapped;
    }

    private void seedSchedules(UUID tenantId) {
        for(AmazonRecurringPlan.Schedule plan:AmazonRecurringPlan.schedules()){
            jdbc.update("""
                INSERT INTO marketplace_sync_schedules(tenant_id,marketplace_connection_id,schedule_key,
                    run_type,cadence_seconds,lookback_seconds,priority,next_run_at)
                SELECT connection.tenant_id,connection.id,?,?,?,?,?,
                    coalesce((SELECT max(job.completed_at)+make_interval(secs=>?)
                        FROM marketplace_sync_jobs job
                        WHERE job.tenant_id=connection.tenant_id
                          AND job.marketplace_connection_id=connection.id
                          AND job.job_type=? AND job.status='COMPLETED'),
                        now()+make_interval(secs=>?))
                FROM marketplace_connections connection
                WHERE connection.tenant_id=? AND connection.channel='AMAZON' AND connection.status='ACTIVE'
                ON CONFLICT(tenant_id,marketplace_connection_id,schedule_key) DO UPDATE SET
                    run_type=EXCLUDED.run_type,cadence_seconds=EXCLUDED.cadence_seconds,
                    lookback_seconds=EXCLUDED.lookback_seconds,priority=EXCLUDED.priority,
                    updated_at=now()
                """,plan.key(),plan.runType(),plan.cadence().toSeconds(),plan.lookback().toSeconds(),plan.priority(),
                plan.cadence().toSeconds(),plan.jobs().getFirst(),
                Math.min(plan.cadence().toSeconds(),Math.max(60,plan.priority()*30L)),tenantId);
        }
    }

    private void setTenant(UUID tenantId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
    }

    private static String cadenceLabel(long seconds){return seconds<3600?"Every "+seconds/60+" minutes":
        seconds<86400?"Every "+seconds/3600+" hours":"Daily";}
    private static String taskLabel(String key){return switch(key){
        case "ORDER_CHANGES"->"check new and changed orders using Orders API";
        case "CURRENT_INVENTORY"->"refresh Amazon inventory snapshot";
        case "RECENT_ORDER_RECONCILIATION"->"reconcile recent orders using a report";
        case "ORDER_LIFECYCLE"->"reconcile 30 days of order history using a report";
        case "LISTINGS"->"refresh listings report";case "RETURNS"->"refresh returns";
        case "FINANCES"->"refresh financial transactions";default->key.toLowerCase().replace('_',' ');};}
    private record DueSchedule(UUID connectionId,String key,String runType,long cadenceSeconds,long lookbackSeconds,String storeName){}
}
