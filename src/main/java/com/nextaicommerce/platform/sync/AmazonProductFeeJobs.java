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
    @Value("${app.amazon.product-fees-marketplace-currency-fallback:false}") private boolean currencyFallback;
    private com.nextaicommerce.platform.fees.AmazonFeeEvidence feeEvidence;
    @Autowired(required=false) void configureFeeEvidence(com.nextaicommerce.platform.fees.AmazonFeeEvidence evidence){feeEvidence=evidence;}
    record Target(String sku,BigDecimal price,String currency) {}
    private record Row(AmazonSyncStore.Job job,ObjectNode cursor,ObjectNode fee,Instant now,Instant deadline,Instant leaseExpiry) {}
    @Autowired public AmazonProductFeeJobs(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AmazonSyncStore store,
            @Value("${app.amazon.product-fees-requests-per-second:0.5}") double rate){
        if(!Double.isFinite(rate)||rate<=0||rate>0.5)throw new IllegalArgumentException("Fee rate must be positive and at most 0.5 requests/second");
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.store=store;this.baseInterval=(long)Math.ceil(1000/rate);
    }
    /** No enqueue: only an existing, operator-approved local job can acquire this lease. */
    AmazonSyncStore.Job claimPilot(UUID tenant,UUID connection,String market,UUID job,String approvedHash,String worker){
        if(approvedHash==null||!approvedHash.matches("[a-f0-9]{64}")||worker==null||worker.isBlank())return null;
        return tx.execute(status->{
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
            var rows=jdbc.queryForList("""
                SELECT j.cursor,j.started_at,c.seller_identifier,clock_timestamp() db_now
                FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id
                JOIN marketplace_connections c ON c.tenant_id=j.tenant_id AND c.id=j.marketplace_connection_id
                WHERE j.tenant_id=? AND j.marketplace_connection_id=? AND j.id=? AND j.job_type='FEES_SNAPSHOT'
                  AND c.marketplace_identifier=? AND c.channel='AMAZON' AND c.status='ACTIVE'
                  AND r.status IN ('QUEUED','RUNNING') AND EXISTS(SELECT 1 FROM tenants t WHERE t.id=j.tenant_id AND t.status='ACTIVE')
                FOR UPDATE OF j,r,c
                """,tenant,connection,job,market);
            if(rows.size()!=1)return null;var row=rows.getFirst();var cursor=json.readTree(row.get("cursor").toString());var contract=cursor.path("feeRun");
            if(!cursor.isObject()||!validRun(contract,tenant,connection,market,row.get("seller_identifier").toString())||!approvedHash.equals(digest(canonical(contract))))return null;
            Instant now=((Timestamp)row.get("db_now")).toInstant();Instant expiry=Instant.parse(contract.path("approvalExpiresAt").asText());
            if(!now.isBefore(expiry)||(cursor.has("pilotApprovalHash")&&!approvedHash.equals(cursor.path("pilotApprovalHash").asText())))return null;
            if(cursor.has("productFees")&&row.get("started_at")==null)return null;
            if(cursor.has("productFees")){
                var fee=cursor.path("productFees");if(!fee.isObject()||!"FEES_V1".equals(fee.path("format").asText()))return null;
                for(String key:List.of("http","oauth","skuAttempts"))if(!fee.path(key).isInt()||fee.path(key).asInt()<0)return null;
                if(fee.path("skuAttempts").asInt()>SKU_ATTEMPTS)return null;
            }
            if(row.get("started_at")!=null){Instant started=((Timestamp)row.get("started_at")).toInstant();
                if(!now.isBefore(runDeadline(started,contract)))return null;
                if(cursor.has("productFees")&&(!cursor.path("productFees").isObject()||!consistentRun((ObjectNode)cursor,(ObjectNode)cursor.path("productFees"),contract,started)))return null;
            }
            if(cursor.path("feeRunFrozen").asBoolean(false)&&!cursor.has("productFees"))return null;
            var leased=store.claimFeePilot(tenant,connection,market,job,worker);
            if(leased!=null)jdbc.update("UPDATE marketplace_sync_jobs SET cursor=jsonb_set(cursor,'{pilotApprovalHash}',to_jsonb(CAST(? AS text))) WHERE tenant_id=? AND id=?",approvedHash,tenant,job);
            return leased;
        });
    }
    boolean matchesPilot(AmazonSyncStore.Job job,String approvedHash){
        return owned(job,r->approvedHash!=null&&approvedHash.equals(digest(canonical(r.cursor().path("feeRun"))))
            &&approvedHash.equals(r.cursor().path("pilotApprovalHash").asText()),false);
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
            if(saved.isMissingNode()&&cursor.path("feeRunFrozen").asBoolean(false)){
                rejectRun(job,"FEE_RUN_STATE_INVALID");return absent;
            }
            ObjectNode fee=saved.isMissingNode()?cursor.putObject("productFees"):(ObjectNode)saved;
            if(saved.isMissingNode())fee.put("format","FEES_V1").put("seller",data.get("seller_identifier").toString()).put("deadline",started.plus(RUN_LIMIT).toString()).put("http",0).put("oauth",0).put("skuAttempts",0);
            if(!fee.path("http").isInt()||fee.path("http").asInt()<0||fee.path("http").asInt()>HTTP_LIMIT
                    ||!fee.path("oauth").isInt()||fee.path("oauth").asInt()<0||fee.path("oauth").asInt()>OAUTH_LIMIT
                    ||!fee.path("skuAttempts").isInt()||fee.path("skuAttempts").asInt()<0||fee.path("skuAttempts").asInt()>SKU_ATTEMPTS){
                jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',error_code='FEE_STATE_INVALID',lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=?",job.tenantId(),job.id());return absent;
            }
            boolean controlled=cursor.has("feeRun")||fee.has("runContract")||cursor.has("feeRunFrozen"),freeze=false;
            if(controlled){
                var contract=cursor.path("feeRun");
                if(!validRun(contract,job.tenantId(),job.connectionId(),job.marketplaceId(),data.get("seller_identifier").toString())){rejectRun(job,"FEE_RUN_INVALID");return absent;}
                if(cursor.has("pilotApprovalHash")&&!cursor.path("pilotApprovalHash").asText().equals(digest(canonical(contract)))){rejectRun(job,"FEE_RUN_STATE_INVALID");return absent;}
                if(!fee.has("runContract")){
                    if(cursor.has("feeRunFrozen")||fee.path("http").asInt()!=0||fee.path("oauth").asInt()!=0||fee.has("pendingKind")){
                        rejectRun(job,"FEE_RUN_STATE_INVALID");return absent;
                    }
                    fee.set("runContract",contract.deepCopy());fee.put("runContractHash",digest(canonical(contract)));
                    fee.put("runStarted",started.toString()).put("deadline",runDeadline(started,contract).toString());
                    fee.putArray("runReservations");cursor.put("feeRunFrozen",true);freeze=true;
                }
                if(!consistentRun(cursor,fee,contract,started)){rejectRun(job,"FEE_RUN_STATE_INVALID");return absent;}
            }
            Instant deadline;
            try{deadline=Instant.parse(fee.path("deadline").asText());}
            catch(RuntimeException bad){deadline=started;}
            if(deadline.isAfter(started.plus(RUN_LIMIT)))deadline=started.plus(RUN_LIMIT);
            Row row=new Row(job,cursor,fee,now,deadline,((Timestamp)data.get("lease_expires_at")).toInstant());
            if(!fee.path("seller").asText().equals(data.get("seller_identifier").toString())){stop(row,"FEE_SCOPE_CHANGED");return absent;}
            if(!now.isBefore(deadline)){stop(row,"FEE_DEADLINE");return absent;}
            if(freeze)save(row); // Freeze before any dispatch, even when this action only reads a basis.
            return action.apply(row);
        });
    }
    private void rejectRun(AmazonSyncStore.Job job,String code){
        int updated=jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',error_code=?,error_message='Fee run contract is invalid; no further requests permitted.',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=? AND status='RUNNING' AND lease_owner=? AND attempt_count=? AND lease_expires_at>clock_timestamp()",code,job.tenantId(),job.id(),job.leaseOwner(),job.attempts()+1);
        if(updated!=1)throw new FenceLost();
        jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',user_message='Fee run contract requires review',completed_at=clock_timestamp() WHERE tenant_id=? AND id=? AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs WHERE tenant_id=? AND sync_run_id=? AND status NOT IN ('COMPLETED','SKIPPED','FAILED'))",job.tenantId(),job.runId(),job.tenantId(),job.runId());
    }
    private boolean validRun(JsonNode c,UUID tenant,UUID connection,String marketplace,String seller){
        try{
            Set<String> keys=new HashSet<>();c.properties().forEach(e->keys.add(e.getKey()));
            if(!c.isObject()||!keys.equals(Set.of("format","approval","tenant","connection","marketplace","seller","skus","maxTargets","maxHttp","maxOauth","maxSeconds","basisSha256","approvalExpiresAt","publicationMode")))return false;
            if(!"FEE_RUN_V1".equals(c.path("format").asText())||!tenant.toString().equals(c.path("tenant").asText())
                ||!connection.toString().equals(c.path("connection").asText())||!marketplace.equals(c.path("marketplace").asText())
                ||!seller.equals(c.path("seller").asText())||!c.path("basisSha256").asText().matches("[a-f0-9]{64}"))return false;
            if(!"ARCHIVE_ONLY".equals(c.path("publicationMode").asText()))return false;
            UUID.fromString(c.path("approval").asText());Instant.parse(c.path("approvalExpiresAt").asText());
            Map<String,Integer> ceilings=Map.of("maxTargets",1000,"maxHttp",HTTP_LIMIT,"maxOauth",OAUTH_LIMIT,"maxSeconds",(int)RUN_LIMIT.toSeconds());
            for(var e:ceilings.entrySet())if(!c.path(e.getKey()).isInt()||c.path(e.getKey()).asInt()<1||c.path(e.getKey()).asInt()>e.getValue())return false;
            if(!c.path("skus").isArray()||c.path("skus").isEmpty()||c.path("skus").size()>c.path("maxTargets").asInt())return false;
            Set<String> skus=new HashSet<>();for(var sku:c.path("skus"))if(!sku.isString()||sku.asText().isBlank()||sku.asText().codePointCount(0,sku.asText().length())>240||!skus.add(sku.asText()))return false;
            return true;
        }catch(RuntimeException error){return false;}
    }
    private static Instant runDeadline(Instant started,JsonNode contract){
        Instant cap=started.plusSeconds(contract.path("maxSeconds").asInt()),expiry=Instant.parse(contract.path("approvalExpiresAt").asText());
        return cap.isBefore(expiry)?cap:expiry;
    }
    private boolean consistentRun(ObjectNode cursor,ObjectNode fee,JsonNode contract,Instant started){
        try{
            if(!cursor.path("feeRunFrozen").asBoolean()||!contract.equals(fee.path("runContract"))||!digest(canonical(contract)).equals(fee.path("runContractHash").asText())
                ||!started.toString().equals(fee.path("runStarted").asText())||!runDeadline(started,contract).equals(Instant.parse(fee.path("deadline").asText())))return false;
            if(!fee.path("runReservations").isArray())return false;
            int http=0,oauth=0;for(var reservation:fee.path("runReservations")){
                if(!reservation.isString())return false;
                if("FEE".equals(reservation.asText()))http++;else if("OAUTH".equals(reservation.asText()))oauth++;else return false;
            }
            if(http!=fee.path("http").asInt()||oauth!=fee.path("oauth").asInt()||http>contract.path("maxHttp").asInt()||oauth>contract.path("maxOauth").asInt())return false;
            if(fee.has("runManifest")){
                JsonNode frozen=fee.path("runManifest"),work=fee.path("batchManifest");
                if(!frozen.isArray()||!work.isArray()||frozen.size()!=contract.path("skus").size()||!basisDigest(frozen).equals(contract.path("basisSha256").asText())
                    ||!fee.path("batchIndex").isInt()||fee.path("batchIndex").asInt()<0||fee.path("batchIndex").asInt()>work.size())return false;
                Set<String> permitted=new HashSet<>(),seen=new HashSet<>();for(var sku:contract.path("skus"))permitted.add(sku.asText());
                for(var node:frozen)if(!permitted.remove(node.path("sku").asText())||!node.path("price").isNumber()||node.path("price").decimalValue().signum()<=0||!node.path("currency").asText().matches("[A-Z]{3}"))return false;
                Map<String,JsonNode> originals=new HashMap<>();frozen.forEach(n->originals.put(n.path("sku").asText(),n));
                for(var node:work)if(!node.equals(originals.get(node.path("sku").asText()))||!seen.add(node.path("sku").asText()))return false;
                if(fee.has("batchPending")&&!pendingSlice(fee))return false;
                if(fee.has("itemAttempts")){
                    if(!fee.path("itemAttempts").isObject())return false;
                    Set<String> identities=new HashSet<>();frozen.forEach(n->identities.add(targetKey(n)));
                    for(var entry:fee.path("itemAttempts").properties())if(!identities.contains(entry.getKey())||!entry.getValue().isInt()
                        ||entry.getValue().asInt()<0||entry.getValue().asInt()>SKU_ATTEMPTS)return false;
                }
            }else if(http!=0||oauth!=0)return false;
            return true;
        }catch(RuntimeException error){return false;}
    }
    private static String targetKey(JsonNode node){return digest(node.path("sku").asText()+":"+node.path("price").decimalValue().stripTrailingZeros().toPlainString()+":"+node.path("currency").asText());}
    private boolean pendingSlice(ObjectNode fee){
        var work=fee.path("batchManifest");var pending=fee.path("batchPending");int index=fee.path("batchIndex").asInt(-1);
        if(!work.isArray()||!pending.isArray()||index<0||index>=work.size()||pending.size()!=Math.min(20,work.size()-index))return false;
        for(int n=0;n<pending.size();n++)if(!pending.get(n).isString()||!targetKey(work.get(index+n)).equals(pending.get(n).asText()))return false;
        return true;
    }
    String pilotContractHash(JsonNode contract){return digest(canonical(contract));}
    private String canonical(JsonNode node){
        if(node.isObject()){Map<String,String> map=new TreeMap<>();node.properties().forEach(e->map.put(e.getKey(),canonical(e.getValue())));return json.writeValueAsString(map);}
        if(node.isArray()){List<String> list=new ArrayList<>();node.forEach(n->list.add(canonical(n)));return json.writeValueAsString(list);}
        return node.isNumber()?node.decimalValue().stripTrailingZeros().toPlainString():node.toString();
    }
    private String basisDigest(JsonNode rows){
        Map<String,List<String>> values=new TreeMap<>();for(var row:rows){String sku=row.path("sku").asText();
            if(values.put(sku,List.of(sku,row.path("price").decimalValue().stripTrailingZeros().toPlainString(),row.path("currency").asText(),"FBA","0"))!=null)throw new IllegalArgumentException("Duplicate fee basis");}
        return digest(json.writeValueAsString(values.values()));
    }
    private int cap(Row r,String key,int ceiling){return r.fee().has("runContract")?r.fee().path("runContract").path(key).asInt():ceiling;}
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
        if(r.fee().path("batchMode").asBoolean(false))jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',progress_percent=100,user_message='Fee refresh stopped; valid evidence retained',completed_at=clock_timestamp() WHERE tenant_id=? AND id=? AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs WHERE tenant_id=? AND sync_run_id=? AND status NOT IN ('COMPLETED','SKIPPED','FAILED'))",r.job().tenantId(),r.job().runId(),r.job().tenantId(),r.job().runId());
    }
    public void stop(AmazonSyncStore.Job j,String reason){owned(j,r->{stop(r,reason);return true;},false);}
    private void defer(Row r,Instant until){
        if(!until.isBefore(r.deadline())){stop(r,"FEE_RETRY_OUTSIDE_WINDOW");return;}
        save(r);update(r,false,"status='WAITING',available_at=?,lease_owner=NULL,lease_expires_at=NULL",Timestamp.from(until));
    }
    Target next(AmazonSyncStore.Job j){return owned(j,r->{
        if(r.fee().has("runContract")){stop(r,"FEE_RUN_REQUIRES_BATCH");return null;}
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
            if(r.fee().has("runContract")){
                boolean exact=false;for(var n:r.fee().path("runManifest"))if(t.sku().equals(n.path("sku").asText())&&t.currency().equals(n.path("currency").asText())&&t.price().compareTo(n.path("price").decimalValue())==0)exact=true;
                if(!exact){stop(r,"FEE_RUN_BASIS_CHANGED");return null;}
            }
            ObjectNode basis=json.createObjectNode().put("MarketplaceId",j.marketplaceId()).put("IsAmazonFulfilled",true)
                .put("_platformSellerId",r.fee().path("seller").asText());
            if(r.cursor().path("shippingBasisConfirmed").asBoolean(false))basis.put("_platformShippingApproved",true);
            var p=basis.putObject("PriceToEstimateFees");p.putObject("ListingPrice").put("CurrencyCode",t.currency()).put("Amount",t.price());
            p.putObject("Shipping").put("CurrencyCode",t.currency()).put("Amount",BigDecimal.ZERO);return basis;
        },null);
    }
    boolean allowed(AmazonSyncStore.Job j,String sku){return owned(j,r->{
        int attempts=sku.equals(r.fee().path("attemptSku").asText())?r.fee().path("skuAttempts").asInt():0;
        if(attempts>=SKU_ATTEMPTS||r.fee().path("http").asInt()>=cap(r,"maxHttp",HTTP_LIMIT)){stop(r,"FEE_REQUEST_LIMIT");return false;}
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
        if(!"FEE".equals(kind)&&!"OAUTH".equals(kind)){stop(r,"FEE_RUN_INVALID");return false;}
        if(r.fee().has("runContract")&&(!r.fee().has("runManifest")||!pendingSlice(r.fee())
            ||!sku.equals(r.fee().path("batchManifest").get(r.fee().path("batchIndex").asInt()).path("sku").asText()))){stop(r,"FEE_RUN_STATE_INVALID");return false;}
        if(!r.fee().path("pendingKind").asText().isEmpty()){stop(r,"FEE_REQUEST_OUTCOME_UNKNOWN");return false;}
        if("FEE".equals(kind)){
            Instant ready=nextAt(r);if(ready.isAfter(r.now())){defer(r,ready);return false;}
            int attempts=sku.equals(r.fee().path("attemptSku").asText())?r.fee().path("skuAttempts").asInt():0;
            if(attempts>=SKU_ATTEMPTS||r.fee().path("http").asInt()>=cap(r,"maxHttp",HTTP_LIMIT)){stop(r,"FEE_REQUEST_LIMIT");return false;}
            if(r.fee().path("batchPending").isArray()){
                var itemAttempts=r.fee().has("itemAttempts")?(ObjectNode)r.fee().path("itemAttempts"):r.fee().putObject("itemAttempts");
                for(var item:r.fee().path("batchPending"))if(itemAttempts.path(item.asText()).asInt()>=SKU_ATTEMPTS){stop(r,"FEE_ITEM_RETRY_EXHAUSTED");return false;}
                for(var item:r.fee().path("batchPending"))itemAttempts.put(item.asText(),itemAttempts.path(item.asText()).asInt()+1);
            }
            r.fee().put("attemptSku",sku).put("skuAttempts",attempts+1).put("http",r.fee().path("http").asInt()+1)
                .put("nextAt",r.now().plusMillis(interval(r)).toString());
        }else{if(r.fee().path("oauth").asInt()>=cap(r,"maxOauth",OAUTH_LIMIT)){stop(r,"FEE_OAUTH_LIMIT");return false;}r.fee().put("oauth",r.fee().path("oauth").asInt()+1);}
        if(r.fee().has("runContract"))((tools.jackson.databind.node.ArrayNode)r.fee().path("runReservations")).add(kind);
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
    private UUID archive(Row r,String sku,String body,String requestId,boolean verified){return archive(r,sku,body,requestId,verified,verified?1:0);}
    private UUID archive(Row r,String sku,String body,String requestId,boolean verified,int records){
        JsonNode parsed;try{parsed=json.readTree(body);}catch(RuntimeException malformed){parsed=json.createObjectNode();}
        var created=jdbc.queryForList("""
            INSERT INTO amazon_source_documents(tenant_id,marketplace_connection_id,sync_job_id,source_type,amazon_request_id,
              source_key,content_type,sha256,payload,text_payload,record_count,normalized_at,normalization_error)
            VALUES(?,?,?,'FEES_SNAPSHOT',?,?,'application/json',?,CAST(? AS jsonb),?,?,clock_timestamp(),?)
            ON CONFLICT(tenant_id,marketplace_connection_id,source_type,sha256) DO NOTHING RETURNING id
            """,UUID.class,r.job().tenantId(),r.job().connectionId(),r.job().id(),requestId,sku,digest(body),parsed.toString(),body,
                records,verified?null:"Fee response retained without a verified estimate");
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
        if(feeEvidence!=null)feeEvidence.quote(j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),source,digest(stored.toString()),basis,amounts.referral(),AmazonProductFeeSync.comparableFba(result.path("FeesEstimate"),t.currency()),r.now());
        advance(r,t.sku(),1);return true;
    },false);}
    void failed(AmazonSyncStore.Job j,String sku,RuntimeException error,String body,String requestId,Double rate){owned(j,r->{
        if(body!=null)archive(r,sku,body,requestId,false);observe(r,rate);
        if(error instanceof IllegalArgumentException){stop(r,"FEE_INVALID_REQUEST_SCOPE");return true;}
        if(error instanceof AmazonSpApiClient.AmazonApiException e&&(e.status()==401||e.status()==403)){stop(r,"FEE_AUTHORIZATION_REQUIRED");return true;}
        if(error instanceof AmazonSpApiClient.AmazonApiException e&&e.status()>=400&&e.status()<500&&e.status()!=429){stop(r,"FEE_UNAVAILABLE");return true;}
        if(!r.fee().path("pendingKind").asText().isEmpty()){stop(r,"FEE_REQUEST_OUTCOME_UNKNOWN");return true;}
        if(r.fee().path("skuAttempts").asInt()>=SKU_ATTEMPTS||r.fee().path("http").asInt()>=cap(r,"maxHttp",HTTP_LIMIT)){stop(r,"FEE_RETRY_EXHAUSTED");return true;}
        Duration delay=error instanceof AmazonSpApiClient.AmazonApiException e&&e.retryAfter()!=null?e.retryAfter():Duration.ofSeconds(30);
        if(delay.isNegative()||delay.isZero())delay=Duration.ofSeconds(1);
        Instant retry;try{retry=r.now().plus(delay);}catch(RuntimeException overflow){retry=r.deadline();}
        Instant paced=nextAt(r);if(paced.isAfter(retry))retry=paced;
        r.fee().put("reason","RETRY");defer(r,retry);return true;
    },false);}
    static final String BATCH_CACHE_SQL="""
        SELECT target.sku FROM jsonb_to_recordset(CAST(? AS jsonb)) AS target(sku text,currency text,basis jsonb)
        WHERE EXISTS(SELECT 1 FROM amazon_fee_estimates f JOIN amazon_source_documents d
          ON d.tenant_id=f.tenant_id AND d.marketplace_connection_id=f.marketplace_connection_id AND d.id=f.source_document_id
          WHERE f.tenant_id=? AND f.marketplace_connection_id=? AND f.marketplace_id=? AND f.seller_sku=target.sku AND f.currency=target.currency
           AND f.effective_at>=? AND f.effective_at<=? AND f.fulfillment_fee>=0 AND f.referral_fee>=0 AND f.estimated_fee_total>=0
           AND d.source_type='FEES_SNAPSHOT' AND f.raw_payload->>'_platformFeeContract'=?
           AND f.raw_payload->>'Status'='Success' AND f.raw_payload->'_platformQuoteBasis'=target.basis
           AND f.raw_payload->'FeesEstimateIdentifier'->>'IdType'='SellerSKU'
           AND f.raw_payload->'FeesEstimateIdentifier'->>'IdValue'=target.sku
           AND f.raw_payload->'FeesEstimateIdentifier'->>'MarketplaceId'=target.basis->>'MarketplaceId'
           AND f.raw_payload->'FeesEstimateIdentifier'->>'SellerId'=target.basis->>'_platformSellerId')
        """;
    /** Freeze exact inputs once. Live shipping-zero authority must come from a reviewed operator/run. */
    List<Target> batchNext(AmazonSyncStore.Job j){return owned(j,r->{
        r.fee().put("batchMode",true);
        if(!r.fee().path("pendingKind").asText().isEmpty()){stop(r,"FEE_REQUEST_OUTCOME_UNKNOWN");return List.of();}
        if(!r.cursor().path("shippingBasisConfirmed").asBoolean(false)){stop(r,"FEE_SHIPPING_BASIS_REQUIRES_APPROVAL");return List.of();}
        if(!r.fee().has("batchManifest")){
            var manifest=r.fee().putArray("batchManifest");
            var rows=jdbc.queryForList("""
                SELECT l.seller_sku,l.price,CASE WHEN ? THEN coalesce(nullif(trim(l.currency),''),d.currency_code) ELSE l.currency END currency FROM amazon_listings l JOIN marketplace_connections c
                 ON c.tenant_id=l.tenant_id AND c.id=l.marketplace_connection_id AND c.marketplace_identifier=l.marketplace_id
                JOIN marketplace_definitions d ON d.channel='AMAZON' AND d.marketplace_identifier=l.marketplace_id AND (d.currency_code=l.currency OR (? AND nullif(trim(l.currency),'') IS NULL))
                WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.marketplace_id=? AND upper(l.listing_status)='ACTIVE'
                 AND upper(l.fulfillment_channel) IN ('AMAZON','AMAZON_NA','AMAZON_EU','AMAZON_JP','FBA','AFN') AND l.price>0
                 AND (CAST(? AS jsonb) IS NULL OR l.seller_sku IN (SELECT jsonb_array_elements_text(CAST(? AS jsonb))))
                 AND NOT EXISTS(SELECT 1 FROM amazon_variation_classifications v WHERE v.tenant_id=l.tenant_id
                   AND v.marketplace_connection_id=l.marketplace_connection_id AND v.marketplace_id=l.marketplace_id
                   AND v.seller_sku=l.seller_sku AND (v.classification='CONFLICT' OR (v.classification='PARENT'
                     AND v.valid_until>clock_timestamp() AND v.verified_revision=v.evidence_revision
                     AND v.own_complete AND v.reciprocal_complete AND v.members_complete)))
                ORDER BY l.seller_sku LIMIT 1001
                """,currencyFallback,currencyFallback,j.tenantId(),j.connectionId(),j.marketplaceId(),
                    r.fee().has("runContract")?r.fee().path("runContract").path("skus").toString():null,
                    r.fee().has("runContract")?r.fee().path("runContract").path("skus").toString():null);
            if(rows.size()>cap(r,"maxTargets",1000)){stop(r,"FEE_MANIFEST_LIMIT");return List.of();}
            var candidates=json.createArrayNode();
            for(var row:rows){
                String sku=row.get("seller_sku").toString(),currency=row.get("currency").toString();BigDecimal price=(BigDecimal)row.get("price");
                var basis=json.createObjectNode().put("MarketplaceId",j.marketplaceId()).put("IsAmazonFulfilled",true)
                    .put("_platformSellerId",r.fee().path("seller").asText()).put("_platformShippingApproved",true);
                var money=basis.putObject("PriceToEstimateFees");money.putObject("ListingPrice").put("CurrencyCode",currency).put("Amount",price);
                money.putObject("Shipping").put("CurrencyCode",currency).put("Amount",BigDecimal.ZERO);
                var candidate=candidates.addObject().put("sku",sku).put("price",price).put("currency",currency);candidate.set("basis",basis);
            }
            if(r.fee().has("runContract")){
                var contract=r.fee().path("runContract");
                if(candidates.size()!=contract.path("skus").size()||!basisDigest(candidates).equals(contract.path("basisSha256").asText())){stop(r,"FEE_RUN_BASIS_CHANGED");return List.of();}
                var frozen=r.fee().putArray("runManifest");for(var c:candidates)frozen.addObject().put("sku",c.path("sku").asText()).put("price",c.path("price").decimalValue()).put("currency",c.path("currency").asText());
            }
            Set<String> cached=new HashSet<>(jdbc.queryForList(BATCH_CACHE_SQL,String.class,candidates.toString(),
                j.tenantId(),j.connectionId(),j.marketplaceId(),Timestamp.from(r.now().minus(CACHE_AGE)),Timestamp.from(r.now()),AmazonProductFeeBatch.CONTRACT));
            for(var candidate:candidates){
                if(cached.contains(candidate.path("sku").asText()))r.fee().put("cacheHits",r.fee().path("cacheHits").asInt()+1);
                else manifest.addObject().put("sku",candidate.path("sku").asText()).put("price",candidate.path("price").decimalValue()).put("currency",candidate.path("currency").asText());
            }
            r.fee().put("batchIndex",0);save(r);
        }
        var manifest=r.fee().path("batchManifest");int index=r.fee().path("batchIndex").asInt();
        if(index>=manifest.size()){
            update(r,true,"status='COMPLETED',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL");
            jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED',progress_percent=100,user_message=?,completed_at=clock_timestamp() WHERE tenant_id=? AND id=? AND NOT EXISTS(SELECT 1 FROM marketplace_sync_jobs WHERE tenant_id=? AND sync_run_id=? AND status NOT IN ('COMPLETED','SKIPPED'))",r.fee().has("runContract")?"Fee responses archived; estimates unchanged":"Fee estimates refreshed",j.tenantId(),j.runId(),j.tenantId(),j.runId());
            return List.of();
        }
        List<Target> targets=new ArrayList<>();
        for(int n=index;n<Math.min(index+20,manifest.size());n++){
            var m=manifest.get(n);targets.add(new Target(m.path("sku").asText(),m.path("price").decimalValue(),m.path("currency").asText()));
        }
        var pending=r.fee().putArray("batchPending");for(var t:targets)pending.add(digest(t.sku()+":"+t.price().stripTrailingZeros().toPlainString()+":"+t.currency()));save(r);
        return List.copyOf(targets);
    },List.of());}
    void publishBatch(AmazonSyncStore.Job j,List<Target> targets,AmazonProductFeeBatch.Result result,AmazonSpApiClient.ApiResponse response){owned(j,r->{
        observe(r,response.rateLimit());
        for(var accepted:result.accepted()){
            var t=accepted.request().target();
            var current=jdbc.queryForList("SELECT id FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? AND price=? AND (currency=? OR (? AND nullif(trim(currency),'') IS NULL AND ?=(SELECT currency_code FROM marketplace_definitions WHERE channel='AMAZON' AND marketplace_identifier=?))) AND upper(listing_status)='ACTIVE' AND upper(fulfillment_channel) IN ('AMAZON','AMAZON_NA','AMAZON_EU','AMAZON_JP','FBA','AFN') FOR SHARE",UUID.class,j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),t.price(),t.currency(),currencyFallback,t.currency(),j.marketplaceId());
            if(current.size()!=1){archive(r,"batch:"+r.fee().path("batchIndex").asInt(),response.body(),response.requestId(),false,0);stop(r,"FEE_BASIS_CHANGED");return false;}
        }
        UUID source=archive(r,"batch:"+r.fee().path("batchIndex").asInt(),response.body(),response.requestId(),!result.accepted().isEmpty(),result.accepted().size());
        // The initial controlled pilot is archive-only. Legacy publication remains unchanged.
        if(!r.fee().has("runContract"))for(var accepted:result.accepted()){
            var t=accepted.request().target();
            ObjectNode raw=(ObjectNode)accepted.result().deepCopy();raw.set("_platformQuoteBasis",accepted.request().basis());raw.put("_platformFeeContract",AmazonProductFeeBatch.CONTRACT);
            String key=digest(raw.toString());
            boolean exists=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_fee_estimates WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? AND raw_payload=CAST(? AS jsonb))",Boolean.class,j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),raw.toString());
            if(!exists){
                var a=accepted.amounts();jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,estimated_fee_total,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)",j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),t.currency(),a.total(),a.referral(),a.fulfillment(),Timestamp.from(r.now()),raw.toString(),source);
                if(feeEvidence!=null)feeEvidence.quote(j.tenantId(),j.connectionId(),j.marketplaceId(),t.sku(),source,key,accepted.request().basis(),a.referral(),AmazonProductFeeBatch.comparableFba(accepted.result().path("FeesEstimate"),t.currency()),r.now());
            }
        }
        r.fee().remove("batchPending");
        if(!result.unresolved().isEmpty()){
            // Retry only definitively unresolved siblings; keep valid observations and durable progress.
            var manifest=(tools.jackson.databind.node.ArrayNode)r.fee().path("batchManifest");int index=r.fee().path("batchIndex").asInt();
            for(int n=0;n<targets.size();n++)manifest.remove(index);
            for(int n=result.unresolved().size()-1;n>=0;n--){var t=result.unresolved().get(n).target();manifest.insert(index,json.createObjectNode().put("sku",t.sku()).put("price",t.price()).put("currency",t.currency()));}
            save(r);if(r.fee().path("skuAttempts").asInt()>=SKU_ATTEMPTS){stop(r,"FEE_RETRY_EXHAUSTED");return false;}
            defer(r,r.now().plusSeconds(30));return true;
        }
        r.fee().put("batchIndex",r.fee().path("batchIndex").asInt()+targets.size()).put("skuAttempts",0).put("attemptSku","");save(r);
        update(r,true,"records_processed=records_processed+?,status='WAITING',available_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL",result.accepted().size());return true;
    },false);}

}
