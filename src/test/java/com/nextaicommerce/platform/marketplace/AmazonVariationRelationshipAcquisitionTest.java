package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProviderTest.observation;
import java.util.*;
import java.time.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.sync.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;

/** Existing test DB, disposable schema, mocked HTTP only. No application/server startup. */
@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class AmazonVariationRelationshipAcquisitionTest {
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    final ObjectMapper json=new ObjectMapper();UUID tenant,connection;Scope scope;MutableClock clock;
    AmazonVariationRelationshipJobs jobs;AmazonVariationRelationshipProvider provider;
    AmazonSpApiClient api;AmazonVariationRelationshipSync worker;
    static class MutableClock extends Clock {
        Instant time=Instant.parse("2026-10-18T12:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}public Instant instant(){return time;}
        void advance(long seconds){time=time.plusSeconds(seconds);}
    }
    @BeforeAll static void migrate()throws Exception{
        schema="variation_a2_"+UUID.randomUUID().toString().replace("-","");
        var data=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",Files.readString(Path.of(".local/database-password")).trim());
        Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("111").load().migrate();
        jdbc=new JdbcTemplate(data);tx=new TransactionTemplate(new DataSourceTransactionManager(data));
    }
    @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("variation_a2_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @BeforeEach void setup(){
        // Previous fixtures remain isolated; only this disposable schema is touched.
        jdbc.update("UPDATE marketplace_sync_jobs SET status='SKIPPED' WHERE job_type='VARIATION_RELATIONSHIPS'");
        jdbc.queryForObject("SELECT setval('amazon_variation_next_request_ms',1)",Long.class);
        jdbc.queryForObject("SELECT setval('amazon_variation_request_interval_ms',1000)",Long.class);
        tenant=UUID.randomUUID();connection=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'A2 fixture')",tenant,tenant.toString());
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','seller','ATVPDKIKX0DER','test','ACTIVE','A2 fixture','UTC',now())",connection,tenant);
        scope=new Scope(tenant,connection,"ATVPDKIKX0DER");clock=new MutableClock();
        jobs=new AmazonVariationRelationshipJobs(jdbc,tx,json,clock);
        var repo=new AmazonVariationRelationshipRepository(jdbc,json);provider=new AmazonVariationRelationshipProvider(repo,clock);
        var publisher=new AmazonVariationRelationshipPublisher(jdbc,new AmazonSyncStore(jdbc,tx),repo,provider,json);
        api=mock(AmazonSpApiClient.class);configureMockDispatch();worker=new AmazonVariationRelationshipSync(jobs,publisher,api,true);
    }
    void resetApi(){reset(api);configureMockDispatch();}
    void configureMockDispatch(){when(api.getVariationAt(any(),any(),anyString(),anyString(),any())).thenAnswer(i->{
        java.util.function.BooleanSupplier permit=i.getArgument(4);if(!permit.getAsBoolean())throw new AmazonSpApiClient.VariationDeferredException();
        return api.getVariationAt(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));});}
    String body(String sku,String role,List<String> parents,List<String> children){return observation(sku,role,parents,children).payload().toString().replace("\"US\"","\"ATVPDKIKX0DER\"");}
    void response(String sku,String body){when(api.getVariationAt(eq(tenant),eq(connection),anyString(),contains("/"+AmazonVariationRelationshipAcquisitionTest.encode(sku)+"?")))
        .thenReturn(new AmazonSpApiClient.ApiResponse(200,"mock-request",json.readTree(body),body));}
    static String encode(String s){return java.net.URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}
    void run(String... seeds){worker.enqueue(scope,List.of(seeds));for(int i=0;i<12;i++){worker.tick();clock.advance(2);}}
    Classification read(String sku){return tx.execute(s->provider.classify(scope,List.of(sku)).get(sku));}
    int archived(){return jdbc.queryForObject("SELECT count(*) FROM amazon_source_documents WHERE marketplace_connection_id=?",Integer.class,connection);}
    String jobStatus(UUID id){return jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id);}
    @Test void defaultOffDoesNotReadQueueEnqueueOrCallAmazon(){
        var queue=mock(AmazonVariationRelationshipJobs.class);var publisher=mock(AmazonVariationRelationshipPublisher.class);
        var disabled=new AmazonVariationRelationshipSync(queue,publisher,api,false);disabled.tick();assertThat(disabled.enqueue(scope,List.of("S"))).isNull();verifyNoInteractions(queue,publisher,api);
    }
    @Test void reciprocalFamilyPublishesAtomicallyAndPreservesInventory(){
        UUID source=UUID.randomUUID();jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,sha256,text_payload) VALUES (?,?,?,'INVENTORY_SNAPSHOT',?,'original report')",source,tenant,connection,"a".repeat(64));
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,inbound_shipped_quantity,snapshot_at,source_document_id) VALUES (?,?,'ATVPDKIKX0DER','C','ASIN1','FNSKU1',27,160,?,?)",tenant,connection,java.sql.Timestamp.from(clock.instant()),source);
        String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s))::text FROM amazon_inventory_snapshots s",String.class);
        response("P",body("P","PARENT",List.of(),List.of("C")));response("C",body("C","CHILD",List.of("P"),List.of()));
        UUID id=worker.enqueue(scope,List.of("P"));worker.tick();assertThat(read("P").state()).isEqualTo(State.UNKNOWN);
        clock.advance(2);worker.tick();assertThat(read("P").state()).isEqualTo(State.UNKNOWN);clock.advance(2);worker.tick();assertThat(read("P").state()).isEqualTo(State.PARENT);assertThat(read("C").state()).isEqualTo(State.CHILD);
        assertThat(jobStatus(id)).isEqualTo("COMPLETED");assertThat(archived()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s))::text FROM amazon_inventory_snapshots s",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents WHERE id=?",String.class,source)).isEqualTo("original report");
    }
    @Test void standaloneRequiresExplicitCompleteEvidence(){
        response("S",body("S","STANDALONE",List.of(),List.of()));
        response("U","{\"sku\":\"U\"}");run("S","U");
        assertThat(read("S").state()).isEqualTo(State.STANDALONE);assertThat(read("U").state()).isEqualTo(State.UNKNOWN);assertThat(read("missing").state()).isEqualTo(State.UNKNOWN);
    }
    @Test void incompleteAndMissingReciprocalFamiliesNeverVerify(){
        response("P",body("P","PARENT",List.of(),List.of("C","M")));response("C",body("C","CHILD",List.of("P"),List.of()));
        when(api.getVariationAt(eq(tenant),eq(connection),anyString(),contains("/M?"))).thenThrow(new AmazonSpApiClient.AmazonApiException(404,null,"mock"));run("P");
        assertThat(read("P").state()).isEqualTo(State.UNKNOWN);assertThat(read("C").state()).isEqualTo(State.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT last_http_status FROM amazon_variation_classifications WHERE marketplace_connection_id=? AND seller_sku='M'",Integer.class,connection)).isEqualTo(404);
    }
    @Test void contradictoryReciprocalAndMultipleParentsAreConflicts(){
        response("P",body("P","PARENT",List.of(),List.of("C")));response("C",body("C","STANDALONE",List.of(),List.of()));run("P");
        assertThat(read("P").state()).isEqualTo(State.CONFLICT);
        response("C",body("C","CHILD",List.of("P","Q"),List.of()));response("Q",body("Q","PARENT",List.of(),List.of("C")));run("C");
        assertThat(read("C").state()).isEqualTo(State.CONFLICT);
    }
    @Test void malformedAndWrongIdentityAreArchivedWithoutClassificationGuess(){
        when(api.getVariationAt(eq(tenant),eq(connection),anyString(),contains("/M?"))).thenReturn(new AmazonSpApiClient.ApiResponse(200,"mock",json.createObjectNode(),"not JSON"));
        response("X",body("OTHER","STANDALONE",List.of(),List.of()));run("M","X");
        assertThat(read("M").state()).isEqualTo(State.UNKNOWN);assertThat(read("X").state()).isEqualTo(State.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents WHERE marketplace_connection_id=? AND source_key='M'",String.class,connection)).isEqualTo("not JSON");
    }
    @Test void staleFamilyAndReparentingInvalidateDependentVerification(){
        response("P",body("P","PARENT",List.of(),List.of("C")));response("C",body("C","CHILD",List.of("P"),List.of()));run("P");assertThat(read("P").state()).isEqualTo(State.PARENT);
        clock.advance(25*3600);assertThat(read("P").quality()).isEqualTo(Quality.EXPIRED);
        response("C",body("C","CHILD",List.of("Q"),List.of()));response("Q",body("Q","PARENT",List.of(),List.of("C")));run("C");
        assertThat(read("P").state()).isEqualTo(State.CONFLICT);assertThat(read("C").state()).isNotEqualTo(State.CHILD);
        response("P",body("P","STANDALONE",List.of(),List.of()));run("P");
        assertThat(read("P").state()).isEqualTo(State.STANDALONE);assertThat(read("C").state()).isEqualTo(State.CHILD);assertThat(read("Q").state()).isEqualTo(State.PARENT);
    }
    @Test void duplicateActiveJobsAndExactIdentityIsolation(){
        UUID id=worker.enqueue(scope,List.of("0001"));assertThat(worker.enqueue(scope,List.of("0001"))).isEqualTo(id);assertThatThrownBy(()->worker.enqueue(scope,List.of("OTHER"))).isInstanceOf(IllegalStateException.class);
        response("0001",body("0001","STANDALONE",List.of(),List.of()));run("0001");
        assertThat(read("0001").state()).isEqualTo(State.STANDALONE);
        for(String sku:List.of("1","0001 ","SKU","sku"))assertThat(read(sku).state()).isEqualTo(State.UNKNOWN);
        assertThat(tx.<State>execute(s->provider.classify(new Scope(UUID.randomUUID(),connection,scope.marketplace()),List.of("0001")).get("0001").state())).isEqualTo(State.UNKNOWN);
        assertThat(tx.<State>execute(s->provider.classify(new Scope(tenant,UUID.randomUUID(),scope.marketplace()),List.of("0001")).get("0001").state())).isEqualTo(State.UNKNOWN);
        assertThat(tx.<State>execute(s->provider.classify(new Scope(tenant,connection,"OTHER"),List.of("0001")).get("0001").state())).isEqualTo(State.UNKNOWN);
    }
    @Test void retryAfterAndBoundedFailures(){
        when(api.getVariationAt(any(),any(),anyString(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(900),"mock"));
        UUID id=worker.enqueue(scope,List.of("S"));worker.tick();assertThat(jobStatus(id)).isEqualTo("WAITING");
        Instant due=jdbc.queryForObject("SELECT available_at FROM marketplace_sync_jobs WHERE id=?",java.sql.Timestamp.class,id).toInstant();assertThat(due).isEqualTo(clock.instant().plusSeconds(900));
        worker.tick();verify(api,times(1)).getVariationAt(any(),any(),anyString(),anyString());
        for(int i=0;i<7;i++){clock.advance(10000);worker.tick();}assertThat(jobStatus(id)).isEqualTo("SKIPPED");verify(api,times(8)).getVariationAt(any(),any(),anyString(),anyString());assertThat(archived()).isZero();
    }
    @Test void authorizationFailuresPauseWithoutRetry(){for(int status:List.of(401,403)){
        resetApi();when(api.getVariationAt(any(),any(),anyString(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(status,null,"mock"));
        UUID id=worker.enqueue(scope,List.of("S"));worker.tick();assertThat(jobStatus(id)).isEqualTo("SKIPPED");clock.advance(200);worker.tick();
        assertThat(read("S").state()).isEqualTo(State.UNKNOWN);
    }verify(api,times(1)).getVariationAt(any(),any(),anyString(),anyString());}
    @Test void leaseExpirationDiscardsResponseAndCrashResumesSameCursor(){
        String body=body("S","STANDALONE",List.of(),List.of());
        when(api.getVariationAt(any(),any(),anyString(),anyString())).thenAnswer(i->{clock.advance(121);return new AmazonSpApiClient.ApiResponse(200,"mock",json.readTree(body),body);});
        UUID id=worker.enqueue(scope,List.of("S"));worker.tick();assertThat(archived()).isZero();assertThat(jobStatus(id)).isEqualTo("RUNNING");
        when(api.getVariationAt(any(),any(),anyString(),anyString())).thenThrow(new AssertionError("simulated worker crash"));
        assertThatThrownBy(()->worker.tick()).isInstanceOf(AssertionError.class);assertThat(archived()).isZero();
        clock.advance(121);resetApi();response("S",body);worker.tick();assertThat(read("S").state()).isEqualTo(State.STANDALONE);assertThat(jobStatus(id)).isEqualTo("COMPLETED");
    }
    @Test void lostFenceCannotArchiveOrAdvance(){
        worker.enqueue(scope,List.of("S"));var old=jobs.claim("old");clock.advance(121);var current=jobs.claim("new");
        assertThat(current.fence()).isGreaterThan(old.fence());assertThat(jobs.<Boolean>owned(old,j->true)).isNull();worker.process(old);verifyNoInteractions(api);assertThat(archived()).isZero();
        response("S",body("S","STANDALONE",List.of(),List.of()));worker.process(current);assertThat(read("S").state()).isEqualTo(State.STANDALONE);
    }
    @Test void stricterRateIsPersistedAndPreventsPrematureRequest(){
        String b=body("S","STANDALONE",List.of(),List.of());when(api.getVariationAt(any(),any(),anyString(),contains("/S?"))).thenReturn(new AmazonSpApiClient.ApiResponse(200,"mock",json.readTree(b),b,0.25));
        worker.enqueue(scope,List.of("S","T"));worker.tick();clock.advance(2);worker.tick();verify(api,times(1)).getVariationAt(any(),any(),anyString(),anyString());
        assertThat(jdbc.queryForObject("SELECT last_value FROM amazon_variation_request_interval_ms",Long.class)).isEqualTo(4000L);
        response("T",body("T","STANDALONE",List.of(),List.of()));clock.advance(2);worker.tick();assertThat(read("T").state()).isEqualTo(State.STANDALONE);
    }
    @Test void rollbackDoesNotPartiallyPublishEvidence(){
        worker.enqueue(scope,List.of("S"));var lease=jobs.claim("test");
        var repo=new AmazonVariationRelationshipRepository(jdbc,json);var pub=new AmazonVariationRelationshipPublisher(jdbc,new AmazonSyncStore(jdbc,tx),repo,provider,json);
        assertThatThrownBy(()->jobs.owned(lease,j->{pub.publish(lease.job(),"S",body("S","STANDALONE",List.of(),List.of()),"mock",clock.instant());throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(archived()).isZero();assertThat(read("S").state()).isEqualTo(State.UNKNOWN);assertThat(jobStatus(lease.job().id())).isEqualTo("RUNNING");
    }
    @Test void concurrentEnqueueReturnsOneJobAndDatabaseRejectsDuplicate()throws Exception{
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->worker.enqueue(scope,List.of("A")));var second=executor.submit(()->worker.enqueue(scope,List.of("A")));
            UUID id=first.get();assertThat(second.get()).isEqualTo(id);
            UUID run=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end) VALUES(?,?,?,'RECONCILIATION','VARIATION_RELATIONSHIPS',now(),now())",run,tenant,connection);
            assertThatThrownBy(()->jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number) VALUES(?,?,?,'VARIATION_RELATIONSHIPS',1)",tenant,run,connection)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class).hasMessageContaining("marketplace_one_active_variation_job_idx");
            assertThat(new AmazonSyncStore(jdbc,tx).claim("general")).isNull();
        }
    }
    @Test void changedTenantOrMarketplaceCannotEnqueueOrPublish(){
        assertThatThrownBy(()->worker.enqueue(new Scope(UUID.randomUUID(),connection,scope.marketplace()),List.of("S"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->worker.enqueue(new Scope(tenant,connection,"OTHER"),List.of("S"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api);assertThat(archived()).isZero();
    }
    @Test void rawErrorEvidenceIsArchivedButNeverBecomesRelationshipProof(){
        String raw="{\"errors\":[{\"code\":\"QuotaExceeded\"}]}";
        var failure=new AmazonSpApiClient.VariationReadException(429,Duration.ofSeconds(900),raw,"mock-id",0.25);
        assertThat(failure.toString()).doesNotContain("QuotaExceeded");
        when(api.getVariationAt(any(),any(),anyString(),anyString())).thenThrow(failure);
        worker.enqueue(scope,List.of("S"));worker.tick();assertThat(read("S").state()).isEqualTo(State.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents WHERE marketplace_connection_id=?",String.class,connection)).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT last_value FROM amazon_variation_request_interval_ms",Long.class)).isEqualTo(8000L);
    }
    @Test void transientFailureRetainsFreshProofThenExpiresNaturally(){
        response("S",body("S","STANDALONE",List.of(),List.of()));run("S");var original=read("S");
        resetApi();when(api.getVariationAt(any(),any(),anyString(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(503,null,"mock"));
        worker.enqueue(scope,List.of("S"));worker.tick();assertThat(read("S").quality()).isEqualTo(Quality.VERIFIED);
        assertThat(read("S").sourceDocumentId()).isEqualTo(original.sourceDocumentId());clock.advance(25*3600);assertThat(read("S").quality()).isEqualTo(Quality.EXPIRED);
    }
    @Test void repeatedRawResponseKeepsOriginalArchiveAndAdvancesObservationRevision(){
        response("S",body("S","STANDALONE",List.of(),List.of()));run("S");var first=read("S");
        String archive=jdbc.queryForObject("SELECT to_jsonb(d)::text FROM amazon_source_documents d WHERE marketplace_connection_id=?",String.class,connection);
        run("S");assertThat(archived()).isEqualTo(1);assertThat(read("S").evidenceRevision()).isEqualTo(first.evidenceRevision()+1);
        assertThat(read("S").observedAt()).isAfter(first.observedAt());assertThat(read("S").sourceDocumentId()).isEqualTo(first.sourceDocumentId());
        assertThat(jdbc.queryForObject("SELECT to_jsonb(d)::text FROM amazon_source_documents d WHERE marketplace_connection_id=?",String.class,connection)).isEqualTo(archive);
    }
    @Test void leaseExpiringInsidePublicationRollsBackArchiveAndClassification(){
        worker.enqueue(scope,List.of("S"));var lease=jobs.claim("test");var repo=new AmazonVariationRelationshipRepository(jdbc,json);
        var pub=new AmazonVariationRelationshipPublisher(jdbc,new AmazonSyncStore(jdbc,tx),repo,provider,json);
        assertThatThrownBy(()->jobs.owned(lease,j->{pub.publish(lease.job(),"S",body("S","STANDALONE",List.of(),List.of()),"mock",clock.instant());clock.advance(121);jobs.advanceInside(lease,List.of());return true;})).isInstanceOf(IllegalStateException.class).hasMessageContaining("lease expired");
        assertThat(archived()).isZero();assertThat(read("S").state()).isEqualTo(State.UNKNOWN);assertThat(jobStatus(lease.job().id())).isEqualTo("RUNNING");
    }

}
