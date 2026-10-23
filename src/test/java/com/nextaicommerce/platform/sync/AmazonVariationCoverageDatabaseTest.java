package com.nextaicommerce.platform.sync;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import com.nextaicommerce.platform.marketplace.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Disposable test schema, synthetic identities and mocked Amazon only. */
@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class AmazonVariationCoverageDatabaseTest {
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    final ObjectMapper json=new ObjectMapper();UUID tenant,connection,key;Scope scope;
    AmazonVariationCoverageGrant grant;AmazonVariationRelationshipJobs jobs;AmazonVariationRelationshipSync worker;
    AmazonVariationRelationshipProvider provider;AmazonSpApiClient api;
    @BeforeAll static void database()throws Exception{
        schema="variation_coverage_"+UUID.randomUUID().toString().replace("-","");
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",Files.readString(Path.of(".local/database-password")).trim());
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("112").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterAll static void close(){if(jdbc!=null&&schema.matches("variation_coverage_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @BeforeEach void fixture(){
        jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED' WHERE job_type='VARIATION_RELATIONSHIPS'");
        tenant=UUID.randomUUID();connection=UUID.randomUUID();key=UUID.randomUUID();scope=new Scope(tenant,connection,AmazonVariationTrial.MARKET);
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Synthetic coverage')",tenant,tenant.toString());
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','synthetic-seller',?,'synthetic','ACTIVE','Synthetic coverage','UTC',now())",connection,tenant,scope.marketplace());
        api=mock(AmazonSpApiClient.class);var repo=new AmazonVariationRelationshipRepository(jdbc,json);provider=new AmazonVariationRelationshipProvider(repo);
        var publisher=new AmazonVariationRelationshipPublisher(jdbc,new AmazonSyncStore(jdbc,tx),repo,provider,json);
        worker=new AmazonVariationRelationshipSync(mock(AmazonVariationRelationshipJobs.class),publisher,api,true);
    }
    void setup(List<String> seeds){
        for(String sku:seeds)jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,?,?)",tenant,connection,scope.marketplace(),sku);
        grant=new AmazonVariationCoverageGrant(jdbc,tx,json,true,key.toString(),tenant.toString(),connection.toString(),scope.marketplace(),"synthetic-seller",AmazonVariationCoverageGrant.seedsDigest(json,seeds));
        jobs=new AmazonVariationRelationshipJobs(jdbc,tx,json,new AmazonVariationTrial(jdbc,tx,true),grant);
        var repo=new AmazonVariationRelationshipRepository(jdbc,json);
        worker=new AmazonVariationRelationshipSync(jobs,new AmazonVariationRelationshipPublisher(jdbc,new AmazonSyncStore(jdbc,tx),repo,provider,json),api,true);
    }
    void due(UUID id){jdbc.update("UPDATE marketplace_sync_jobs SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",id);jdbc.queryForObject("SELECT setval('amazon_variation_next_request_ms',1)",Long.class);}
    AmazonVariationRelationshipJobs.Lease lease(UUID id){due(id);return jobs.claim("synthetic");}
    String state(){return (String)grant.inspect(false).get("state");}
    String cursor(UUID id){return jdbc.queryForObject("SELECT cursor::text FROM marketplace_sync_jobs WHERE id=?",String.class,id);}
    void alter(UUID id,String expression){jdbc.update("UPDATE marketplace_sync_jobs SET cursor="+expression+" WHERE id=?",id);}
    Classification read(String sku){return tx.execute(t->provider.classify(scope,List.of(sku)).get(sku));}
    String payload(String sku,String role,List<String> children){
        Map<String,Object> attrs=new HashMap<>();List<Map<String,Object>> relations=new ArrayList<>();
        if(!role.equals("STANDALONE"))attrs.put("parentage_level",List.of(Map.of("marketplace_id",scope.marketplace(),"value",role.toLowerCase(Locale.ROOT))));
        if(role.equals("PARENT"))relations.add(Map.of("type","VARIATION","childSkus",children));
        if(role.equals("CHILD")){attrs.put("child_parent_sku_relationship",List.of(Map.of("marketplace_id",scope.marketplace(),"child_relationship_type","variation","parent_sku","0000")));relations.add(Map.of("type","VARIATION","parentSkus",List.of("0000")));}
        return json.writeValueAsString(Map.of("sku",sku,"summaries",List.of(Map.of("marketplaceId",scope.marketplace(),"asin","ASIN1","status",List.of(role.equals("PARENT")?"DISCOVERABLE":"BUYABLE"))),"attributes",attrs,"relationships",List.of(Map.of("marketplaceId",scope.marketplace(),"relationships",relations))));
    }
    void responses(List<String> children){when(api.getVariationAt(eq(tenant),eq(connection),anyString(),anyString(),any())).thenAnswer(i->{
        AmazonSpApiClient.VariationPermit permit=i.getArgument(4);if(!permit.getAsBoolean())throw new AmazonSpApiClient.VariationDeferredException();
        String path=i.getArgument(3);String sku=java.net.URLDecoder.decode(path.substring(path.lastIndexOf('/')+1,path.indexOf('?')),java.nio.charset.StandardCharsets.UTF_8);
        String body=payload(sku,sku.equals("0000")?"PARENT":children.contains(sku)?"CHILD":"STANDALONE",children);permit.completed();
        return new AmazonSpApiClient.ApiResponse(200,"synthetic-request",json.readTree(body),body);
    });}
    @Test void frozen549SeedsAndLargerFamilyFinishWithoutTouchingInventory(){
        List<String> seeds=new ArrayList<>();for(int i=0;i<549;i++)seeds.add(String.format(Locale.ROOT,"%04d",i));setup(seeds);
        List<String> children=seeds.subList(1,6);responses(children);
        UUID source=UUID.randomUUID();jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,sha256,text_payload) VALUES (?,?,?,'INVENTORY_SNAPSHOT',?,'unchanged source')",source,tenant,connection,"a".repeat(64));
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,inbound_shipped_quantity,snapshot_at,source_document_id) VALUES (?,?,?,'0001','ASIN1','FNSKU1',27,160,now(),?)",tenant,connection,scope.marketplace(),source);
        String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s))::text FROM amazon_inventory_snapshots s",String.class);
        UUID id=jobs.startCoverage(key);assertThat(grant.inspect(false)).containsEntry("seed_count",549);
        for(int n=0;n<555&&state().equals("RUNNING");n++){due(id);worker.tick();}
        assertThat(state()).isEqualTo("COMPLETED");assertThat(grant.inspect(false)).containsEntry("get_reserved","550");
        assertThat(read("0000").state()).isEqualTo(State.PARENT);assertThat(read("0001").state()).isEqualTo(State.CHILD);assertThat(read("0548").state()).isEqualTo(State.STANDALONE);
        assertThat(read("1").state()).isEqualTo(State.UNKNOWN);assertThat(jobs.startCoverage(key)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s))::text FROM amazon_inventory_snapshots s",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents WHERE id=?",String.class,source)).isEqualTo("unchanged source");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_trial WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }
    @Test void approvalCannotBeUsedForChangedManifestOrKey(){setup(List.of("0001"));
        assertThatThrownBy(()->jobs.startCoverage(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,?,'1')",tenant,connection,scope.marketplace());
        assertThatThrownBy(()->jobs.startCoverage(key)).isInstanceOf(IllegalStateException.class);assertThat(state()).isEqualTo("NOT_STARTED");
    }
    @Test void duplicateStartsSerializeAndCancelNeverRenews()throws Exception{setup(List.of("A"));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var a=pool.submit(()->jobs.startCoverage(key));var b=pool.submit(()->jobs.startCoverage(key));assertThat(a.get()).isEqualTo(b.get());}
        UUID id=jobs.startCoverage(key);var l=lease(id);grant.inspect(true);assertThat(jobs.<Boolean>owned(l,j->true)).isNull();assertThat(jobs.startCoverage(key)).isEqualTo(id);assertThat(state()).isEqualTo("CANCELLED");
    }
    @Test void reservationsAreDurablePreservedByRetryAndBounded(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);
        UUID request=UUID.randomUUID();assertThat(jobs.reserveOAuth(l,request)).isTrue();jobs.requestCompleted(l,request);
        jobs.owned(l,j->{jobs.retryInside(l,429,Duration.ofSeconds(130));return true;});assertThat(grant.inspect(false)).containsEntry("oauth_reserved","1");
        var next=lease(id);request=UUID.randomUUID();assertThat(jobs.reserveOAuth(next,request)).isTrue();jobs.requestCompleted(next,request);
        assertThat(jobs.reserveOAuth(next,UUID.randomUUID())).isFalse();assertThat(state()).isEqualTo("OAUTH_LIMIT");
    }
    @Test void read1450BoundaryAndIdentity700BoundaryFailClosed(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);
        alter(id,"jsonb_set(cursor,'{approval,getReserved}','1449')");UUID req=UUID.randomUUID();assertThat(jobs.reserve(l,req)).isTrue();jobs.requestCompleted(l,req);due(id);
        assertThat(jobs.reserve(l,UUID.randomUUID())).isFalse();assertThat(state()).isEqualTo("GET_LIMIT");
        fixture();setup(List.of("A"));id=jobs.startCoverage(key);l=lease(id);var members=new ArrayList<String>();for(int i=0;i<700;i++)members.add("C"+i);
        var raw=json.valueToTree(Map.of("sku","A","relationships",List.of(Map.of("marketplaceId",scope.marketplace(),"relationships",List.of(Map.of("type","VARIATION","childSkus",members))))));
        assertThat(jobs.familyAllowed(l,"A",raw)).isFalse();members.removeLast();raw=json.valueToTree(Map.of("sku","A","relationships",List.of(Map.of("marketplaceId",scope.marketplace(),"relationships",List.of(Map.of("type","VARIATION","childSkus",members))))));assertThat(jobs.familyAllowed(l,"A",raw)).isTrue();
    }
    @Test void crashedInFlightRequestCannotReplayAfterLeaseLoss(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);assertThat(jobs.reserveOAuth(l,UUID.randomUUID())).isTrue();
        jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThat(lease(id)).isNull();assertThat(state()).isEqualTo("REQUEST_OUTCOME_UNKNOWN");assertThat(jobs.<Boolean>owned(l,j->true)).isNull();
        assertThat(grant.inspect(false)).containsEntry("oauth_reserved","1");
    }
    @Test void uncertainTransportStopsRatherThanRetrying(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);UUID req=UUID.randomUUID();assertThat(jobs.reserveOAuth(l,req)).isTrue();jobs.requestUncertain(l,req);
        assertThat(state()).isEqualTo("REQUEST_OUTCOME_UNKNOWN");assertThat(grant.inspect(false)).containsEntry("request_in_flight",true);assertThat(jobs.claim("replacement")).isNull();
    }
    @Test void tamperedApprovalAndExpiredEvidenceCannotAuthorizeRequests(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);
        alter(id,"jsonb_set(cursor,'{approval,contract,maxGets}','1451')");assertThat(jobs.reserveOAuth(l,UUID.randomUUID())).isFalse();assertThat(state()).isEqualTo("INVALID_APPROVAL");
    }
    void expire(UUID id){
        var cursor=(tools.jackson.databind.node.ObjectNode)json.readTree(cursor(id));var a=(tools.jackson.databind.node.ObjectNode)cursor.path("approval");
        var values=new TreeMap<String,JsonNode>();a.path("contract").properties().forEach(e->values.put(e.getKey(),e.getValue()));
        Instant started=Instant.now().minusSeconds(3605);values.put("started",json.valueToTree(started.toString()));values.put("deadline",json.valueToTree(started.plusSeconds(3600).toString()));
        a.set("contract",json.valueToTree(values));a.put("sha256",AmazonVariationCoverageGrant.digest(json,a.path("contract")));
        jdbc.update("UPDATE marketplace_sync_jobs SET cursor=CAST(? AS jsonb) WHERE id=?",cursor.toString(),id);
    }
    @Test void deadlineAndPostPublicationDeadlineFenceAreEnforced(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);expire(id);
        assertThat(jobs.reserveOAuth(l,UUID.randomUUID())).isFalse();assertThat(state()).isEqualTo("DEADLINE");
        fixture();setup(List.of("B"));UUID other=jobs.startCoverage(key);var active=lease(other);
        assertThatThrownBy(()->jobs.owned(active,j->{j.update("UPDATE marketplace_sync_jobs SET records_processed=99 WHERE id=?",other);expire(other);return true;})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,other)).isZero();assertThat(state()).isEqualTo("RUNNING");
    }
    @Test void authorizationFailureAndRetryOutsideWindowStopTheGrant(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);
        jobs.owned(l,j->{jobs.retryInside(l,403,null);return true;});assertThat(state()).isEqualTo("AUTHORIZATION_REQUIRED");
        fixture();setup(List.of("B"));id=jobs.startCoverage(key);var next=lease(id);
        jobs.owned(next,j->{jobs.retryInside(next,429,Duration.ofHours(2));return true;});assertThat(state()).isEqualTo("RETRY_OUTSIDE_WINDOW");
    }
    @Test void queueRlsHidesTheApprovalFromUnscopedAndForeignTenants(){setup(List.of("A"));UUID id=jobs.startCoverage(key);String role="coverage_reader_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try{jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON marketplace_sync_jobs TO "+role);
            tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id','',true)",String.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE id=?",Integer.class,id)).isZero();
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE id=?",Integer.class,id)).isZero();
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
            });
        }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
    }
    @Test void completedLegacyTrialAndItsCountersRemainUnchanged(){
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,'legacy-fixture','Legacy fixture')",AmazonVariationTrial.TENANT);
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','legacy-seller',?,'synthetic','ACTIVE','Legacy fixture','UTC',now())",AmazonVariationTrial.CONNECTION,AmazonVariationTrial.TENANT,AmazonVariationTrial.MARKET);
        var trial=new AmazonVariationTrial(jdbc,tx,true);var legacy=new AmazonVariationRelationshipJobs(jdbc,tx,json,trial);UUID oldKey=UUID.randomUUID();UUID oldId=trial.start(oldKey,legacy);var old=legacy.claim("legacy");
        UUID req=UUID.randomUUID();assertThat(legacy.reserveOAuth(old,req)).isTrue();legacy.requestCompleted(old,req);
        legacy.owned(old,j->{legacy.advanceInside(old,List.of());return true;});
        String before=jdbc.queryForObject("SELECT to_jsonb(t)::text FROM amazon_variation_trial t",String.class);
        setup(List.of("A"));UUID id=jobs.startCoverage(key);var active=lease(id);req=UUID.randomUUID();assertThat(jobs.reserveOAuth(active,req)).isTrue();jobs.requestCompleted(active,req);grant.inspect(true);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(t)::text FROM amazon_variation_trial t",String.class)).isEqualTo(before);assertThat(trial.start(oldKey,legacy)).isEqualTo(oldId);
        assertThatThrownBy(()->trial.start(UUID.randomUUID(),legacy)).isInstanceOf(IllegalStateException.class);
    }
    @Test void crossTenantConnectionAndMarketplaceNeverPublish(){setup(List.of("A"));UUID id=jobs.startCoverage(key);var l=lease(id);var o=l.job();
        for(int field=0;field<3;field++){
            var foreign=new AmazonSyncStore.Job(o.id(),field==0?UUID.randomUUID():o.tenantId(),o.runId(),field==1?UUID.randomUUID():o.connectionId(),o.type(),o.profile(),field==2?"FOREIGN":o.marketplaceId(),o.windowStart(),o.windowEnd(),null,o.attempts(),0,false,null);
            var wrong=new AmazonVariationRelationshipJobs.Lease(foreign,l.owner(),l.fence(),l.cursor(),l.seller());assertThat(jobs.reserveOAuth(wrong,UUID.randomUUID())).isFalse();
        }
        assertThat(state()).isEqualTo("RUNNING");assertThat(grant.inspect(false)).containsEntry("get_reserved","0").containsEntry("oauth_reserved","0");
        assertThat(new AmazonVariationCoverageGrant(jdbc,tx,json,false,"","","","","","").enabled()).isFalse();
    }
}
