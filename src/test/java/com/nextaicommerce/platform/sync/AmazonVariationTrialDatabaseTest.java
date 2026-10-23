package com.nextaicommerce.platform.sync;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import static org.assertj.core.api.Assertions.*;

/** Fresh embedded cluster and synthetic records only. No UAT credentials or target. */
class AmazonVariationTrialDatabaseTest {
    static EmbeddedPostgres postgres;static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    AmazonVariationTrial trial;AmazonVariationRelationshipJobs jobs;
    @BeforeAll static void schema()throws Exception{
        javax.sql.DataSource ds;
        schema="variation_trial_"+UUID.randomUUID().toString().replace("-","");
        if(Boolean.getBoolean("localTestDatabase"))ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",
            java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim());
        else{postgres=EmbeddedPostgres.builder().setPort(0).start();ds=postgres.getPostgresDatabase();}
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("112").load().migrate();
        if(!Boolean.getBoolean("localTestDatabase"))((org.postgresql.ds.PGSimpleDataSource)ds).setCurrentSchema(schema);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,'synthetic-karaca','Synthetic fixture')",AmazonVariationTrial.TENANT);
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','synthetic-seller',?,'synthetic','ACTIVE','Synthetic fixture','UTC',now())",AmazonVariationTrial.CONNECTION,AmazonVariationTrial.TENANT,AmazonVariationTrial.MARKET);
        UUID source=UUID.randomUUID();
        jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,sha256,text_payload) VALUES (?,?,?,'INVENTORY_SNAPSHOT',?,'Synthetic baseline')",source,AmazonVariationTrial.TENANT,AmazonVariationTrial.CONNECTION,"a".repeat(64));
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,inbound_shipped_quantity,snapshot_at,source_document_id) VALUES (?,?,?,'BASELINE','ASIN1','FNSKU1',23,160,now(),?)",AmazonVariationTrial.TENANT,AmazonVariationTrial.CONNECTION,AmazonVariationTrial.MARKET,source);
    }
    @AfterAll static void close()throws Exception{if(jdbc!=null&&schema!=null&&schema.matches("variation_trial_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(postgres!=null)postgres.close();}
    @BeforeEach void fixture(){jdbc.update("DELETE FROM amazon_variation_classifications WHERE tenant_id=?",AmazonVariationTrial.TENANT);
        jdbc.update("DELETE FROM amazon_source_documents WHERE tenant_id=? AND source_type='VARIATION_RELATIONSHIPS'",AmazonVariationTrial.TENANT);
        jdbc.update("DELETE FROM amazon_variation_trial");jdbc.update("DELETE FROM marketplace_sync_jobs WHERE tenant_id=?",AmazonVariationTrial.TENANT);jdbc.update("DELETE FROM marketplace_sync_runs WHERE tenant_id=?",AmazonVariationTrial.TENANT);
        jdbc.queryForObject("SELECT setval('amazon_variation_next_request_ms',1)",Long.class);
        jdbc.queryForObject("SELECT setval('amazon_variation_request_interval_ms',1000)",Long.class);
        trial=new AmazonVariationTrial(jdbc,tx,true);jobs=new AmazonVariationRelationshipJobs(jdbc,tx,new tools.jackson.databind.ObjectMapper(),trial);}
    @Test void concurrentStartsHaveOneJobAndTerminalTrialCannotRenew()throws Exception{
        UUID key=UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->trial.start(key,jobs));var b=pool.submit(()->trial.start(key,jobs));UUID id=a.get(10,TimeUnit.SECONDS);assertThat(b.get(10,TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_trial",Integer.class)).isEqualTo(1);
            trial.inspect(true);assertThat(new AmazonVariationTrial(jdbc,tx,true).start(key,jobs)).isEqualTo(id);
            assertThatThrownBy(()->trial.start(UUID.randomUUID(),jobs)).isInstanceOf(IllegalStateException.class);
        }finally{pool.shutdownNow();}
    }
    @Test void getAndOAuthAllowancesPersistAndExhaustWithoutRefund(){
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("synthetic");assertThat(lease).isNotNull();
        for(int i=0;i<10;i++){UUID request=UUID.randomUUID();Boolean reserved=jobs.owned(lease,j->trial.reserve(lease,false,request));assertThat(reserved).isTrue();jobs.requestCompleted(lease,request);}
        Boolean denied=jobs.owned(lease,j->trial.reserve(lease,false,UUID.randomUUID()));assertThat(denied).isFalse();
        assertThat(trial.inspect(false)).containsEntry("get_reserved",10).containsEntry("state","GET_LIMIT");
        assertThat(jobs.claim("synthetic")).isNull();
    }
    @Test void oauthHasIndependentTwoAttemptCap(){
        trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("synthetic");for(int i=0;i<2;i++){UUID request=UUID.randomUUID();assertThat(jobs.reserveOAuth(lease,request)).isTrue();jobs.requestCompleted(lease,request);}assertThat(jobs.reserveOAuth(lease,UUID.randomUUID())).isFalse();
        assertThat(trial.inspect(false)).containsEntry("oauth_reserved",2).containsEntry("get_reserved",0).containsEntry("state","OAUTH_LIMIT");
    }
    @Test void cancelFencesLeaseAndDoesNotRewriteCompletedRun(){
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("synthetic");trial.inspect(true);
        Boolean published=jobs.owned(lease,j->{throw new AssertionError("Late publication");});assertThat(published).isNull();
        assertThat(trial.inspect(true)).containsEntry("state","CANCELLED");assertThat(jobs.claim("synthetic")).isNull();
    }
    @Test void expiredTrialCannotClaimOrResume(){
        UUID id=trial.start(UUID.randomUUID(),jobs);
        jdbc.update("UPDATE amazon_variation_trial SET started_at=now()-interval '6 minutes',deadline=now()-interval '1 minute' WHERE job_id=?",id);
        assertThat(jobs.claim("synthetic")).isNull();assertThat(trial.inspect(false)).containsEntry("state","DEADLINE");
    }
    @Test void crossingDeadlineDuringPublicationRollsBackEvenTerminalCompletion(){
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("synthetic");
        assertThatThrownBy(()->jobs.owned(lease,j->{
            j.update("UPDATE marketplace_sync_jobs SET records_processed=99,status='COMPLETED' WHERE id=?",id);
            j.update("UPDATE amazon_variation_trial SET state='COMPLETED',started_at=now()-interval '6 minutes',deadline=now()-interval '1 minute' WHERE job_id=?",id);return true;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isZero();
    }
    @Test void cancellationAfterCompletionDoesNotRewriteTheRun(){
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("synthetic");
        jobs.owned(lease,j->{jobs.advanceInside(lease,List.of());return true;});
        assertThat(trial.inspect(true)).containsEntry("state","COMPLETED");
        assertThat(jdbc.queryForObject("SELECT r.status FROM marketplace_sync_runs r JOIN marketplace_sync_jobs j ON j.sync_run_id=r.id WHERE j.id=?",String.class,id)).isEqualTo("COMPLETED");
    }
    @Test void inventoryIsUntouchedByStartAndCancel(){
        var before=jdbc.queryForList("SELECT * FROM amazon_inventory_snapshots");trial.start(UUID.randomUUID(),jobs);trial.inspect(true);
        assertThat(jdbc.queryForList("SELECT * FROM amazon_inventory_snapshots")).isEqualTo(before);
    }
    @Test void concurrentReservationsPermitOneRequestAndUnknownCrashOutcomeStops()throws Exception{
        UUID key=UUID.randomUUID(),id=trial.start(key,jobs);var lease=jobs.claim("first");UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)){
            var x=pool.submit(()->jobs.owned(lease,j->trial.reserve(lease,false,a)));
            var y=pool.submit(()->jobs.owned(lease,j->trial.reserve(lease,false,b)));
            assertThat(List.of(x.get(),y.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(trial.inspect(false)).containsEntry("get_reserved",1).containsEntry("request_in_flight",true);
        jobs.requestCompleted(lease,UUID.randomUUID());assertThat(trial.inspect(false)).containsEntry("request_in_flight",true);
        jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        var restarted=new AmazonVariationRelationshipJobs(jdbc,tx,new tools.jackson.databind.ObjectMapper(),new AmazonVariationTrial(jdbc,tx,true));
        var replacement=restarted.claim("replacement");assertThat(replacement.fence()).isGreaterThan(lease.fence());
        assertThat(restarted.reserveOAuth(replacement,UUID.randomUUID())).isFalse();
        assertThat(trial.inspect(false)).containsEntry("state","REQUEST_OUTCOME_UNKNOWN").containsEntry("get_reserved",1).containsEntry("oauth_reserved",0);
        assertThat(trial.start(key,restarted)).isEqualTo(id);assertThatThrownBy(()->trial.start(UUID.randomUUID(),restarted)).isInstanceOf(IllegalStateException.class);
    }
    @Test void committedReservationsSurviveUnrelatedRollbackAndNewService(){
        trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("test");UUID request=UUID.randomUUID();assertThat(jobs.reserveOAuth(lease,request)).isTrue();
        assertThatThrownBy(()->jobs.owned(lease,j->{throw new IllegalStateException("synthetic rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(new AmazonVariationTrial(jdbc,tx,true).inspect(false)).containsEntry("oauth_reserved",1).containsEntry("request_in_flight",true);
        jobs.requestCompleted(lease,request);assertThat(trial.inspect(false)).containsEntry("oauth_reserved",1).containsEntry("request_in_flight",false);
    }
    @Test void retriesUseFullTrialWindowAndPacingCannotWaitPastIt(){
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("test");
        assertThat(jobs.trialRemaining(lease)).isGreaterThan(java.time.Duration.ofSeconds(240));
        jobs.owned(lease,j->{jobs.retryInside(lease,503,null);return true;});
        assertThat(trial.inspect(false)).containsEntry("state","RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("WAITING");
        jdbc.update("UPDATE marketplace_sync_jobs SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        var next=jobs.claim("test");jdbc.queryForObject("SELECT setval('amazon_variation_next_request_ms',?)",Long.class,System.currentTimeMillis()+600000);
        assertThat(jobs.reserve(next,UUID.randomUUID())).isFalse();assertThat(trial.inspect(false)).containsEntry("state","RETRY_OUTSIDE_WINDOW").containsEntry("get_reserved",0);
    }
    @Test void cancellationAndPublicationSerializeAndTerminalOutcomeStaysIntact()throws Exception{
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("test");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelling=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var publishing=pool.submit(()->jobs.owned(lease,j->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}jobs.advanceInside(lease,List.of());return true;}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var cancel=pool.submit(()->{cancelling.countDown();return trial.inspect(true);});assertThat(cancelling.await(5,TimeUnit.SECONDS)).isTrue();release.countDown();
            assertThat(publishing.get(5,TimeUnit.SECONDS)).isTrue();assertThat(cancel.get(5,TimeUnit.SECONDS)).containsEntry("state","COMPLETED");
        }
        assertThat(jdbc.queryForObject("SELECT r.status FROM marketplace_sync_runs r JOIN marketplace_sync_jobs j ON j.sync_run_id=r.id WHERE j.id=?",String.class,id)).isEqualTo("COMPLETED");
    }
    @Test void oversizedFamilyArchivesResponseWithoutPublishingExtraMembers(){
        trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("test");var json=new tools.jackson.databind.ObjectMapper();
        var repo=new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipRepository(jdbc,json);
        var provider=new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider(repo);
        var publisher=new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipPublisher(jdbc,new AmazonSyncStore(jdbc,tx),repo,provider,json);
        var api=org.mockito.Mockito.mock(AmazonSpApiClient.class);
        String raw=json.writeValueAsString(Map.of("sku",AmazonVariationTrial.SEED,"relationships",List.of(Map.of("marketplaceId",AmazonVariationTrial.MARKET,"relationships",List.of(Map.of("type","VARIATION","childSkus",List.of("A","B","C","D")))))));
        org.mockito.Mockito.when(api.getVariationAt(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->{
            AmazonSpApiClient.VariationPermit permit=i.getArgument(4);assertThat(permit.getAsBoolean()).isTrue();permit.completed();return new AmazonSpApiClient.ApiResponse(200,"synthetic",json.readTree(raw),raw);});
        String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(i))::text FROM amazon_inventory_snapshots i",String.class);
        new AmazonVariationRelationshipSync(jobs,publisher,api,true).process(lease);
        assertThat(trial.inspect(false)).containsEntry("state","FAMILY_LIMIT").containsEntry("get_reserved",1);
        var effective=tx.execute(t->provider.classify(new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope(AmazonVariationTrial.TENANT,AmazonVariationTrial.CONNECTION,AmazonVariationTrial.MARKET),List.of(AmazonVariationTrial.SEED)).get(AmazonVariationTrial.SEED));
        assertThat(effective.reason()).isEqualTo("FAMILY_LIMIT");assertThat(effective.state()).isEqualTo(com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.State.UNKNOWN);
        assertThat(jdbc.queryForList("SELECT seller_sku FROM amazon_variation_classifications WHERE tenant_id=?",String.class,AmazonVariationTrial.TENANT)).containsExactly(AmazonVariationTrial.SEED);
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents WHERE source_type='VARIATION_RELATIONSHIPS'",String.class)).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(i))::text FROM amazon_inventory_snapshots i",String.class)).isEqualTo(before);
    }
    @Test void oauthDeadlineOrCancellationStopsGetBeforeDispatch()throws Exception{
        for(boolean cancel:List.of(false,true)){
            if(cancel)fixture();UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("test");UUID request=UUID.randomUUID();
            var credentials=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.MarketplaceCredentialService.class);
            org.mockito.Mockito.when(credentials.credentials(AmazonVariationTrial.TENANT,AmazonVariationTrial.CONNECTION)).thenReturn(Map.of("refreshToken","synthetic","lwaClientId","synthetic","lwaClientSecret","synthetic"));
            var client=new AmazonSpApiClient(credentials,new tools.jackson.databind.ObjectMapper());var http=org.mockito.Mockito.mock(java.net.http.HttpClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(client,"http",http);
            @SuppressWarnings("unchecked") java.net.http.HttpResponse<String> token=org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(token.statusCode()).thenReturn(200);org.mockito.Mockito.when(token.body()).thenReturn("{\"access_token\":\"synthetic\",\"expires_in\":3600}");
            org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),org.mockito.ArgumentMatchers.any(java.net.http.HttpResponse.BodyHandler.class))).thenAnswer(i->{
                assertThat(new AmazonVariationTrial(jdbc,tx,true).inspect(false)).containsEntry("oauth_reserved",1).containsEntry("get_reserved",0);
                if(cancel)trial.inspect(true);else jdbc.update("UPDATE amazon_variation_trial SET started_at=statement_timestamp()-interval '6 minutes',deadline=statement_timestamp()-interval '1 minute' WHERE job_id=?",id);
                return token;
            });
            var permit=new AmazonSpApiClient.VariationPermit(){public boolean oauth(){return jobs.reserveOAuth(lease,request);}public boolean getAsBoolean(){return jobs.reserve(lease,request);}public java.time.Duration remaining(){return jobs.trialRemaining(lease);}public void completed(){jobs.requestCompleted(lease,request);}};
            assertThatThrownBy(()->client.getVariationAt(AmazonVariationTrial.TENANT,AmazonVariationTrial.CONNECTION,"https://sellingpartnerapi-na.amazon.com","/listings/2021-08-01/items/synthetic/"+AmazonVariationTrial.SEED,permit)).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);
            org.mockito.Mockito.verify(http,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),org.mockito.ArgumentMatchers.any(java.net.http.HttpResponse.BodyHandler.class));
            assertThat(trial.inspect(false)).containsEntry("state",cancel?"CANCELLED":"DEADLINE").containsEntry("oauth_reserved",1).containsEntry("get_reserved",0);
        }
    }
    @Test void trialRlsRejectsUnscopedAndForeignTenantAccess(){
        trial.start(UUID.randomUUID(),jobs);String role="variation_trial_reader_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try{
            jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT,UPDATE ON amazon_variation_trial TO "+role);
            tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id','',true)",String.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_trial",Integer.class)).isZero();
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.update("UPDATE amazon_variation_trial SET get_reserved=1")).isZero();
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,AmazonVariationTrial.TENANT.toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_trial",Integer.class)).isEqualTo(1);
            });
        }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
    }

    @Test void disabledOperatorPreventsResumeAndAcquisitionOffStillAllowsCancellation(){
        UUID id=trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("active");
        var disabledTrial=new AmazonVariationTrial(jdbc,tx,false);
        var guarded=new AmazonVariationRelationshipJobs(jdbc,tx,new tools.jackson.databind.ObjectMapper(),disabledTrial);
        assertThat(guarded.claim("disabled")).isNull();assertThat(guarded.reserveOAuth(lease,UUID.randomUUID())).isFalse();
        var api=org.mockito.Mockito.mock(AmazonSpApiClient.class);var publisher=org.mockito.Mockito.mock(com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipPublisher.class);
        var sync=new AmazonVariationRelationshipSync(jobs,publisher,api,false);sync.tick();sync.process(lease);
        trial.inspect(true);assertThat(trial.inspect(false)).containsEntry("state","CANCELLED").containsEntry("get_reserved",0).containsEntry("oauth_reserved",0);
        org.mockito.Mockito.verifyNoInteractions(api,publisher);assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("SKIPPED");
    }
    @Test void foreignScopeCannotReadDeadlineOrReleaseTheApprovedRequest(){
        trial.start(UUID.randomUUID(),jobs);var lease=jobs.claim("active");UUID request=UUID.randomUUID();assertThat(jobs.reserveOAuth(lease,request)).isTrue();
        var original=lease.job();var foreign=new AmazonVariationRelationshipJobs.Lease(new AmazonSyncStore.Job(original.id(),UUID.randomUUID(),original.runId(),original.connectionId(),original.type(),original.profile(),original.marketplaceId(),original.windowStart(),original.windowEnd(),null,original.attempts(),0,false,null),lease.owner(),lease.fence(),lease.cursor(),lease.seller());
        assertThatThrownBy(()->jobs.trialRemaining(foreign)).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);jobs.requestCompleted(foreign,request);
        assertThat(trial.inspect(false)).containsEntry("request_in_flight",true).containsEntry("oauth_reserved",1);jobs.requestCompleted(lease,request);
    }

}
