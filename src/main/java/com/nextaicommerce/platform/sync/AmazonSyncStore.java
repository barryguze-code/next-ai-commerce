package com.nextaicommerce.platform.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Repository
public class AmazonSyncStore {
    private static final Logger log=LoggerFactory.getLogger(AmazonSyncStore.class);
    public record Job(UUID id, UUID tenantId, UUID runId, UUID connectionId, String type,
        String profile, String marketplaceId, Instant windowStart, Instant windowEnd, String reportId, int attempts,
        int failures, boolean required, Instant reportRequestedAt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public AmazonSyncStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc=jdbc; this.transactions=transactions;
    }

    public Job claim(String worker) {
        return claim(worker,false);
    }

    /** Keeps interactive order refreshes moving while a larger report is downloading or importing. */
    public Job claimOrderRefresh(String worker) {
        return claim(worker,true);
    }

    private Job claim(String worker,boolean orderRefreshOnly) {
        List<UUID> tenants = jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",
            (rs,row)->rs.getObject(1,UUID.class));
        for (UUID tenant : tenants) {
            Job claimed = transactions.execute(status -> claimForTenant(tenant,worker,orderRefreshOnly));
            if (claimed != null) return claimed;
        }
        return null;
    }

    private Job claimForTenant(UUID tenant,String worker,boolean orderRefreshOnly) {
        setTenant(tenant);
        List<Job> jobs = jdbc.query("""
            SELECT j.id,j.tenant_id,j.sync_run_id,j.marketplace_connection_id,j.job_type,r.sync_profile,
                   c.marketplace_identifier,r.window_start,r.window_end,j.cursor->>'reportId',j.attempt_count,
                   j.failure_count,j.required_for_ready,
                   (SELECT requested.created_at FROM amazon_report_requests requested
                    WHERE requested.tenant_id=j.tenant_id AND requested.sync_job_id=j.id
                    ORDER BY requested.created_at DESC LIMIT 1)
            FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.id=j.sync_run_id
            JOIN marketplace_connections c ON c.id=j.marketplace_connection_id AND c.tenant_id=j.tenant_id
            WHERE j.tenant_id=? AND (
                (j.status IN ('QUEUED','WAITING') AND j.available_at<=now()
                    AND (j.lease_expires_at IS NULL OR j.lease_expires_at<now()))
                OR (j.status='RUNNING' AND j.lease_expires_at<now())
              )
                  AND (?=false OR r.sync_profile='ORDER_CHANGES')
                  AND NOT EXISTS (SELECT 1 FROM marketplace_sync_jobs prior WHERE prior.sync_run_id=j.sync_run_id
                  AND prior.sequence_number<j.sequence_number AND prior.status NOT IN ('COMPLETED','SKIPPED')
                  AND NOT (j.job_type<>'FINAL_RECONCILIATION'
                      AND prior.required_for_ready=false AND prior.status='WAITING'))
            ORDER BY CASE r.sync_profile
                       WHEN 'ORDER_CHANGES' THEN 0
                       WHEN 'STARTUP_ORDERS' THEN 2
                       ELSE 1
                     END,
                     j.created_at,j.sequence_number
            FOR UPDATE OF j SKIP LOCKED LIMIT 1
            """, (rs,row)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),
                rs.getTimestamp(8).toInstant(),rs.getTimestamp(9).toInstant(),rs.getString(10),rs.getInt(11),
                rs.getInt(12),rs.getBoolean(13),rs.getTimestamp(14)==null?null:rs.getTimestamp(14).toInstant()),
            tenant,orderRefreshOnly);
        if (jobs.isEmpty()) return null;
        Job job=jobs.getFirst();
        jdbc.update("""
            UPDATE marketplace_sync_jobs SET status='RUNNING',lease_owner=?,lease_expires_at=now()+interval '3 minutes',
            attempt_count=attempt_count+1,started_at=coalesce(started_at,now()) WHERE tenant_id=? AND id=?
            """, worker,tenant,job.id());
        jdbc.update("""
            UPDATE marketplace_sync_runs SET
            status=CASE WHEN status IN ('COMPLETED','COMPLETED_WITH_WARNINGS') THEN status ELSE 'RUNNING' END,
            started_at=coalesce(started_at,now()),
            current_stage=CASE WHEN status IN ('COMPLETED','COMPLETED_WITH_WARNINGS') THEN current_stage ELSE ? END,
            user_message=CASE WHEN status IN ('COMPLETED','COMPLETED_WITH_WARNINGS') THEN user_message ELSE ? END
            WHERE tenant_id=? AND id=?
            """,job.type(),message(job.type()),tenant,job.runId());
        return job;
    }

    public void reportRequested(Job job, String reportType, String reportId) {
        transactions.executeWithoutResult(status->{ setTenant(job.tenantId());
            jdbc.update("""
                UPDATE marketplace_sync_jobs SET status='WAITING',cursor=jsonb_build_object('reportId',?::text),
                available_at=now()+interval '30 seconds',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?
                """,
                reportId,job.tenantId(),job.id());
            jdbc.update("""
                INSERT INTO amazon_report_requests(tenant_id,marketplace_connection_id,sync_job_id,report_type,
                amazon_report_id,status,data_start_time,data_end_time) VALUES(?,?,?,?,?,'REQUESTED',?,?)
                ON CONFLICT(tenant_id,marketplace_connection_id,amazon_report_id) DO NOTHING
                """,
                job.tenantId(),job.connectionId(),job.id(),reportType,reportId,
                Timestamp.from(job.windowStart()),Timestamp.from(job.windowEnd()));
        });
    }

    public void waitForReport(Job job, String processingStatus, Duration delay, boolean delayed, boolean nonBlocking) {
        transactions.executeWithoutResult(status->{ setTenant(job.tenantId());
            jdbc.update("""
                UPDATE marketplace_sync_jobs SET status='WAITING',available_at=?,lease_owner=NULL,lease_expires_at=NULL,
                error_code=?,error_message=? WHERE tenant_id=? AND id=?
                """,Timestamp.from(Instant.now().plus(delay)),delayed?"AMAZON_REPORT_DELAYED":null,
                delayed?(nonBlocking?"Delayed by Amazon — other setup steps are continuing.":
                    "Delayed by Amazon — setup will continue if this reaches one hour."):null,
                job.tenantId(),job.id());
            jdbc.update("UPDATE amazon_report_requests SET status=? WHERE tenant_id=? AND amazon_report_id=?",
                processingStatus,job.tenantId(),job.reportId());
        });
    }

    public void skipUnavailableReport(Job job,String processingStatus,String reason) {
        transactions.executeWithoutResult(status->{setTenant(job.tenantId());
            jdbc.update("""
                UPDATE marketplace_sync_jobs SET status='SKIPPED',completed_at=now(),lease_owner=NULL,
                    lease_expires_at=NULL,error_code=?,error_message=? WHERE tenant_id=? AND id=?
                """,processingStatus,truncate(reason),job.tenantId(),job.id());
            jdbc.update("UPDATE amazon_report_requests SET status=?,processing_completed_at=now() WHERE tenant_id=? AND amazon_report_id=?",
                processingStatus,job.tenantId(),job.reportId());
            jdbc.update("UPDATE marketplace_sync_runs SET user_message='An optional Amazon report was unavailable; remaining updates will continue' WHERE tenant_id=? AND id=?",
                job.tenantId(),job.runId());
            jdbc.update("""
                UPDATE marketplace_sync_schedules SET last_error=?,updated_at=now()
                WHERE tenant_id=? AND marketplace_connection_id=? AND schedule_key=?
                """,truncate(reason),job.tenantId(),job.connectionId(),job.profile());
            log.warn("Amazon optional report skipped: profile={} stage={} connection={} status={} reason={}",
                job.profile(),job.type(),job.connectionId(),processingStatus,truncate(reason));
        });
    }

    public void saveDocumentAndComplete(Job job, String documentId, String text, String requestId,
            ToLongFunction<UUID> importer) {
        transactions.executeWithoutResult(status->{ setTenant(job.tenantId());
            String hash=sha256(text);
            UUID reportRequest=jdbc.query("SELECT id FROM amazon_report_requests WHERE tenant_id=? AND amazon_report_id=?",
                rs->rs.next()?rs.getObject(1,UUID.class):null,job.tenantId(),job.reportId());
            UUID sourceDocument=jdbc.queryForObject("""
                INSERT INTO amazon_source_documents(tenant_id,marketplace_connection_id,sync_job_id,
                report_request_id,source_type,amazon_request_id,source_key,content_type,sha256,text_payload,record_count)
                VALUES(?,?,?,?,?,?,?,?,?,?,0)
                ON CONFLICT(tenant_id,marketplace_connection_id,source_type,sha256) DO UPDATE SET
                    sync_job_id=EXCLUDED.sync_job_id,report_request_id=EXCLUDED.report_request_id,
                    amazon_request_id=EXCLUDED.amazon_request_id,source_key=EXCLUDED.source_key
                RETURNING id
                """,
                UUID.class,
                job.tenantId(),job.connectionId(),job.id(),reportRequest,job.type(),requestId,documentId,
                "text/tab-separated-values",hash,text);
            long records=importer.applyAsLong(sourceDocument);
            jdbc.update("UPDATE amazon_source_documents SET record_count=?,normalized_at=now(),normalization_error=NULL WHERE tenant_id=? AND id=?",
                records,job.tenantId(),sourceDocument);
            jdbc.update("UPDATE amazon_report_requests SET status='DONE',amazon_document_id=?,processing_completed_at=now() WHERE tenant_id=? AND amazon_report_id=?",
                documentId,job.tenantId(),job.reportId());
            completeInside(job,records);
        });
    }

    public void saveJsonAndComplete(Job job, String sourceKey, String body, String requestId,
            ToLongFunction<UUID> importer) {
        transactions.executeWithoutResult(status->{ setTenant(job.tenantId());
            UUID sourceDocument=jdbc.queryForObject("""
                INSERT INTO amazon_source_documents(tenant_id,marketplace_connection_id,sync_job_id,
                source_type,amazon_request_id,source_key,content_type,sha256,payload,record_count)
                VALUES(?,?,?,?,?,?,?, ?,CAST(? AS jsonb),0)
                ON CONFLICT(tenant_id,marketplace_connection_id,source_type,sha256) DO UPDATE SET
                    sync_job_id=EXCLUDED.sync_job_id,amazon_request_id=EXCLUDED.amazon_request_id,
                    source_key=EXCLUDED.source_key
                RETURNING id
                """,
                UUID.class,
                job.tenantId(),job.connectionId(),job.id(),job.type(),requestId,sourceKey,
                "application/json",sha256(body),body);
            long records=importer.applyAsLong(sourceDocument);
            jdbc.update("UPDATE amazon_source_documents SET record_count=?,normalized_at=now(),normalization_error=NULL WHERE tenant_id=? AND id=?",
                records,job.tenantId(),sourceDocument);
            completeInside(job,records);
        });
    }

    public long saveJsonPage(Job job,String sourceKey,String body,String requestId,
            ToLongFunction<UUID> importer){
        Long result=transactions.execute(status->{setTenant(job.tenantId());
            UUID sourceDocument=jdbc.queryForObject("""
                INSERT INTO amazon_source_documents(tenant_id,marketplace_connection_id,sync_job_id,
                source_type,amazon_request_id,source_key,content_type,sha256,payload,record_count)
                VALUES(?,?,?,?,?,?,?, ?,CAST(? AS jsonb),0)
                ON CONFLICT(tenant_id,marketplace_connection_id,source_type,sha256) DO UPDATE SET
                    sync_job_id=EXCLUDED.sync_job_id,amazon_request_id=EXCLUDED.amazon_request_id,
                    source_key=EXCLUDED.source_key
                RETURNING id
                """,UUID.class,job.tenantId(),job.connectionId(),job.id(),job.type(),requestId,sourceKey,
                "application/json",sha256(body),body);
            long records=importer.applyAsLong(sourceDocument);
            jdbc.update("""
                UPDATE amazon_source_documents SET record_count=?,normalized_at=now(),
                normalization_error=NULL WHERE tenant_id=? AND id=?
                """,records,job.tenantId(),sourceDocument);
            return records;
        });
        return result==null?0:result;
    }

    public void complete(Job job, long records) {
        transactions.executeWithoutResult(status->{setTenant(job.tenantId());completeInside(job,records);});
    }
    private void completeInside(Job job,long records) {
        jdbc.update("""
            UPDATE marketplace_sync_jobs SET status='COMPLETED',records_processed=?,completed_at=now(),
            lease_owner=NULL,lease_expires_at=NULL,error_code=NULL,error_message=NULL
            WHERE tenant_id=? AND id=?
            """,records,job.tenantId(),job.id());
        log.info("{} [{}]: {} complete — {} row(s) processed.",readableProfile(job.profile()),
            shortRun(job),readableStage(job.type()),records);
        updateRun(job);
    }
    private void updateRun(Job job) {
        int percent=jdbc.query("SELECT job_type,status FROM marketplace_sync_jobs WHERE tenant_id=? AND sync_run_id=?",
            rs->{int weighted=0;while(rs.next())if("COMPLETED".equals(rs.getString(2))||"SKIPPED".equals(rs.getString(2)))weighted+=AmazonSyncProgress.weight(rs.getString(1));return Math.min(100,weighted);},
            job.tenantId(),job.runId());
        jdbc.update("UPDATE marketplace_sync_runs SET progress_percent=?,user_message=? WHERE tenant_id=? AND id=?",
            percent, percent==100?"Amazon data is ready":"Preparing your Amazon data",job.tenantId(),job.runId());
        Integer requiredRemaining=jdbc.queryForObject("""
            SELECT count(*) FROM marketplace_sync_jobs
            WHERE tenant_id=? AND sync_run_id=? AND required_for_ready=true
              AND status NOT IN ('COMPLETED','SKIPPED')
            """,Integer.class,job.tenantId(),job.runId());
        if(requiredRemaining!=null&&requiredRemaining==0){
            jdbc.update("UPDATE marketplace_connections SET status='ACTIVE',last_synced_at=now() WHERE tenant_id=? AND id=?",
                job.tenantId(),job.connectionId());
            jdbc.update("UPDATE marketplace_sync_runs SET user_message='Historical Amazon data is still being processed' WHERE tenant_id=? AND id=? AND status<>'COMPLETED'",
                job.tenantId(),job.runId());
        }
    }

    public void finishRun(Job job,String completionMessage) {
        transactions.executeWithoutResult(status->{setTenant(job.tenantId());completeInside(job,0);
            Integer skipped=jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=? AND sync_run_id=? AND status='SKIPPED'",
                Integer.class,job.tenantId(),job.runId());
            boolean warnings=skipped!=null&&skipped>0;
            jdbc.update("UPDATE marketplace_sync_runs SET status=?,progress_percent=100,user_message=?,completed_at=now() WHERE tenant_id=? AND id=?",
                warnings?"COMPLETED_WITH_WARNINGS":"COMPLETED",
                warnings?"Amazon data refreshed with some optional data unavailable":completionMessage,
                job.tenantId(),job.runId());
            jdbc.update("UPDATE marketplace_connections SET status='ACTIVE',last_synced_at=now() WHERE tenant_id=? AND id=?",job.tenantId(),job.connectionId());
            jdbc.update("""
                UPDATE marketplace_sync_schedules schedule SET last_success_at=CASE WHEN ? THEN last_success_at ELSE now() END,
                    last_error=CASE WHEN ? THEN last_error ELSE NULL END,updated_at=now()
                FROM marketplace_sync_runs run
                WHERE run.tenant_id=? AND run.id=? AND schedule.tenant_id=run.tenant_id
                  AND schedule.marketplace_connection_id=run.marketplace_connection_id
                  AND schedule.schedule_key=run.sync_profile
                """,warnings,warnings,job.tenantId(),job.runId());
            jdbc.update("""
                INSERT INTO amazon_sync_watermarks(tenant_id,marketplace_connection_id,dataset,
                    high_watermark,last_success_at,last_reconciliation_at)
                SELECT run.tenant_id,run.marketplace_connection_id,run.sync_profile,run.window_end,now(),
                    CASE WHEN run.run_type='RECONCILIATION' THEN now() ELSE NULL END
                FROM marketplace_sync_runs run WHERE run.tenant_id=? AND run.id=?
                  AND NOT EXISTS (SELECT 1 FROM marketplace_sync_jobs incomplete
                    WHERE incomplete.tenant_id=run.tenant_id AND incomplete.sync_run_id=run.id
                      AND incomplete.status<>'COMPLETED')
                ON CONFLICT(tenant_id,marketplace_connection_id,dataset) DO UPDATE SET
                    high_watermark=greatest(amazon_sync_watermarks.high_watermark,EXCLUDED.high_watermark),
                    last_success_at=EXCLUDED.last_success_at,
                    last_reconciliation_at=coalesce(EXCLUDED.last_reconciliation_at,
                        amazon_sync_watermarks.last_reconciliation_at),updated_at=now()
                """,job.tenantId(),job.runId());
            log.info("{} [{}] {}.",readableProfile(job.profile()),shortRun(job),
                warnings?"finished with unavailable data; successful sync position retained":"finished successfully");
        });
    }

    public void retryOrFail(Job job, Throwable error, Duration retryAfter) {
        transactions.executeWithoutResult(status->{setTenant(job.tenantId());
            boolean transientFailure=retryAfter!=null || job.failures()+1<8;
            Duration delay=retryAfter!=null?retryAfter:Duration.ofSeconds(Math.min(900,30L*(1L<<Math.min(5,job.failures()))));
            if(transientFailure)log.warn("{} [{}] will retry {} in {} second(s) — attempt {} failed: {}.",
                readableProfile(job.profile()),shortRun(job),readableStage(job.type()),delay.toSeconds(),
                job.failures()+1,truncate(error.getMessage()));
            else log.error("{} [{}] stopped after {} failed attempt(s) during {} — {}.",
                readableProfile(job.profile()),shortRun(job),job.failures()+1,readableStage(job.type()),
                truncate(error.getMessage()),error);
            jdbc.update("""
                UPDATE marketplace_sync_jobs SET status=?,available_at=?,lease_owner=NULL,lease_expires_at=NULL,
                failure_count=failure_count+1,
                error_code=?,error_message=? WHERE tenant_id=? AND id=?
                """,transientFailure?"WAITING":"FAILED",
                Timestamp.from(Instant.now().plus(delay)),error.getClass().getSimpleName(),truncate(error.getMessage()),job.tenantId(),job.id());
            if(!transientFailure && !job.required()){
                jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',completed_at=now() WHERE tenant_id=? AND id=?",job.tenantId(),job.id());
                jdbc.update("UPDATE marketplace_sync_runs SET user_message='Core Amazon data is continuing; an optional dataset was unavailable' WHERE tenant_id=? AND id=?",job.tenantId(),job.runId());
                jdbc.update("""
                    UPDATE marketplace_sync_schedules schedule SET last_error=?,updated_at=now()
                    FROM marketplace_sync_runs run WHERE run.tenant_id=? AND run.id=?
                      AND schedule.tenant_id=run.tenant_id
                      AND schedule.marketplace_connection_id=run.marketplace_connection_id
                      AND schedule.schedule_key=run.sync_profile
                    """,truncate(error.getMessage()),job.tenantId(),job.runId());
            } else if(!transientFailure){
                jdbc.update("UPDATE marketplace_sync_runs SET status='FAILED',user_message='Amazon access needs attention' WHERE tenant_id=? AND id=?",job.tenantId(),job.runId());
                jdbc.update("UPDATE marketplace_connections SET status='ERROR' WHERE tenant_id=? AND id=?",job.tenantId(),job.connectionId());
            }
        });
    }
    public void fail(Job job, Throwable error) {
        transactions.executeWithoutResult(status->{setTenant(job.tenantId());
            log.error("{} [{}] stopped during {} — {}.",readableProfile(job.profile()),shortRun(job),
                readableStage(job.type()),truncate(error.getMessage()),error);
            jdbc.update("""
                UPDATE marketplace_sync_jobs SET status='FAILED',lease_owner=NULL,lease_expires_at=NULL,
                error_code=?,error_message=?,completed_at=now() WHERE tenant_id=? AND id=?
                """,error.getClass().getSimpleName(),truncate(error.getMessage()),job.tenantId(),job.id());
            jdbc.update("UPDATE marketplace_sync_runs SET status='FAILED',user_message='Amazon access needs attention' WHERE tenant_id=? AND id=?",job.tenantId(),job.runId());
            jdbc.update("UPDATE marketplace_connections SET status='ERROR' WHERE tenant_id=? AND id=?",job.tenantId(),job.connectionId());
        });
    }
    private void setTenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private static String message(String type){return switch(type){case"VERIFY_SELLER"->"Verifying your Amazon store";case"LISTINGS_SNAPSHOT"->"Downloading your product listings";case"ORDERS_API_DELTA"->"Checking for new Amazon orders";case"ORDERS_30_DAY"->"Importing the last 30 days of orders";case"INVENTORY_SNAPSHOT","INVENTORY_LEDGER_30_DAY"->"Building your Amazon inventory history";case"RETURNS_30_DAY","REIMBURSEMENTS_30_DAY"->"Reviewing returns and reimbursements";case"FINANCES_30_DAY"->"Importing Amazon finances";default->"Preparing your Amazon data";};}
    private static String readableStage(String type){return switch(type){
        case "ORDERS_API_DELTA"->"new and changed orders";case "ORDERS_30_DAY"->"orders report";
        case "ORDER_ITEMS_30_DAY"->"order items already included; no API request";
        case "FINAL_RECONCILIATION"->"local reconciliation; no API request";
        case "LISTINGS_SNAPSHOT"->"listings report";case "INVENTORY_SNAPSHOT"->"inventory report";
        default->type.toLowerCase().replace('_',' ');};}
    private static String readableProfile(String profile){return switch(profile){
        case "ORDER_CHANGES"->"5-minute order update";case "STARTUP_ORDERS"->"application-start 30-day order refresh";
        case "RECENT_ORDER_RECONCILIATION"->"6-hour order safety reconciliation";
        case "ORDER_LIFECYCLE"->"daily 30-day order reconciliation";
        case "CURRENT_INVENTORY"->"hourly inventory refresh";default->profile.toLowerCase().replace('_',' ');};}
    private static String truncate(String value){return value==null?"Amazon synchronization failed.":value.substring(0,Math.min(500,value.length()));}
    private static String shortRun(Job job){return job.runId().toString().substring(0,8);}
    private static String sha256(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
