package com.nextaicommerce.platform.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Backfills operational tables from immutable source documents without another Amazon call. */
@Component
public class AmazonSourceReprocessor {
    private static final Logger log=LoggerFactory.getLogger(AmazonSourceReprocessor.class);
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    private final AmazonReportNormalizer normalizer; private final ObjectMapper json;
    private final String workerId="source-reprocessor-"+UUID.randomUUID();

    public AmazonSourceReprocessor(JdbcTemplate jdbc,TransactionTemplate transactions,
            AmazonReportNormalizer normalizer,ObjectMapper json){
        this.jdbc=jdbc;this.transactions=transactions;this.normalizer=normalizer;this.json=json;
    }

    @Scheduled(fixedDelayString="${app.amazon.reprocessor-delay-ms:2000}",initialDelayString="${app.amazon.reprocessor-initial-delay-ms:5000}")
    public void reprocessOne(){
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(rs,row)->rs.getObject(1,UUID.class));
        for(UUID tenant:tenants){
            Source source=transactions.execute(status->{setTenant(tenant);return findOne(tenant);});
            if(source==null)continue;
            try{
                long records=transactions.execute(status->{setTenant(tenant);
                    long result=source.textPayload()!=null
                        ?normalizer.normalize(source.job(),source.textPayload(),source.id())
                        :normalizer.normalizeJson(source.job(),readJson(source.jsonPayload()),source.id());
                    jdbc.update("""
                        UPDATE amazon_source_documents SET normalized_at=now(),record_count=?,
                        normalization_attempt_count=normalization_attempt_count+1,normalization_error=NULL,
                        normalization_lease_owner=NULL,normalization_lease_expires_at=NULL
                        WHERE tenant_id=? AND id=?
                        """,result,tenant,source.id());
                    return result;
                });
                log.info("Amazon source document normalized: dataset={} connection={} source={} records={}",
                    source.job().type(),source.job().connectionId(),source.id(),records);
            }catch(Exception ex){
                transactions.executeWithoutResult(status->{setTenant(tenant);jdbc.update("""
                    UPDATE amazon_source_documents SET normalization_attempt_count=normalization_attempt_count+1,
                    normalization_error=?,normalization_lease_owner=NULL,normalization_lease_expires_at=NULL
                    WHERE tenant_id=? AND id=?
                    """,truncate(ex.getMessage()),tenant,source.id());});
                log.warn("Amazon source normalization will retry: dataset={} source={} reason={}",
                    source.job().type(),source.id(),ex.getClass().getSimpleName());
            }
            return;
        }
    }

    private Source findOne(UUID tenant){
        Source source=jdbc.query("""
            SELECT d.id,d.text_payload,d.payload::text,j.id,j.sync_run_id,j.marketplace_connection_id,j.job_type,
                   r.sync_profile,c.marketplace_identifier,r.window_start,r.window_end,j.cursor->>'reportId',j.attempt_count,
                   j.failure_count,j.required_for_ready
            FROM amazon_source_documents d
            JOIN marketplace_sync_jobs j ON j.tenant_id=d.tenant_id AND j.id=d.sync_job_id
            JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
            JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
            WHERE d.tenant_id=? AND d.normalized_at IS NULL AND d.normalization_attempt_count<5
              AND (d.normalization_lease_expires_at IS NULL OR d.normalization_lease_expires_at<now())
              AND d.source_type IN ('LISTINGS_SNAPSHOT','ORDERS_30_DAY','INVENTORY_SNAPSHOT',
                  'INVENTORY_LEDGER_30_DAY','FBA_CUSTOMER_SHIPMENTS_30_DAY','RETURNS_30_DAY',
                  'FINANCES_30_DAY','REIMBURSEMENTS_30_DAY','FEES_SNAPSHOT')
            ORDER BY d.received_at FOR UPDATE OF d SKIP LOCKED LIMIT 1
            """,rs->{if(!rs.next())return null;
                Timestamp start=rs.getTimestamp(10),end=rs.getTimestamp(11);
                var job=new AmazonSyncStore.Job(rs.getObject(4,UUID.class),tenant,rs.getObject(5,UUID.class),
                    rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8),rs.getString(9),start.toInstant(),end.toInstant(),
                    rs.getString(12),rs.getInt(13),rs.getInt(14),rs.getBoolean(15),null);
                return new Source(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),job);
            },tenant);
        if(source!=null)jdbc.update("""
            UPDATE amazon_source_documents SET normalization_lease_owner=?,
            normalization_lease_expires_at=now()+interval '5 minutes' WHERE tenant_id=? AND id=?
            """,workerId,tenant,source.id());
        return source;
    }

    private tools.jackson.databind.JsonNode readJson(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalArgumentException("Stored Amazon JSON is invalid.",ex);}}
    private void setTenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private static String truncate(String value){String safe=value==null?"Normalization failed":value;return safe.substring(0,Math.min(500,safe.length()));}
    private record Source(UUID id,String textPayload,String jsonPayload,AmazonSyncStore.Job job){}
}
