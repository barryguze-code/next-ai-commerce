package com.nextaicommerce.platform.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope;

/** One explicit approval over a frozen local catalogue. The consumed legacy trial is never reused. */
@Repository
public class AmazonVariationCoverageGrant {
    static final String FORMAT="ACCOUNT_RELATIONSHIPS_V1";
    static final int IDENTITIES=700, GETS=1450, OAUTH=2;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final boolean enabled;private final String approval,tenant,connection,market,seller,seedsHash;
    public AmazonVariationCoverageGrant(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,
            @Value("${app.amazon.variation.coverage-enabled:false}") boolean enabled,
            @Value("${app.amazon.variation.coverage-approval:}") String approval,
            @Value("${app.amazon.variation.coverage-tenant:}") String tenant,
            @Value("${app.amazon.variation.coverage-connection:}") String connection,
            @Value("${app.amazon.variation.coverage-marketplace:}") String market,
            @Value("${app.amazon.variation.coverage-seller:}") String seller,
            @Value("${app.amazon.variation.coverage-seeds-sha256:}") String seedsHash){
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.enabled=enabled;this.approval=approval;
        this.tenant=tenant;this.connection=connection;this.market=market;this.seller=seller;this.seedsHash=seedsHash;
        if(enabled){UUID.fromString(approval);UUID.fromString(tenant);UUID.fromString(connection);
            if(market.isBlank()||seller.isBlank()||!seedsHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Incomplete relationship approval");}
    }
    boolean enabled(){return enabled;}
    public Scope scope(){if(!enabled)throw new IllegalStateException("Relationship coverage is disabled");return new Scope(UUID.fromString(tenant),UUID.fromString(connection),market);}
    public UUID approval(){scope();return UUID.fromString(approval);}
    public Map<String,Object> displayScope(){return tx.execute(status->{tenant();Scope s=scope();return jdbc.queryForMap("SELECT t.display_name AS account,c.display_name AS store FROM tenants t JOIN marketplace_connections c ON c.tenant_id=t.id WHERE t.id=? AND c.id=? AND c.marketplace_identifier=? AND c.seller_identifier=?",s.tenant(),s.connection(),s.marketplace(),seller);});}
    private void tenant(){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant);}
    static String digest(ObjectMapper json,Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String seedsDigest(ObjectMapper json,Collection<String> seeds){return digest(json,new TreeSet<>(seeds));}
    private List<String> seeds(){Scope s=scope();return jdbc.queryForList("SELECT seller_sku FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? ORDER BY seller_sku COLLATE \"C\"",String.class,s.tenant(),s.connection(),s.marketplace());}
    private ObjectNode immutable(Collection<String> seeds,Instant started){
        return (ObjectNode)json.valueToTree(new TreeMap<>(Map.ofEntries(Map.entry("format",FORMAT),Map.entry("approval",approval),
            Map.entry("tenant",tenant),Map.entry("connection",connection),Map.entry("marketplace",market),Map.entry("seller",seller),
            Map.entry("seeds",new TreeSet<>(seeds)),Map.entry("seedsHash",seedsHash),Map.entry("maxIdentities",IDENTITIES),
            Map.entry("maxGets",GETS),Map.entry("maxOauth",OAUTH),Map.entry("started",started.toString()),Map.entry("deadline",started.plusSeconds(3600).toString()),
            Map.entry("expansion","EXPLICIT_SAME_SELLER_RELATIONSHIPS"))));
    }
    /** No enqueue on construction/startup. Connection lock serializes grants and the existing queue. */
    public UUID start(UUID key){
        if(!approval().equals(key))throw new IllegalArgumentException("Unapproved relationship grant");
        return tx.execute(status->{tenant();Scope s=scope();
            var c=jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND id=? AND marketplace_identifier=? AND seller_identifier=? AND channel='AMAZON' AND status='ACTIVE' FOR UPDATE",s.tenant(),s.connection(),market,seller);
            if(c.size()!=1)throw new IllegalStateException("Approved connection identity changed");
            var previous=jdbc.queryForList("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND cursor->'approval'->'contract'->>'approval'=?",UUID.class,s.tenant(),s.connection(),AmazonVariationRelationshipJobs.TYPE,approval);
            if(!previous.isEmpty())return previous.getFirst(); // Terminal grants cannot renew or reset.
            if(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND status IN ('QUEUED','WAITING','RUNNING')",Integer.class,s.tenant(),s.connection(),AmazonVariationRelationshipJobs.TYPE)!=0)throw new IllegalStateException("Relationship job already active");
            var seeds=seeds();if(seeds.isEmpty()||seeds.size()>IDENTITIES||!seedsHash.equals(seedsDigest(json,seeds)))throw new IllegalStateException("Approved catalogue manifest changed");
            for(String sku:seeds)if(sku.isEmpty()||sku.codePointCount(0,sku.length())>240)throw new IllegalArgumentException("Invalid exact SKU");
            Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();ObjectNode contract=immutable(seeds,now);
            ObjectNode grant=json.createObjectNode();grant.set("contract",contract);grant.put("sha256",digest(json,contract));grant.put("getReserved",0);grant.put("oauthReserved",0);grant.put("state","RUNNING");
            ObjectNode cursor=json.createObjectNode();cursor.put("marketplace",market);cursor.set("pending",json.valueToTree(seeds));cursor.set("done",json.createArrayNode());cursor.set("approval",grant);
            UUID run=UUID.randomUUID(),job=UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end) VALUES(?,?,?,'RECONCILIATION',?,?,?)",run,s.tenant(),s.connection(),AmazonVariationRelationshipJobs.TYPE,Timestamp.from(now),Timestamp.from(now));
            jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,required_for_ready,cursor,available_at) VALUES(?,?,?,?,?,1,false,CAST(? AS jsonb),?)",job,s.tenant(),run,s.connection(),AmazonVariationRelationshipJobs.TYPE,cursor.toString(),Timestamp.from(now));return job;
        });
    }
    boolean covers(AmazonVariationRelationshipJobs.Lease lease){return lease.cursor().has("approval");}
    private ObjectNode current(AmazonVariationRelationshipJobs.Lease lease){
        return (ObjectNode)json.readTree(jdbc.queryForObject("SELECT cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",String.class,lease.job().tenantId(),lease.job().connectionId(),lease.job().id()));
    }
    private boolean exactScope(AmazonVariationRelationshipJobs.Lease lease){return enabled&&tenant.equals(lease.job().tenantId().toString())&&connection.equals(lease.job().connectionId().toString())&&market.equals(lease.job().marketplaceId())&&seller.equals(lease.seller());}
    private boolean valid(AmazonVariationRelationshipJobs.Lease lease,ObjectNode cursor){
        if(!exactScope(lease))return false;
        JsonNode a=cursor.path("approval"),c=a.path("contract");
        try{
            if(!c.path("seeds").isArray())return false;List<String> seeds=new ArrayList<>();for(JsonNode v:c.path("seeds")){if(!v.isString())return false;seeds.add(v.asText());}
            if(seeds.isEmpty()||seeds.size()>IDENTITIES||new HashSet<>(seeds).size()!=seeds.size()||!seedsHash.equals(seedsDigest(json,seeds)))return false;
            ObjectNode expected=immutable(seeds,Instant.parse(c.path("started").asText()));
            if(!expected.equals(c)||!digest(json,expected).equals(a.path("sha256").asText())||!market.equals(cursor.path("marketplace").asText()))return false;
            Set<String> all=new HashSet<>();for(String field:List.of("pending","done","rechecked")){
                if(!cursor.path(field).isMissingNode()&&!cursor.path(field).isArray())return false;
                for(JsonNode v:cursor.path(field)){if(!v.isString()||v.asText().isEmpty()||v.asText().codePointCount(0,v.asText().length())>240)return false;all.add(v.asText());}}
            if(all.size()>IDENTITIES||!all.containsAll(seeds))return false;
            for(String field:List.of("getReserved","oauthReserved"))if(!a.path(field).isIntegralNumber()||!a.path(field).canConvertToInt()||a.path(field).asInt()<0)return false;
            return a.path("getReserved").asInt()<=GETS&&a.path("oauthReserved").asInt()<=OAUTH;
        }catch(RuntimeException ex){return false;}
    }
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    boolean withinDeadline(AmazonVariationRelationshipJobs.Lease lease){ObjectNode c=current(lease);return valid(lease,c)&&Instant.parse(c.path("approval").path("contract").path("deadline").asText()).isAfter(now());}
    boolean allowed(AmazonVariationRelationshipJobs.Lease lease){
        if(!exactScope(lease))return false;
        ObjectNode c=current(lease);if(!valid(lease,c)){stop(lease,"INVALID_APPROVAL");return false;}
        if(!"RUNNING".equals(c.path("approval").path("state").asText()))return false;
        if(!withinDeadline(lease)){stop(lease,"DEADLINE");return false;}
        var request=c.path("approval").path("request");
        if(!request.isMissingNode()&&(!lease.owner().equals(request.path("owner").asText())||lease.fence()!=request.path("fence").asInt())){stop(lease,"REQUEST_OUTCOME_UNKNOWN");return false;}
        return true;
    }
    Duration remaining(AmazonVariationRelationshipJobs.Lease lease){if(!exactScope(lease))throw new AmazonSpApiClient.VariationDeferredException();return tx.execute(s->{tenant();
        var rows=jdbc.queryForList("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND status='RUNNING' AND lease_owner=? AND attempt_count=? AND lease_expires_at>clock_timestamp() FOR UPDATE",UUID.class,lease.job().tenantId(),lease.job().connectionId(),lease.job().id(),lease.owner(),lease.fence());
        if(rows.isEmpty()||!allowed(lease))throw new AmazonSpApiClient.VariationDeferredException();
        return Duration.between(now(),Instant.parse(current(lease).path("approval").path("contract").path("deadline").asText()));});}
    private void save(AmazonVariationRelationshipJobs.Lease lease,ObjectNode c){jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb) WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",c.toString(),lease.job().tenantId(),lease.job().connectionId(),lease.job().id());}
    boolean reserve(AmazonVariationRelationshipJobs.Lease lease,boolean oauth,UUID request){
        if(!allowed(lease))return false;ObjectNode c=current(lease),a=(ObjectNode)c.path("approval");if(a.has("request"))return false;
        String field=oauth?"oauthReserved":"getReserved";int cap=oauth?OAUTH:GETS;
        if(a.path(field).asInt()>=cap){stop(lease,oauth?"OAUTH_LIMIT":"GET_LIMIT");return false;}
        a.put(field,a.path(field).asInt()+1);a.set("request",json.valueToTree(Map.of("id",request.toString(),"owner",lease.owner(),"fence",lease.fence(),"kind",oauth?"OAUTH":"GET")));save(lease,c);return true;
    }
    void release(AmazonVariationRelationshipJobs.Lease lease,UUID request){if(!exactScope(lease))return;tx.executeWithoutResult(s->{tenant();
        ObjectNode c=current(lease);JsonNode r=c.path("approval").path("request");
        if(valid(lease,c)&&request.toString().equals(r.path("id").asText())&&lease.owner().equals(r.path("owner").asText())&&lease.fence()==r.path("fence").asInt()){
            ((ObjectNode)c.path("approval")).remove("request");save(lease,c);}
    });}
    void uncertain(AmazonVariationRelationshipJobs.Lease lease){if(!exactScope(lease))return;tx.executeWithoutResult(s->{tenant();if(valid(lease,current(lease)))stop(lease,"REQUEST_OUTCOME_UNKNOWN");});}
    void finish(AmazonVariationRelationshipJobs.Lease lease,String reason){ObjectNode c=current(lease);if(c.path("approval").isObject()){((ObjectNode)c.path("approval")).put("state",reason);save(lease,c);}}
    void stop(AmazonVariationRelationshipJobs.Lease lease,String reason){
        finish(lease,reason);jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',error_code=?,completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=? AND id=? AND status IN ('QUEUED','WAITING','RUNNING')",reason,lease.job().tenantId(),lease.job().id());
        jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=clock_timestamp(),user_message=? WHERE tenant_id=? AND id=? AND status IN ('QUEUED','RUNNING')",reason,lease.job().tenantId(),lease.job().runId());
    }
    public Map<String,Object> inspect(boolean cancel){return tx.execute(s->{tenant();Scope scope=scope();
        var rows=jdbc.queryForList("SELECT id FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=? AND job_type=? AND cursor->'approval'->'contract'->>'approval'=? FOR UPDATE",UUID.class,scope.tenant(),scope.connection(),AmazonVariationRelationshipJobs.TYPE,approval);
        if(rows.isEmpty())return Map.of("state","NOT_STARTED","approval",approval);
        UUID id=rows.getFirst();if(cancel){
            jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED',error_code='CANCELLED',completed_at=clock_timestamp(),lease_owner=NULL,lease_expires_at=NULL,cursor=jsonb_set(cursor,'{approval,state}','\"CANCELLED\"') WHERE tenant_id=? AND id=? AND status IN ('QUEUED','WAITING','RUNNING')",scope.tenant(),id);
            jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED',completed_at=clock_timestamp() WHERE tenant_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?) AND status IN ('QUEUED','RUNNING')",scope.tenant(),scope.tenant(),id);
        }
        return jdbc.queryForMap("SELECT id AS job_id,status,error_code,cursor->'approval'->>'state' AS state,cursor->'approval'->>'getReserved' AS get_reserved,cursor->'approval'->>'oauthReserved' AS oauth_reserved,cursor->'approval'->'contract'->>'deadline' AS deadline,jsonb_array_length(cursor->'approval'->'contract'->'seeds') AS seed_count,jsonb_array_length(cursor->'done') AS observed_count,cursor->'approval'->'request' IS NOT NULL AS request_in_flight FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",scope.tenant(),id);
    });}
}
