package com.nextaicommerce.platform.sync;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fee-only budgets and publication fences in the existing queue; no enqueue or inventory writes. */
@Component
public class AmazonProductFeeJobs {
    static final int HTTP_LIMIT=500,OAUTH_LIMIT=2,SKU_ATTEMPTS=3;
    static final Duration RUN_LIMIT=Duration.ofMinutes(30),CACHE_AGE=Duration.ofHours(24);
    private static final long GLOBAL_LOCK=0x464545535f5631L;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final AmazonSyncStore store;private final long baseInterval;
    record Target(String sku,BigDecimal price,String currency) {}
    private record Row(AmazonSyncStore.Job job,ObjectNode cursor,ObjectNode fee,Instant now,Instant deadline,Instant leaseExpiry) {}
    @Autowired public AmazonProductFeeJobs(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AmazonSyncStore store,
            @Value("${app.amazon.product-fees-requests-per-second:0.5}") double rate){
        if(!Double.isFinite(rate)||rate<=0||rate>0.5)throw new IllegalArgumentException("Fee rate must be positive and at most 0.5 requests/second");
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.store=store;this.baseInterval=(long)Math.ceil(1000/rate);
    }
    private <T>T owned(AmazonSyncStore.Job job,Function<Row,T> action,T absent){
        if(job.leaseOwner()==null||!"FEES_SNAPSHOT".equals(job.type()))return absent;
        return tx.execute(s->{
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,job.tenantId().toString());
            var rows=jdbc.queryForList("""
                SELECT j.cursor,j.started_at,j.lease_expires_at,c.seller_identifier,clock_timestamp() db_now
                FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.id=j.sync_run_id AND r.tenant_id=j.tenant_id
                JOIN marketplace_connections c ON c.id=j.marketplace_connection_id AND c.tenant_id=j.tenant_id
                WHERE j.tenant_id=? AND j.id=? AND j.marketplace_connection_id=? AND j.sync_run_id=?
                  AND j.job_type='FEES_SNAPSHOT' AND c.marketplace_identifier=? AND c.channel='AMAZON' AND c.status='ACTIVE'
                  AND EXISTS(SELECT 1 FROM tenants t WHERE t.id=j.tenant_id AND t.status='ACTIVE')
                  AND j.status='RUNNING' AND j.lease_owner=? AND j.attempt_count=?
                  AND j.lease_expires_at>clock_timestamp() AND r.status NOT IN ('CANCELLED','FAILED')
                FOR UPDATE OF j,r
                """,job.tenantId(),job.id(),job.connectionId(),job.runId(),job.marketplaceId(),job.leaseOwner(),job.attempts()+1);
            if(rows.isEmpty())return absent;
            var data=rows.getFirst();JsonNode parsedCursor=json.readTree(data.get("cursor").toString());
            if(!(parsedCursor instanceof ObjectNode)){
                jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',error_code='FEE_STATE_INVALID',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",job.tenantId(),job.id());return absent;
            }
            ObjectNode cursor=(ObjectNode)parsedCursor;
            Instant now=((Timestamp)data.get("db_now")).toInstant();
            Instant started=((Timestamp)data.get("started_at")).toInstant();
            JsonNode saved=cursor.path("productFees");
            if(!saved.isMissingNode()&&(!saved.isObject()||!"FEES_V1".equals(saved.path("format").asText()))){
                jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',error_code='FEE_STATE_INVALID',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",job.tenantId(),job.id());return absent;
            }
            ObjectNode fee=saved.isMissingNode()?cursor.putObject("productFees"):(ObjectNode)saved;
            if(saved.isMissingNode())fee.put("format","FEES_V1").put("seller",data.get("seller_identifier").toString()).put("deadline",started.plus(RUN_LIMIT).toString()).put("http",0).put("oauth",0).put("skuAttempts",0);
            if(!fee.path("http").isInt()||fee.path("http").asInt()<0||fee.path("http").asInt()>HTTP_LIMIT
                    ||!fee.path("oauth").isInt()||fee.path("oauth").asInt()<0||fee.path("oauth").asInt()>OAUTH_LIMIT
                    ||!fee.path("skuAttempts").isInt()||fee.path("skuAttempts").asInt()<0||fee.path("skuAttempts").asInt()>SKU_ATTEMPTS){
                jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',error_code='FEE_STATE_INVALID',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",job.tenantId(),job.id());return absent;
            }
            Instant deadline;
            try{deadline=Instant.parse(fee.path("deadline").asText());}
            catch(RuntimeException bad){deadline=started;}
            if(deadline.isAfter(started.plus(RUN_LIMIT)))deadline=started.plus(RUN_LIMIT);
            Row row=new Row(job,cursor,fee,now,deadline,((Timestamp)data.get("lease_expires_at")).toInstant());
            if(!fee.path("seller").asText().equals(data.get("seller_identifier").toString())){stop(row,"FEE_SCOPE_CHANGED");return absent;}
            if(!now.isBefore(deadline)){stop(row,"FEE_DEADLINE");return absent;}
            return action.apply(row);
        });
    }
    private static final class FenceLost extends RuntimeException {}
    private void update(Row r,boolean withinWindow,String assignments,Object... values){
        var parameters=new ArrayList<Object>(Arrays.asList(values));
        Collections.addAll(parameters,r.job().tenantId(),r.job().id(),r.job().leaseOwner(),r.job().attempts()+1);
        if(withinWindow)parameters.add(Timestamp.from(r.deadline()));
        int written=jdbc.update("UPDATE marketplace_sync_jobs SET "+assignments+" WHERE tenant_id=? AND id=?"
            +" AND status='RUNNING' AND lease_owner=? AND attempt_count=? AND lease_expires_at>clock_timestamp()"
            +(withinWindow?" AND clock_timestamp()<?":""),parameters.toArray());
        if(written!=1)throw new FenceLost();
    }
    private void save(Row r){update(r,false,"cursor=CAST(? AS jsonb)",r.cursor().toString());}

    private void stop(Row r,String reason){
        r.fee().put("reason",reason);save(r);
        update(r,false,"status=?,error_code=?,error_message='Fee collection stopped; saved estimates preserved.',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL",
            r.job().required()?"FAILED":"SKIPPED",reason);
    }
    public void stop(AmazonSyncStore.Job j,String reason){owned(j,r->{stop(r,reason);return true;},false);}
    private void defer(Row r,Instant until){
        if(!until.isBefore(r.deadline())){stop(r,"FEE_RETRY_OUTSIDE_WINDOW");return;}
        save(r);update(r,false,"status='WAITING',available_at=?,lease_owner=NULL,lease_expires_at=NULL",Timestamp.from(until));
    }
    Target next(AmazonSyncStore.Job j){return owned(j,r->{
        if(!r.fee().path("pendingKind").asText().isEmpty()){stop(r,"FEE_REQUEST_OUTCOME_UNKNOWN");return null;}
        if(!nextAt(r).isBefore(r.deadline())){stop(r,"FEE_RATE_OUTSIDE_WINDOW");return null;}
        var rows=jdbc.queryForList("""
            SELECT l.seller_sku,l.price,l.currency FROM amazon_listings l JOIN marketplace_definitions d
              ON d.channel='AMAZON' AND d.marketplace_identifier=l.marketplace_id AND d.currency_code=l.currency
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.marketplace_id=?
              AND upper(coalesce(l.fulfillment_channel,'')) IN ('AMAZON','AMAZON_NA','AMAZON_EU','AMAZON_JP','FBA','AFN')
              AND l.price>0 AND l.seller_sku>coalesce(?,'') ORDER BY l.seller_sku LIMIT 1
            """,j.tenantId(),j.connectionId(),j.marketplaceId(),r.cursor().path("feeSku").asText(""));
        if(rows.isEmpty()){r.fee().put("reason","COMPLETED");save(r);update(r,true,"updated_at=clock_timestamp()");store.complete(j,jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",Long.class,j.tenantId(),j.id()));return null;}
        save(r);var row=rows.getFirst();return new Target((String)row.get("seller_sku"),(BigDecimal)row.get("price"),(String)row.get("currency"));
    },null);}
    ObjectNode basis(AmazonSyncStore.Job j,Target t){
        return owned(j,r->{
            ObjectNode basis=json.createObjectNode().put("MarketplaceId",j.marketplaceId()).put("IsAmazonFulfilled",true)
                .put("_platformSellerId",r.fee().path("seller").asText());
            var p=basis.putObject("PriceToEstimateFees");p.putObject("ListingPrice").put("CurrencyCode",t.currency()).put("Amount",t.price());
            p.putObject("Shipping").put("CurrencyCode",t.currency()).put("Amount",BigDecimal.ZERO);return basis;
        },null);
    }
    boolean allowed(AmazonSyncStore.Job j,String sku){return owned(j,r->{
        int attempts=sku.equals(r.fee().path("attemptSku").asText())?r.fee().path("skuAttempts").asInt():0;
        if(attempts>=SKU_ATTEMPTS||r.fee().path("http").asInt()>=HTTP_LIMIT){stop(r,"FEE_REQUEST_LIMIT");return false;}
        Instant ready=nextAt(r);if(ready.isAfter(r.now())){defer(r,ready);return false;}
        return true;
    },false);}
    boolean reuse(AmazonSyncStore.Job j,Target t,ObjectNode basis){return owned(j,r->{
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM amazon_fee_estimates f JOIN amazon_source_documents d
              ON d.id=f.source_document_id AND d.tenant_id=f.tenant_id AND d.marketplace_connection_id=f.marketplace_connection_id
            WHERE f.tenant_id=? AND f.marketplace_connection_id=? AND f.marketplace_id=? AND f.seller_sku=? AND f.currency=?
              AND f.effective_at>=? AND f.effective_at<=? AND f.fulfillment_fee>=0 AND f.referral_fee>=0 AND f.estimated_fee_total>=0
              AND f.raw_payload->>'Status'='Success' AND f.raw_payload->'_platformQuoteBasis'=CAST(? AS jsonb)
              AND f.raw_payload->'FeesEstimateIdentifier'->>'IdValue'=?
              AND f.raw_payload->'FeesEstimateIdentifier'->>'IdType'='SellerSKU'
              AND f.raw_payload->'FeesEstimateIdentifier'->>'MarketplaceId'=?
            """,Integer.class,j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),t.currency(),Timestamp.from(r.now().minus(CACHE_AGE)),Timestamp.from(r.now()),basis.toString(),t.sku(),j.marketplaceId());
        if(count==null||count==0)return false;
        r.fee().put("cacheHits",r.fee().path("cacheHits").asInt()+1);advance(r,t.sku(),0);return true;
    },false);}
    private void advance(Row r,String sku,long count){
        r.cursor().put("feeSku",sku);r.fee().put("attemptSku","").put("skuAttempts",0).remove("pendingKind");save(r);
        update(r,true,"records_processed=records_processed+?,status='WAITING',available_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL",count);
    }
    private Instant nextAt(Row r){
        var entries=jdbc.queryForList("SELECT cursor->'productFees'->>'nextAt' n FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type='FEES_SNAPSHOT'",r.job().tenantId(),r.job().connectionId());
        Instant next=Instant.MIN;for(var e:entries)if(e.get("n")!=null){try{Instant n=Instant.parse(e.get("n").toString());if(n.isAfter(next))next=n;}catch(RuntimeException bad){return r.deadline();}}return next;
    }
    private long interval(Row r){
        long value=baseInterval;
        var entries=jdbc.queryForList("SELECT cursor->'productFees'->>'intervalMs' n FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type='FEES_SNAPSHOT'",r.job().tenantId(),r.job().connectionId());
        for(var e:entries)if(e.get("n")!=null){try{value=Math.max(value,Long.parseLong(e.get("n").toString()));}catch(RuntimeException bad){return RUN_LIMIT.toMillis();}}return value;
    }
    boolean reserve(AmazonSyncStore.Job j,String sku,String kind){return owned(j,r->{
        if(!r.fee().path("pendingKind").asText().isEmpty()){stop(r,"FEE_REQUEST_OUTCOME_UNKNOWN");return false;}
        if("FEE".equals(kind)){
            Instant ready=nextAt(r);if(ready.isAfter(r.now())){defer(r,ready);return false;}
            int attempts=sku.equals(r.fee().path("attemptSku").asText())?r.fee().path("skuAttempts").asInt():0;
            if(attempts>=SKU_ATTEMPTS||r.fee().path("http").asInt()>=HTTP_LIMIT){stop(r,"FEE_REQUEST_LIMIT");return false;}
            r.fee().put("attemptSku",sku).put("skuAttempts",attempts+1).put("http",r.fee().path("http").asInt()+1)
                .put("nextAt",r.now().plusMillis(interval(r)).toString());
        }else{if(r.fee().path("oauth").asInt()>=OAUTH_LIMIT){stop(r,"FEE_OAUTH_LIMIT");return false;}r.fee().put("oauth",r.fee().path("oauth").asInt()+1);}
        r.fee().put("pendingKind",kind);save(r);return true;
    },false);}
    void received(AmazonSyncStore.Job j){owned(j,r->{r.fee().remove("pendingKind");save(r);return true;},false);}
    private Duration remaining(AmazonSyncStore.Job j){return owned(j,r->{Instant until=r.deadline().isBefore(r.leaseExpiry())?r.deadline():r.leaseExpiry();return Duration.between(r.now(),until).minusMillis(100);},Duration.ZERO);}
    /** A conservative global operation lock holds through dispatch/response. Wait before every
     * request: a crashed JVM releases the lock, and its successor still waits a full interval.
     * No cross-tenant evidence or credentials are read to coordinate this shared permit. */
    private record Pacing(Instant next,long interval) {}
    private Pacing globalPacing(AmazonSyncStore.Job job){return owned(job,r->{
        Instant next=Instant.MIN;long interval=baseInterval;
        // Same tenant iteration used by the existing background queue. Read ONLY
        // non-sensitive operation pacing; never share fee evidence or credentials.
        try{
            for(UUID tenant:jdbc.queryForList("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",UUID.class)){
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
                for(var entry:jdbc.queryForList("SELECT cursor->'productFees'->>'nextAt' n,cursor->'productFees'->>'intervalMs' i FROM marketplace_sync_jobs WHERE tenant_id=? AND job_type='FEES_SNAPSHOT' AND cursor->'productFees'->>'format'='FEES_V1'",tenant)){
                    try{
                        if(entry.get("n")!=null){Instant at=Instant.parse(entry.get("n").toString());if(at.isAfter(next))next=at;}
                        if(entry.get("i")!=null)interval=Math.max(interval,Long.parseLong(entry.get("i").toString()));
                    }catch(RuntimeException bad){return new Pacing(r.deadline(),RUN_LIMIT.toMillis());}
                }
            }
        }finally{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,job.tenantId().toString());}
        return new Pacing(next,interval);
    },null);}
    final class Permit implements AmazonSpApiClient.FeePermit,AutoCloseable {
        private final AmazonSyncStore.Job job;private final String sku;private Connection lock;private String kind;
        Permit(AmazonSyncStore.Job job,String sku){this.job=job;this.sku=sku;}
        public boolean oauth(){boolean reserved=reserve(job,sku,"OAUTH");if(reserved)kind="OAUTH";return reserved;}
        public Duration remaining(){return AmazonProductFeeJobs.this.remaining(job);}
        public boolean getAsBoolean(){
            try{
                lock=Objects.requireNonNull(jdbc.getDataSource()).getConnection();
                try(var ps=lock.prepareStatement("SELECT pg_try_advisory_lock(?)")){ps.setLong(1,GLOBAL_LOCK);try(var rs=ps.executeQuery()){rs.next();if(!rs.getBoolean(1)){lock.close();lock=null;owned(job,r->{defer(r,r.now().plusSeconds(2));return true;},false);return false;}}}
                Pacing pacing=globalPacing(job);if(pacing==null){close();return false;}
                boolean ready=owned(job,r->{
                    if(pacing.next().isAfter(r.now())){defer(r,pacing.next());return false;}
                    r.fee().put("intervalMs",Math.max(pacing.interval(),r.fee().path("intervalMs").asLong(baseInterval)));save(r);return true;
                },false);
                if(!ready){close();return false;}
                if(remaining().compareTo(Duration.ofSeconds(3))<=0){stop(job,"FEE_INSUFFICIENT_WINDOW");close();return false;}
                Thread.sleep(2000);
                if(!reserve(job,sku,"FEE")){close();return false;}kind="FEE";return true;
            }catch(InterruptedException ex){Thread.currentThread().interrupt();close();return false;}
            catch(SQLException ex){close();throw new IllegalStateException("Fee operation permit unavailable",ex);}
        }
        public void observedRate(Double rate){if("FEE".equals(kind))owned(job,r->{observe(r,rate);save(r);return true;},false);}
        public void completed(){received(job);kind=null;close();}
        public void uncertain(){/* Keep the durable marker: crash/unknown outcome must not silently replay. */close();}
        public void close(){if(lock!=null){try(var ps=lock.prepareStatement("SELECT pg_advisory_unlock(?)")){ps.setLong(1,GLOBAL_LOCK);ps.execute();}catch(SQLException ignored){try{lock.abort(Runnable::run);}catch(SQLException ignoredAgain){}}finally{try{lock.close();}catch(SQLException ignored){}lock=null;}}}
    }
    Permit permit(AmazonSyncStore.Job j,String sku){return new Permit(j,sku);}
    private UUID archive(Row r,String sku,String body,String requestId,boolean verified){
        JsonNode parsed;try{parsed=json.readTree(body);}catch(RuntimeException malformed){parsed=json.createObjectNode();}
        var created=jdbc.queryForList("""
            INSERT INTO amazon_source_documents(tenant_id,marketplace_connection_id,sync_job_id,source_type,amazon_request_id,
              source_key,content_type,sha256,payload,text_payload,record_count,normalized_at,normalization_error)
            VALUES(?,?,?,'FEES_SNAPSHOT',?,?,'application/json',?,CAST(? AS jsonb),?,?,clock_timestamp(),?)
            ON CONFLICT(tenant_id,marketplace_connection_id,source_type,sha256) DO NOTHING RETURNING id
            """,UUID.class,r.job().tenantId(),r.job().connectionId(),r.job().id(),requestId,sku,digest(body),parsed.toString(),body,
                verified?1:0,verified?null:"Fee response retained without a verified estimate");
        if(!created.isEmpty())return created.getFirst();
        return jdbc.queryForObject("SELECT id FROM amazon_source_documents WHERE tenant_id=? AND marketplace_connection_id=? AND source_type='FEES_SNAPSHOT' AND sha256=?",UUID.class,r.job().tenantId(),r.job().connectionId(),digest(body));
    }
    private static String digest(String body){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private void observe(Row r,Double rate){
        if(rate==null||!Double.isFinite(rate)||rate<=0)return;
        double ms=Math.ceil(1000/rate);long observed=ms>=Long.MAX_VALUE?Long.MAX_VALUE:(long)ms;
        long interval=Math.max(baseInterval,Math.max(observed,r.fee().path("intervalMs").asLong(baseInterval)));
        r.fee().put("intervalMs",interval);
        Instant until;try{until=r.now().plusMillis(interval);}catch(RuntimeException overflow){until=r.deadline();}
        if(until.isAfter(nextAt(r)))r.fee().put("nextAt",until.toString());
    }

    void publish(AmazonSyncStore.Job j,Target t,ObjectNode basis,JsonNode result,AmazonProductFeeSync.Amounts amounts,AmazonSpApiClient.ApiResponse response){owned(j,r->{
        observe(r,response.rateLimit());
        UUID source=archive(r,t.sku(),response.body(),response.requestId(),true);
        ObjectNode stored=(ObjectNode)result.deepCopy();stored.set("_platformQuoteBasis",basis);
        jdbc.update("""
            INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,
              estimated_fee_total,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id)
            VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)
            """,j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),t.currency(),amounts.total(),amounts.referral(),amounts.fulfillment(),Timestamp.from(r.now()),stored.toString(),source);
        advance(r,t.sku(),1);return true;
    },false);}
    void failed(AmazonSyncStore.Job j,String sku,RuntimeException error,String body,String requestId,Double rate){owned(j,r->{
        if(body!=null)archive(r,sku,body,requestId,false);observe(r,rate);
        if(error instanceof IllegalArgumentException){stop(r,"FEE_INVALID_REQUEST_SCOPE");return true;}
        if(error instanceof AmazonSpApiClient.AmazonApiException e&&(e.status()==401||e.status()==403)){stop(r,"FEE_AUTHORIZATION_REQUIRED");return true;}
        if(error instanceof AmazonSpApiClient.AmazonApiException e&&e.status()>=400&&e.status()<500&&e.status()!=429){stop(r,"FEE_UNAVAILABLE");return true;}
        if(!r.fee().path("pendingKind").asText().isEmpty()){stop(r,"FEE_REQUEST_OUTCOME_UNKNOWN");return true;}
        if(r.fee().path("skuAttempts").asInt()>=SKU_ATTEMPTS||r.fee().path("http").asInt()>=HTTP_LIMIT){stop(r,"FEE_RETRY_EXHAUSTED");return true;}
        Duration delay=error instanceof AmazonSpApiClient.AmazonApiException e&&e.retryAfter()!=null?e.retryAfter():Duration.ofSeconds(30);
        if(delay.isNegative()||delay.isZero())delay=Duration.ofSeconds(1);
        Instant retry;try{retry=r.now().plus(delay);}catch(RuntimeException overflow){retry=r.deadline();}
        Instant paced=nextAt(r);if(paced.isAfter(retry))retry=paced;
        r.fee().put("reason","RETRY");defer(r,retry);return true;
    },false);}
}
