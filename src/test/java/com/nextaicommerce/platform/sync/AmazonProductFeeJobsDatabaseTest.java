package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AmazonProductFeeJobsDatabaseTest {
    static String schema;static JdbcTemplate jdbc;static TransactionTemplate tx;static String publicationRole;
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;
    final ObjectMapper json=new ObjectMapper();UUID tenant,connection,run,id;AmazonSyncStore store;AmazonProductFeeJobs jobs;AmazonSyncStore.Job job;
    static final String MARKET="ATVPDKIKX0DER",SKU="00001 / Exact";
    @BeforeAll static void database()throws Exception{
        schema="fee_jobs_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=Files.readString(Path.of(".local/database-password")).trim();}
        else{schema="public"; // This branch owns a disposable cluster; match production function lookup.
        embedded=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=embedded.getJdbcUrl("postgres","postgres");}
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        publicationRole=schema+"_pub";jdbc.execute("CREATE ROLE "+publicationRole+" NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+publicationRole);
        jdbc.execute("GRANT SELECT ON tenants,marketplace_connections,marketplace_definitions,marketplace_sync_jobs,marketplace_sync_runs,amazon_listings,amazon_variation_classifications,amazon_fee_estimates,amazon_fee_observations,amazon_source_documents,flyway_schema_history TO "+publicationRole);
        jdbc.execute("GRANT SELECT(tenant_id,sync_job_id,created_at) ON amazon_report_requests TO "+publicationRole);
        jdbc.execute("GRANT INSERT ON amazon_source_documents,amazon_fee_estimates,amazon_fee_observations TO "+publicationRole);
        jdbc.execute("GRANT UPDATE ON marketplace_sync_jobs,marketplace_sync_runs TO "+publicationRole);
        jdbc.execute("GRANT UPDATE(updated_at) ON marketplace_connections,amazon_listings TO "+publicationRole);
    }
    @AfterAll static void cleanup()throws Exception{if(jdbc!=null&&schema.matches("fee_jobs_[a-f0-9]{32}")){jdbc.execute("DROP SCHEMA "+schema+" CASCADE");jdbc.execute("DROP ROLE "+publicationRole);}if(embedded!=null)embedded.close();}
    @BeforeEach void fixture(){
        jdbc.execute("TRUNCATE "+schema+".tenants CASCADE");tenant=UUID.randomUUID();connection=UUID.randomUUID();run=UUID.randomUUID();id=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Synthetic')",tenant,tenant.toString());
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,?,'fixture','ACTIVE','Synthetic','America/Los_Angeles',now())",connection,tenant,connection.toString(),MARKET);
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end,status) VALUES (?,?,?,'RECONCILIATION',now(),now(),'RUNNING')",run,tenant,connection);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status,lease_owner,lease_expires_at,started_at,attempt_count,required_for_ready) VALUES (?,?,?,?,'FEES_SNAPSHOT',1,'RUNNING','fixture',now()+interval '3 minutes',now(),1,false)",id,tenant,run,connection);
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel) VALUES (?,?,?,?,30,'USD','AFN')",tenant,connection,MARKET,SKU);
        store=new AmazonSyncStore(jdbc,tx);jobs=new AmazonProductFeeJobs(jdbc,tx,json,store,0.5);job=job(tenant,connection,MARKET,"fixture",0);
    }
    AmazonSyncStore.Job job(UUID t,UUID c,String market,String owner,int attempts){return new AmazonSyncStore.Job(id,t,run,c,"FEES_SNAPSHOT","FBA_FEES",market,Instant.now(),Instant.now(),null,attempts,0,false,null,owner);}
    String status(){return jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id);}
    String reason(){return jdbc.queryForObject("SELECT error_code FROM marketplace_sync_jobs WHERE id=?",String.class,id);}
    ObjectNode fee(){return (ObjectNode)json.readTree(jdbc.queryForObject("SELECT cursor->'productFees' FROM marketplace_sync_jobs WHERE id=?",String.class,id));}
    void state(String name,Object value){jdbc.update("UPDATE marketplace_sync_jobs SET cursor=jsonb_set(cursor,ARRAY['productFees',?],CAST(? AS jsonb)) WHERE id=?",name,json.writeValueAsString(value),id);}
    void resumed(){jdbc.update("UPDATE marketplace_sync_jobs SET status='RUNNING',lease_owner='fixture',lease_expires_at=now()+interval '3 minutes' WHERE id=?",id);}
    AmazonSpApiClient.ApiResponse response(){String body="""
        {"payload":{"FeesEstimateResult":{"Status":"Success","FeesEstimateIdentifier":{"IdType":"SellerSKU","IdValue":"00001 / Exact","MarketplaceId":"ATVPDKIKX0DER","SellerInputIdentifier":"test"},"FeesEstimate":{"TotalFeesEstimate":{"Amount":8,"CurrencyCode":"USD"},"FeeDetailList":[{"FeeType":"ReferralFee","FeeAmount":{"Amount":3,"CurrencyCode":"USD"}},{"FeeType":"FBAFees","FeeAmount":{"Amount":5,"CurrencyCode":"USD"}}]}}}}
        """;return new AmazonSpApiClient.ApiResponse(200,"synthetic-request",json.readTree(body),body);}
    void publish(){var t=jobs.next(job);var response=response();var r=response.json().path("payload").path("FeesEstimateResult");jobs.publish(job,t,jobs.basis(job,t),r,AmazonProductFeeSync.amounts(r.path("FeesEstimate"),"USD"),response);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}

    @Test void defaultOffStopsWithoutCallingAmazon(){var api=mock(AmazonSpApiClient.class);new AmazonProductFeeSync(api,jobs,false).processPage(job);verifyNoInteractions(api);assertThat(status()).isEqualTo("SKIPPED");assertThat(reason()).isEqualTo("FEE_COLLECTION_DISABLED");}
    @Test void exactSkuAndMissingEvidenceDoNotCreateFees(){assertThat(jobs.next(job).sku()).isEqualTo(SKU);assertThat(count("amazon_fee_estimates")).isZero();}
    @Test void ownershipAndAttemptGenerationAreRequired(){assertThat(jobs.next(job(tenant,connection,MARKET,"stale",0))).isNull();assertThat(jobs.next(job(tenant,connection,MARKET,"fixture",1))).isNull();assertThat(count("amazon_source_documents")).isZero();}
    @Test void tenantStoreAndMarketplaceAreIsolated(){assertThat(jobs.next(job(UUID.randomUUID(),connection,MARKET,"fixture",0))).isNull();assertThat(jobs.next(job(tenant,UUID.randomUUID(),MARKET,"fixture",0))).isNull();assertThat(jobs.next(job(tenant,connection,"OTHER","fixture",0))).isNull();}
    @Test void cancellationAndExpiredLeasesBlockPublication(){jobs.next(job);jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=?",run);assertThat(jobs.next(job)).isNull();jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING' WHERE id=?",run);jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?",id);assertThat(jobs.next(job)).isNull();assertThat(count("amazon_fee_estimates")).isZero();}
    @Test void archivedResponseAndFeeAndCursorPublishTogether(){publish();assertThat(status()).isEqualTo("WAITING");assertThat(count("amazon_fee_estimates")).isOne();assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents",String.class)).isEqualTo(response().body());assertThat(jdbc.queryForObject("SELECT cursor->>'feeSku' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo(SKU);assertThat(count("inventory_ledger_entries")).isZero();assertThat(count("amazon_inventory_snapshots")).isZero();}
    @Test void lateWorkerCannotPublishAfterNewLeaseGeneration(){var t=jobs.next(job);var r=response();jdbc.update("UPDATE marketplace_sync_jobs SET attempt_count=2 WHERE id=?",id);jobs.publish(job,t,jobs.basis(job,t),r.json().path("payload").path("FeesEstimateResult"),new AmazonProductFeeSync.Amounts(BigDecimal.valueOf(8),BigDecimal.valueOf(3),BigDecimal.valueOf(5)),r);assertThat(count("amazon_fee_estimates")).isZero();assertThat(count("amazon_source_documents")).isZero();}
    @Test void publicationRollbackRetainsNeitherArchiveNorCursor(){var t=jobs.next(job);var r=response();jdbc.execute("ALTER TABLE amazon_fee_estimates ADD CONSTRAINT fixture_no_fee CHECK(fulfillment_fee<0)");try{assertThatThrownBy(()->jobs.publish(job,t,jobs.basis(job,t),r.json().path("payload").path("FeesEstimateResult"),new AmazonProductFeeSync.Amounts(BigDecimal.valueOf(8),BigDecimal.valueOf(3),BigDecimal.valueOf(5)),r)).isInstanceOf(RuntimeException.class);assertThat(count("amazon_source_documents")).isZero();assertThat(jdbc.queryForObject("SELECT cursor->>'feeSku' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isNull();}finally{jdbc.execute("ALTER TABLE amazon_fee_estimates DROP CONSTRAINT fixture_no_fee");}}
    @Test void completeUnchangedQuoteReusesWithoutRetimingOrAppending(){publish();var before=jdbc.queryForObject("SELECT effective_at FROM amazon_fee_estimates",java.sql.Timestamp.class);resumed();jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor-'feeSku' WHERE id=?",id);var t=jobs.next(job);assertThat(jobs.reuse(job,t,jobs.basis(job,t))).isTrue();assertThat(count("amazon_fee_estimates")).isOne();assertThat(jdbc.queryForObject("SELECT effective_at FROM amazon_fee_estimates",java.sql.Timestamp.class)).isEqualTo(before);assertThat(fee().path("cacheHits").asInt()).isOne();}
    @Test void changedPriceCurrencyFbaOrShippingCannotHitCache(){publish();resumed();jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor-'feeSku' WHERE id=?",id);var t=jobs.next(job);var b=jobs.basis(job,t);b.withObject("PriceToEstimateFees").withObject("ListingPrice").put("Amount",31);assertThat(jobs.reuse(job,t,b)).isFalse();b=jobs.basis(job,t);b.put("IsAmazonFulfilled",false);assertThat(jobs.reuse(job,t,b)).isFalse();b=jobs.basis(job,t);b.withObject("PriceToEstimateFees").withObject("Shipping").put("Amount",1);assertThat(jobs.reuse(job,t,b)).isFalse();assertThat(jobs.reuse(job,new AmazonProductFeeJobs.Target(SKU,BigDecimal.valueOf(30),"CAD"),jobs.basis(job,t))).isFalse();}
    @Test void missingBasisExpiredAndFutureEvidenceAreCacheMisses(){publish();resumed();jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor-'feeSku' WHERE id=?",id);var t=jobs.next(job);var b=jobs.basis(job,t);jdbc.update("UPDATE amazon_fee_estimates SET effective_at=now()-interval '25 hours'");assertThat(jobs.reuse(job,t,b)).isFalse();jdbc.update("UPDATE amazon_fee_estimates SET effective_at=now()+interval '1 hour'");assertThat(jobs.reuse(job,t,b)).isFalse();jdbc.update("UPDATE amazon_fee_estimates SET effective_at=now(),raw_payload=raw_payload-'_platformQuoteBasis'");assertThat(jobs.reuse(job,t,b)).isFalse();}
    @Test void threeAttemptsAreAHardLimit(){jobs.next(job);for(int i=0;i<3;i++){state("nextAt",Instant.now().minusSeconds(1).toString());assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);}jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor#-'{productFees,nextAt}' WHERE id=?",id);assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(reason()).isEqualTo("FEE_REQUEST_LIMIT");}
    @Test void fiveHundredFeeAttemptsAndTwoOauthAttemptsCannotBeExceeded(){jobs.next(job);state("http",500);assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(reason()).isEqualTo("FEE_REQUEST_LIMIT");resumed();state("http",0);assertThat(jobs.reserve(job,SKU,"OAUTH")).isTrue();jobs.received(job);assertThat(jobs.reserve(job,SKU,"OAUTH")).isTrue();jobs.received(job);assertThat(jobs.reserve(job,SKU,"OAUTH")).isFalse();assertThat(reason()).isEqualTo("FEE_OAUTH_LIMIT");}
    @Test void thirtyMinuteDeadlineCannotBeResetByResume(){jdbc.update("UPDATE marketplace_sync_jobs SET started_at=now()-interval '31 minutes' WHERE id=?",id);assertThat(jobs.next(job)).isNull();assertThat(reason()).isEqualTo("FEE_DEADLINE");}
    @Test void retryAfterCannotBypassDeadlineOrAttemptLimit(){jobs.next(job);jobs.failed(job,SKU,new AmazonSpApiClient.AmazonApiException(429,Duration.ofHours(1),"synthetic"),null,null,null);assertThat(reason()).isEqualTo("FEE_RETRY_OUTSIDE_WINDOW");resumed();state("skuAttempts",3);jobs.failed(job,SKU,new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(1),"synthetic"),null,null,null);assertThat(reason()).isEqualTo("FEE_RETRY_EXHAUSTED");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={401,403}) void authorizationStopsAndPreservesRawFailure(int status){jobs.next(job);jobs.failed(job,SKU,new AmazonSpApiClient.AmazonApiException(status,null,"synthetic"),"not-json", "fixture",null);assertThat(reason()).isEqualTo("FEE_AUTHORIZATION_REQUIRED");assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents",String.class)).isEqualTo("not-json");assertThat(count("amazon_fee_estimates")).isZero();}
    @Test void crashMarkerIsNotSilentlyReplayed(){jobs.next(job);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();assertThat(jobs.next(job)).isNull();assertThat(reason()).isEqualTo("FEE_REQUEST_OUTCOME_UNKNOWN");assertThat(fee().path("http").asInt()).isOne();}
    @Test void lowerObservedRatePersistsAcrossClaims(){var t=jobs.next(job);var response=response();var lower=new AmazonSpApiClient.ApiResponse(200,response.requestId(),response.json(),response.body(),0.25);var r=lower.json().path("payload").path("FeesEstimateResult");jobs.publish(job,t,jobs.basis(job,t),r,new AmazonProductFeeSync.Amounts(BigDecimal.valueOf(8),BigDecimal.valueOf(3),BigDecimal.valueOf(5)),lower);assertThat(fee().path("intervalMs").asLong()).isEqualTo(4000);resumed();assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(status()).isEqualTo("WAITING");}
    @Test void malformedStateCannotResetBudgets(){jobs.next(job);state("http",-1);assertThat(jobs.next(job)).isNull();assertThat(reason()).isEqualTo("FEE_STATE_INVALID");}
    @Test void actualClaimCarriesFenceAndSkipsDuplicateActiveWork(){jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0 WHERE id=?",id);var first=store.claim("worker-one");assertThat(first.id()).isEqualTo(id);assertThat(first.leaseOwner()).isEqualTo("worker-one");assertThat(jobs.next(first).sku()).isEqualTo(SKU);UUID otherRun=UUID.randomUUID(),otherJob=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end) VALUES (?,?,?,'RECONCILIATION',now(),now())",otherRun,tenant,connection);jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number) VALUES (?,?,?,?,'FEES_SNAPSHOT',1)",otherJob,tenant,otherRun,connection);assertThat(store.claim("worker-two")).isNull();assertThat(jdbc.queryForObject("SELECT error_code FROM marketplace_sync_jobs WHERE id=?",String.class,otherJob)).isEqualTo("FEE_DUPLICATE_ACTIVE");}
    @Test void defaultRateConfigurationCannotExceedApprovedCeiling(){assertThatThrownBy(()->new AmazonProductFeeJobs(jdbc,tx,json,store,1)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new AmazonProductFeeJobs(jdbc,tx,json,store,0)).isInstanceOf(IllegalArgumentException.class);}
    @Test void globalPermitSerializesStoresAndPropagatesLowerRateWithoutSharingEvidence(){
        jobs.next(job);UUID tenant2=UUID.randomUUID(),store2=UUID.randomUUID(),run2=UUID.randomUUID(),job2=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Other synthetic')",tenant2,tenant2.toString());
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,?,'fixture','ACTIVE','Other','America/Los_Angeles',now())",store2,tenant2,store2.toString(),MARKET);
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end,status) VALUES (?,?,?,'RECONCILIATION',now(),now(),'RUNNING')",run2,tenant2,store2);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status,lease_owner,lease_expires_at,started_at,attempt_count,required_for_ready) VALUES (?,?,?,?,'FEES_SNAPSHOT',1,'RUNNING','other',now()+interval '3 minutes',now(),1,false)",job2,tenant2,run2,store2);
        var other=new AmazonSyncStore.Job(job2,tenant2,run2,store2,"FEES_SNAPSHOT","FBA_FEES",MARKET,Instant.now(),Instant.now(),null,0,0,false,null,"other");
        try(var first=jobs.permit(job,SKU);var second=jobs.permit(other,"OTHER")){
            assertThat(first.getAsBoolean()).isTrue();assertThat(second.getAsBoolean()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,job2)).isEqualTo("WAITING");
            assertThat(jdbc.queryForObject("SELECT (cursor->'productFees'->>'http')::int FROM marketplace_sync_jobs WHERE id=?",Integer.class,job2)).isZero();
            first.observedRate(0.25);first.completed();
            jdbc.update("UPDATE marketplace_sync_jobs SET status='RUNNING',lease_owner='other',lease_expires_at=now()+interval '3 minutes' WHERE id=?",job2);
            assertThat(second.getAsBoolean()).isFalse(); // Other store honors the shared lower-rate cooldown.
            assertThat(fee().path("http").asInt()).isOne();assertThat(count("amazon_fee_estimates")).isZero();
        }
    }
    @Test void noHistoricalInventoryMutation(){
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,snapshot_at,fulfillable_quantity) VALUES (?,?,?,?,now()-interval '7 days',17)",tenant,connection,MARKET,SKU);
        var before=jdbc.queryForMap("SELECT snapshot_at,fulfillable_quantity FROM amazon_inventory_snapshots");publish();
        assertThat(jdbc.queryForMap("SELECT snapshot_at,fulfillable_quantity FROM amazon_inventory_snapshots")).isEqualTo(before);
        assertThat(count("inventory_ledger_entries")).isZero();assertThat(count("marketplace_sku_mappings")).isZero();
    }
    @Test void malformedAndUnknownQuoteResponsesNeverCreateFees(){
        var api=mock(AmazonSpApiClient.class);
        when(api.quoteProductFee(any(),any(),anyString(),anyString(),any())).thenAnswer(call->{
            var permit=(AmazonSpApiClient.FeePermit)call.getArgument(4);assertThat(permit.getAsBoolean()).isTrue();permit.completed();
            return new AmazonSpApiClient.ApiResponse(200,"bad",json.createObjectNode(),"malformed-exact-body");
        });
        new AmazonProductFeeSync(api,jobs,true).processPage(job);
        assertThat(count("amazon_fee_estimates")).isZero();assertThat(status()).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents",String.class)).isEqualTo("malformed-exact-body");
    }
    @Test void cachedQuotesRequireTheirOwnArchiveAndExactSellerSku(){publish();resumed();jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor-'feeSku' WHERE id=?",id);var t=jobs.next(job);assertThat(jobs.reuse(job,new AmazonProductFeeJobs.Target("0001",t.price(),t.currency()),jobs.basis(job,t))).isFalse();jdbc.update("UPDATE amazon_fee_estimates SET source_document_id=NULL");assertThat(jobs.reuse(job,t,jobs.basis(job,t))).isFalse();}
    @Test void requestLimitIsCheckedBeforeSpendingOauth(){jobs.next(job);state("http",500);var api=mock(AmazonSpApiClient.class);new AmazonProductFeeSync(api,jobs,true).processPage(job);verifyNoInteractions(api);assertThat(reason()).isEqualTo("FEE_REQUEST_LIMIT");}

    @Test void compatibleTwentyThreeHourQuoteCanStillBeReused(){publish();resumed();jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor-'feeSku' WHERE id=?",id);jdbc.update("UPDATE amazon_fee_estimates SET effective_at=now()-interval '23 hours'");var t=jobs.next(job);assertThat(jobs.reuse(job,t,jobs.basis(job,t))).isTrue();}
    @Test void staleFailureCannotOverwriteNewWorkerCursor(){jobs.next(job);jdbc.update("UPDATE marketplace_sync_jobs SET attempt_count=2,lease_owner='new-worker' WHERE id=?",id);var before=jdbc.queryForMap("SELECT status,cursor,lease_owner,attempt_count FROM marketplace_sync_jobs WHERE id=?",id);jobs.failed(job,SKU,new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(30),"synthetic"),"raw", "fixture",null);assertThat(jdbc.queryForMap("SELECT status,cursor,lease_owner,attempt_count FROM marketplace_sync_jobs WHERE id=?",id)).isEqualTo(before);assertThat(count("amazon_source_documents")).isZero();}
    @Test void cancelledRunIsNotReclaimed(){jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=?",run);jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=now()-interval '1 minute' WHERE id=?",id);assertThat(store.claim("other")).isNull();}

    @Test void leaseExpiryDuringPublicationRollsBackArchiveFeeAndCursor(){
        var target=jobs.next(job);var response=response();
        jdbc.execute("CREATE FUNCTION "+schema+".expire_fee_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN UPDATE marketplace_sync_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id='"+id+"'; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER expire_fee_fixture BEFORE INSERT ON amazon_fee_estimates FOR EACH ROW EXECUTE FUNCTION "+schema+".expire_fee_fixture()");
        try{
            var result=response.json().path("payload").path("FeesEstimateResult");
            assertThatThrownBy(()->jobs.publish(job,target,jobs.basis(job,target),result,new AmazonProductFeeSync.Amounts(BigDecimal.valueOf(8),BigDecimal.valueOf(3),BigDecimal.valueOf(5)),response)).isInstanceOf(RuntimeException.class);
            assertThat(count("amazon_source_documents")).isZero();assertThat(count("amazon_fee_estimates")).isZero();
            assertThat(jdbc.queryForObject("SELECT cursor->>'feeSku' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isNull();
        }finally{jdbc.execute("DROP TRIGGER expire_fee_fixture ON amazon_fee_estimates");jdbc.execute("DROP FUNCTION "+schema+".expire_fee_fixture()");}
    }

    @Test void changedSellerBindingStopsInsteadOfReusingAnotherSellerEvidence(){jobs.next(job);jdbc.update("UPDATE marketplace_connections SET seller_identifier='changed-seller' WHERE id=?",connection);assertThat(jobs.next(job)).isNull();assertThat(reason()).isEqualTo("FEE_SCOPE_CHANGED");assertThat(count("amazon_fee_estimates")).isZero();}
    @Test void archiveDedupNeverRewritesEarlierEvidenceAndDoesNotQueueReportNormalization(){
        jobs.next(job);jobs.failed(job,SKU,new IllegalStateException("synthetic"),"exact-raw-failure","first",null);
        var before=jdbc.queryForMap("SELECT * FROM amazon_source_documents");assertThat(before.get("normalized_at")).isNotNull();
        resumed();jobs.failed(job,SKU,new IllegalStateException("synthetic"),"exact-raw-failure","second",null);
        assertThat(count("amazon_source_documents")).isOne();assertThat(jdbc.queryForMap("SELECT * FROM amazon_source_documents")).isEqualTo(before);
    }

    void batchScope(){jdbc.update("UPDATE amazon_listings SET listing_status='ACTIVE' WHERE tenant_id=?",tenant);jdbc.update("UPDATE marketplace_sync_jobs SET cursor=jsonb_set(cursor,'{shippingBasisConfirmed}','true') WHERE id=?",id);}
    @Test void batchManifestCacheUsesOneSetQueryForTwoHundredExactTargets(){
        batchScope();jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,listing_status) SELECT ?,?,?,'CACHE-'||n,30,'USD','AFN','ACTIVE' FROM generate_series(1,199) n",tenant,connection,MARKET);
        var observed=spy(jdbc);var measured=new AmazonProductFeeJobs(observed,tx,json,store,0.5);long start=System.nanoTime();
        assertThat(measured.batchNext(job)).hasSize(20);
        long queries=mockingDetails(observed).getInvocations().stream().filter(call->Set.of("queryForObject","queryForList").contains(call.getMethod().getName())&&call.getArguments().length>0&&call.getArgument(0) instanceof String sql&&sql.contains("amazon_fee_estimates")).count();
        System.out.println("FEE_BATCH_MANIFEST_SYNTHETIC targets=200 cache_queries="+queries+" elapsed_ms="+(System.nanoTime()-start)/1000000);
        assertThat(queries).isEqualTo(1);
    }
    ObjectNode pilot(){
        batchScope();var contract=json.createObjectNode().put("format","FEE_RUN_V1").put("approval",UUID.randomUUID().toString())
            .put("tenant",tenant.toString()).put("connection",connection.toString()).put("marketplace",MARKET).put("seller",connection.toString())
            .put("maxTargets",20).put("maxHttp",3).put("maxOauth",2).put("maxSeconds",300).put("approvalExpiresAt",Instant.now().plusSeconds(600).toString()).put("publicationMode","ARCHIVE_ONLY");
        contract.putArray("skus").add(SKU);
        var basis=json.createArrayNode();basis.addArray().add(SKU).add("30").add("USD").add("FBA").add("0");
        contract.put("basisSha256",com.nextaicommerce.platform.fees.AmazonFeeEvidence.digest(basis.toString()));pilot(contract);return contract;
    }
    void pilot(ObjectNode contract){jdbc.update("UPDATE marketplace_sync_jobs SET cursor=jsonb_set(cursor,'{feeRun}',CAST(? AS jsonb)) WHERE id=?",contract.toString(),id);}
    @Test void pilotExactAllowlistNeverAddsUnapprovedTargets(){
        pilot();jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,listing_status) VALUES (?,?,?,'UNAPPROVED',30,'USD','AFN','ACTIVE')",tenant,connection,MARKET);
        assertThat(jobs.batchNext(job)).extracting(AmazonProductFeeJobs.Target::sku).containsExactly(SKU);
    }
    @Test void pilotLowerHttpBudgetSurvivesResume(){
        var contract=pilot().put("maxHttp",1);pilot(contract);jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);
        resumed();state("nextAt",Instant.now().minusSeconds(1).toString());assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(reason()).isEqualTo("FEE_REQUEST_LIMIT");
    }
    @Test void pilotMalformedOrCrossScopeContractFailsClosed(){
        for(String field:List.of("maxHttp","maxOauth","maxTargets","maxSeconds","tenant","connection","marketplace","seller","duplicates","unknown")){
            fixture();var contract=pilot();
            if(field.startsWith("max"))contract.put(field,0);else if(field.equals("duplicates"))((tools.jackson.databind.node.ArrayNode)contract.path("skus")).add(SKU);else if(field.equals("unknown"))contract.put("extraLimit",1);else contract.put(field,"OTHER");
            pilot(contract);assertThat(jobs.batchNext(job)).as(field).isEmpty();assertThat(reason()).as(field).isEqualTo("FEE_RUN_INVALID");
        }
    }
    @Test void pilotDeadlineUsesOriginalStartAndCannotExtendOnResume(){
        pilot();jdbc.update("UPDATE marketplace_sync_jobs SET started_at=now()-interval '301 seconds' WHERE id=?",id);
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_DEADLINE");
    }
    @Test void pilotFrozenCapsAndCountersCannotBeIndividuallyReset(){
        var contract=pilot();jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"OAUTH")).isTrue();jobs.received(job);
        state("oauth",0);assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_STATE_INVALID");
        fixture();contract=pilot();jobs.batchNext(job);pilot(contract.put("maxHttp",4));
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_STATE_INVALID");
    }
    @Test void pilotLostStateCannotReinitializeItsReservations(){
        pilot();jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"OAUTH")).isTrue();jobs.received(job);
        jdbc.update("UPDATE marketplace_sync_jobs SET cursor=cursor-'productFees' WHERE id=?",id);
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_STATE_INVALID");
    }
    @Test void pilotChangedPriceCannotReplaceApprovedBasis(){
        pilot();jdbc.update("UPDATE amazon_listings SET price=31 WHERE tenant_id=?",tenant);
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_BASIS_CHANGED");
    }
    @Test void pilotLeaseCancellationAndUnknownOutcomeKeepReservations(){
        pilot();jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();
        resumed();assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_REQUEST_OUTCOME_UNKNOWN");assertThat(fee().path("http").asInt()).isEqualTo(1);
        fixture();pilot();jobs.batchNext(job);jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?",id);
        assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(fee().path("http").asInt()).isZero();
        fixture();pilot();jobs.batchNext(job);jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=?",run);
        assertThat(jobs.reserve(job,SKU,"OAUTH")).isFalse();assertThat(fee().path("oauth").asInt()).isZero();
        fixture();pilot();jobs.batchNext(job);jdbc.update("UPDATE marketplace_sync_jobs SET attempt_count=2 WHERE id=?",id);
        assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(fee().path("http").asInt()).isZero();
    }
    @Test void pilotManifestAndDeadlineEditsCannotWidenAResume(){
        pilot();jobs.batchNext(job);var manifest=(tools.jackson.databind.node.ArrayNode)fee().path("batchManifest");((ObjectNode)manifest.get(0)).put("price",31);state("batchManifest",manifest);
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_STATE_INVALID");
        fixture();pilot();jobs.batchNext(job);state("deadline",Instant.now().plusSeconds(1800).toString());
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_STATE_INVALID");
    }
    @Test void pilotLowerOauthLimitCannotBeResetByNewLease(){
        var contract=pilot().put("maxOauth",1);pilot(contract);jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"OAUTH")).isTrue();jobs.received(job);resumed();
        assertThat(jobs.reserve(job,SKU,"OAUTH")).isFalse();assertThat(reason()).isEqualTo("FEE_OAUTH_LIMIT");assertThat(fee().path("oauth").asInt()).isEqualTo(1);
    }
    @Test void pilotConcurrentReservationsCannotSpendTheSameBudgetTwice()throws Exception{
        pilot();jobs.batchNext(job);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->{start.await();return jobs.reserve(job,SKU,"FEE");});var two=pool.submit(()->{start.await();return jobs.reserve(job,SKU,"FEE");});start.countDown();
            assertThat(List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(fee().path("http").asInt()).isEqualTo(1);assertThat(fee().path("runReservations")).hasSize(1);
    }
    @Test void populatedSetCacheQueryHasBoundedOutputAndWork(){
        batchScope();UUID source=UUID.randomUUID();jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,source_key,content_type,sha256,payload) VALUES (?,?,?,'FEES_SNAPSHOT','cache-benchmark','application/json',?,'{}')",source,tenant,connection,source.toString());
        jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,estimated_fee_total,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) SELECT ?,?,?,'BENCH-'||(n%1000),'USD',8,3,5,now()-interval '2 days'-n*interval '1 second','{}',? FROM generate_series(1,10000) n",tenant,connection,MARKET,source);
        UUID otherTenant=UUID.randomUUID(),otherStore=UUID.randomUUID(),otherSource=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Other benchmark')",otherTenant,otherTenant.toString());
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','OTHER',?,'ACTIVE','Other benchmark','fixture','America/Los_Angeles',now())",otherStore,otherTenant,MARKET);
        jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,source_key,content_type,sha256,payload) VALUES (?,?,?,'FEES_SNAPSHOT','other-cache','application/json',?,'{}')",otherSource,otherTenant,otherStore,otherSource.toString());
        jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,estimated_fee_total,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) SELECT ?,?,?,'BENCH-'||(n%1000),'USD',8,3,5,now()-n*interval '1 second','{}',? FROM generate_series(1,10000) n",otherTenant,otherStore,MARKET,otherSource);
        var exactBasis=jobs.basis(job,new AmazonProductFeeJobs.Target("BENCH-0",new BigDecimal("30"),"USD"));
        var raw=json.createObjectNode().put("Status","Success").put("_platformFeeContract",AmazonProductFeeBatch.CONTRACT);raw.set("_platformQuoteBasis",exactBasis);
        raw.putObject("FeesEstimateIdentifier").put("IdType","SellerSKU").put("IdValue","BENCH-0").put("MarketplaceId",MARKET).put("SellerId",connection.toString());
        jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,estimated_fee_total,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES (?,?,?,'BENCH-0','USD',8,3,5,now(),CAST(? AS jsonb),?)",tenant,connection,MARKET,raw.toString(),source);
        jdbc.execute("ANALYZE amazon_fee_estimates");jdbc.execute("ANALYZE amazon_source_documents");
        var targets=json.createArrayNode();for(int n=0;n<200;n++){var node=targets.addObject().put("sku","BENCH-"+n).put("currency","USD");node.set("basis",jobs.basis(job,new AmazonProductFeeJobs.Target("BENCH-"+n,new BigDecimal("30"),"USD")));}
        String plan=jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+AmazonProductFeeJobs.BATCH_CACHE_SQL,String.class,targets.toString(),tenant,connection,MARKET,java.sql.Timestamp.from(Instant.now().minusSeconds(86400)),java.sql.Timestamp.from(Instant.now()),AmazonProductFeeBatch.CONTRACT);
        var cached=jdbc.queryForList(AmazonProductFeeJobs.BATCH_CACHE_SQL,String.class,targets.toString(),tenant,connection,MARKET,java.sql.Timestamp.from(Instant.now().minusSeconds(86400)),java.sql.Timestamp.from(Instant.now()),AmazonProductFeeBatch.CONTRACT);
        assertThat(cached).containsExactly("BENCH-0");
        var root=json.readTree(plan).get(0);assertThat(root.path("Plan").path("Actual Rows").asInt()).isEqualTo(1);
        var nodes=new ArrayDeque<tools.jackson.databind.JsonNode>();nodes.add(root.path("Plan"));long examined=0;boolean found=false;
        while(!nodes.isEmpty()){var node=nodes.remove();if("amazon_fee_estimates".equals(node.path("Relation Name").asText())){found=true;examined+=(node.path("Actual Rows").asLong()+node.path("Rows Removed by Filter").asLong())*node.path("Actual Loops").asLong();}node.path("Plans").forEach(nodes::add);}
        assertThat(found).isTrue();assertThat(examined).isLessThanOrEqualTo(20001);
        System.out.println("FEE_BATCH_CACHE_SYNTHETIC rows=20001 tenants=2 targets=200 hits=1 examined="+examined+" execution_ms="+root.path("Execution Time"));
        System.out.println("FEE_BATCH_CACHE_SYNTHETIC_PLAN "+plan);
    }
    @Test void pilotDispatchRequiresFrozenPendingSliceAndKnownReservationKind(){
        pilot();assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(fee().path("http").asInt()).isZero();assertThat(reason()).isEqualTo("FEE_RUN_STATE_INVALID");
        fixture();pilot();jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"UNKNOWN")).isFalse();assertThat(fee().path("oauth").asInt()).isZero();
        fixture();pilot();jobs.batchNext(job);state("batchPending",List.of("tampered"));assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(fee().path("http").asInt()).isZero();
        fixture();pilot();jobs.batchNext(job);state("itemAttempts",Map.of("unknown",-1));assertThat(jobs.reserve(job,SKU,"FEE")).isFalse();assertThat(fee().path("http").asInt()).isZero();
    }
    @Test void feeOnlyClaimUsesExactApprovedJobAndLeavesOtherQueuedWorkUntouched(){
        pilot();jobs.batchNext(job);String hash=fee().path("runContractHash").asText();
        jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0 WHERE id=?",id);
        UUID unrelated=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status) VALUES (?,?,?,?,'INVENTORY_SNAPSHOT',2,'QUEUED')",unrelated,tenant,run,connection);
        var claimed=jobs.claimPilot(tenant,connection,MARKET,id,hash,"fee-only");assertThat(claimed).isNotNull();assertThat(claimed.id()).isEqualTo(id);assertThat(claimed.leaseOwner()).isEqualTo("fee-only");
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,unrelated)).isEqualTo("QUEUED");assertThat(jobs.matchesPilot(claimed,hash)).isTrue();
    }
    @Test void feeOnlyClaimRejectsWrongScopeHashExpiredGrantAndCancelledRun(){
        var contract=pilot();String hash=jobs.pilotContractHash(contract);
        jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0,started_at=NULL WHERE id=?",id);
        assertThat(jobs.claimPilot(tenant,connection,MARKET,UUID.randomUUID(),hash,"pilot")).isNull();
        assertThat(jobs.claimPilot(UUID.randomUUID(),connection,MARKET,id,hash,"pilot")).isNull();
        assertThat(jobs.claimPilot(tenant,UUID.randomUUID(),MARKET,id,hash,"pilot")).isNull();
        assertThat(jobs.claimPilot(tenant,connection,"OTHER",id,hash,"pilot")).isNull();
        assertThat(jobs.claimPilot(tenant,connection,MARKET,id,"0".repeat(64),"pilot")).isNull();assertThat(status()).isEqualTo("QUEUED");
        contract.put("approvalExpiresAt",Instant.now().minusSeconds(1).toString());pilot(contract);
        assertThat(jobs.claimPilot(tenant,connection,MARKET,id,jobs.pilotContractHash(contract),"pilot")).isNull();assertThat(status()).isEqualTo("QUEUED");
        contract.put("approvalExpiresAt",Instant.now().plusSeconds(600).toString()).put("publicationMode","ARCHIVE_ONLY");pilot(contract);jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=?",run);
        assertThat(jobs.claimPilot(tenant,connection,MARKET,id,jobs.pilotContractHash(contract),"pilot")).isNull();assertThat(status()).isEqualTo("QUEUED");
    }
    @Test void feeOnlyClaimCannotRenewAnExpiredOriginalDeadline(){
        var contract=pilot();jobs.batchNext(job);String hash=jobs.pilotContractHash(contract);
        jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0,started_at=now()-interval '301 seconds' WHERE id=?",id);
        assertThat(jobs.claimPilot(tenant,connection,MARKET,id,hash,"pilot")).isNull();assertThat(status()).isEqualTo("QUEUED");
    }
    @Test void absoluteApprovalExpiryCapsDeadlineAndBlocksLatePublication(){
        var contract=pilot().put("approvalExpiresAt",Instant.now().plusSeconds(30).toString());pilot(contract);jobs.batchNext(job);
        assertThat(Instant.parse(fee().path("deadline").asText())).isEqualTo(Instant.parse(contract.path("approvalExpiresAt").asText()));
        var targets=jobs.batchNext(job);jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?",id);
        jobs.publishBatch(job,targets,new AmazonProductFeeBatch.Result(List.of(),List.of()),response());
        assertThat(count("amazon_source_documents")).isZero();assertThat(count("amazon_fee_estimates")).isZero();
    }
    @Test void absoluteExpiryAfterDispatchBlocksPublicationWithoutAStateRewrite(){
        var contract=pilot().put("approvalExpiresAt",Instant.now().plusSeconds(5).toString());pilot(contract);var targets=jobs.batchNext(job);
        assertThat(targets).hasSize(1);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);
        jdbc.queryForObject("SELECT pg_sleep(greatest(0,extract(epoch FROM CAST(? AS timestamptz)-clock_timestamp()))+0.05)",Object.class,contract.path("approvalExpiresAt").asText());
        jobs.publishBatch(job,targets,new AmazonProductFeeBatch.Result(List.of(),List.of()),response());
        assertThat(reason()).isEqualTo("FEE_DEADLINE");assertThat(fee().path("http").asInt()).isEqualTo(1);
        assertThat(count("amazon_source_documents")).isZero();assertThat(count("amazon_fee_estimates")).isZero();
    }
    @Test void genericQueueCannotClaimControlledFeeJobs(){
        pilot();jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0 WHERE id=?",id);
        assertThat(store.claim("general")).isNull();assertThat(store.claimOrderRefresh("orders")).isNull();assertThat(status()).isEqualTo("QUEUED");
    }
    @Test void controlledJobsNeverDefaultOrWidenPublicationMode(){
        var contract=pilot();contract.remove("publicationMode");pilot(contract);assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_INVALID");assertThat(count("amazon_source_documents")).isZero();
        fixture();contract=pilot().put("publicationMode","ESTIMATES_AND_EVIDENCE");pilot(contract);assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_INVALID");assertThat(count("amazon_fee_estimates")).isZero();
    }
    @Test void archiveOnlyResponseNeverPublishesEstimatesOrComparisonEvidence(){
        pilot();var targets=jobs.batchNext(job);var target=targets.getFirst();var basis=jobs.basis(job,target);
        var raw=(ObjectNode)response().json().path("payload").path("FeesEstimateResult").deepCopy();var identifier=(ObjectNode)raw.path("FeesEstimateIdentifier");
        identifier.put("SellerId",connection.toString()).put("SellerInputIdentifier","archive-only").put("IsAmazonFulfilled",true);identifier.set("PriceToEstimateFees",basis.path("PriceToEstimateFees"));
        for(var fee:raw.path("FeesEstimate").path("FeeDetailList"))((ObjectNode)fee).set("FinalFee",fee.path("FeeAmount").deepCopy());
        var requests=List.of(new AmazonProductFeeBatch.Request(target,basis,"archive-only"));var envelope=json.createArrayNode().add(raw);var verified=AmazonProductFeeBatch.verify(requests,envelope);
        assertThat(verified.accepted()).hasSize(1);var evidence=mock(com.nextaicommerce.platform.fees.AmazonFeeEvidence.class);jobs.configureFeeEvidence(evidence);
        jobs.publishBatch(job,targets,verified,new AmazonSpApiClient.ApiResponse(200,"synthetic",envelope,envelope.toString()));
        assertThat(count("amazon_source_documents")).isOne();assertThat(count("amazon_fee_estimates")).isZero();verifyNoInteractions(evidence);
        assertThat(count("amazon_fee_observations")).isZero();assertThat(count("amazon_fee_comparisons")).isZero();assertThat(count("amazon_fee_approvals")).isZero();
    }
    // Sanitized structural reproduction: one accepted sibling and one unsupported promotion.
    record BatchFixture(List<AmazonProductFeeJobs.Target> targets,AmazonProductFeeBatch.Result verified,AmazonSpApiClient.ApiResponse response) {}
    BatchFixture partialBatch(boolean promotion){
        batchScope();jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,listing_status) VALUES (?,?,?,'SECOND',30,'USD','AFN','ACTIVE')",tenant,connection,MARKET);
        return batchResponse(jobs.batchNext(job),promotion?"SECOND":null,promotion?null:"SECOND");
    }
    BatchFixture batchResponse(List<AmazonProductFeeJobs.Target> targets,String promotionSku,String missingSku){
        var requests=new ArrayList<AmazonProductFeeBatch.Request>();var envelope=json.createArrayNode();
        for(var target:targets){
            var basis=jobs.basis(job,target);String correlation=id+":"+UUID.randomUUID();requests.add(new AmazonProductFeeBatch.Request(target,basis,correlation));
            var raw=(ObjectNode)response().json().path("payload").path("FeesEstimateResult").deepCopy();
            var identifier=(ObjectNode)raw.path("FeesEstimateIdentifier");identifier.put("IdValue",target.sku()).put("SellerId",connection.toString()).put("SellerInputIdentifier",correlation).put("IsAmazonFulfilled",true);identifier.set("PriceToEstimateFees",basis.path("PriceToEstimateFees"));
            ((ObjectNode)raw.path("FeesEstimate")).put("TimeOfFeesEstimation",Instant.now().toString());
            for(var f:raw.path("FeesEstimate").path("FeeDetailList"))((ObjectNode)f).set("FinalFee",f.path("FeeAmount").deepCopy());
            if(target.sku().equals(missingSku))continue; // A missing sibling can retry within its existing budget.
            if(target.sku().equals(promotionSku)){
                for(var f:raw.path("FeesEstimate").path("FeeDetailList"))if("ReferralFee".equals(f.path("FeeType").asText())){
                    ((ObjectNode)f).putObject("FeePromotion").put("CurrencyCode","USD").put("Amount",1);
                    ((ObjectNode)f.path("FinalFee")).put("Amount",2);
                }
                ((ObjectNode)raw.path("FeesEstimate").path("TotalFeesEstimate")).put("Amount",7);
            }
            envelope.add(raw);
        }
        return new BatchFixture(targets,AmazonProductFeeBatch.verify(requests,envelope),new AmazonSpApiClient.ApiResponse(200,"sanitized-partial",envelope,envelope.toString()));
    }
    @Test void partialAcceptedProgressIsDurableBeforeRetryAndCannotCountAgain(){
        var f=partialBatch(false);jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(status()).isEqualTo("WAITING");assertThat(jdbc.queryForObject("SELECT record_count FROM amazon_source_documents",Integer.class)).isEqualTo(1);
        jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(1);
        resumed();assertThat(jobs.batchNext(job)).extracting(AmazonProductFeeJobs.Target::sku).containsExactly("SECOND");
    }
    @Test void deterministicPromotionIsArchivedOnceWithoutAnotherAttempt(){
        var f=partialBatch(true);jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(1);
        resumed();assertThat(jobs.batchNext(job)).isEmpty();
        assertThat(status()).isEqualTo("SKIPPED");assertThat(reason()).isEqualTo("FEE_UNSUPPORTED_RESULTS");
        assertThat(count("amazon_source_documents")).isOne();assertThat(count("amazon_fee_estimates")).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_runs WHERE id=?",String.class,run)).isEqualTo("COMPLETED_WITH_WARNINGS");
    }

    @Test void archiveOnlyPartialResultsRetainDistinctCountsAndUnsupportedSourceWithoutFinancialWrites(){
        var contract=pilot();((tools.jackson.databind.node.ArrayNode)contract.path("skus")).add("SECOND");
        var digest=json.createArrayNode();for(String sku:List.of(SKU,"SECOND"))digest.addArray().add(sku).add("30").add("USD").add("FBA").add("0");
        contract.put("basisSha256",com.nextaicommerce.platform.fees.AmazonFeeEvidence.digest(digest.toString()));pilot(contract);
        var f=partialBatch(true);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(fee().path("acceptedCount").asInt()).isOne();assertThat(fee().path("unsupportedCount").asInt()).isOne();
        assertThat(fee().path("referralComparableCount").asInt()).isOne();assertThat(fee().path("fbaComparableCount").asInt()).isZero();assertThat(fee().path("fbaUnavailableCount").asInt()).isOne();
        assertThat(fee().path("unsupportedResults").get(0).path("sku").asText()).isEqualTo("SECOND");assertThat(fee().path("unsupportedResults").get(0).path("reason").asText()).isEqualTo("UNSUPPORTED_FEE_PROMOTION");
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_source_documents WHERE id=CAST(? AS uuid))",Boolean.class,fee().path("unsupportedResults").get(0).path("sourceDocument").asText())).isTrue();
        resumed();assertThat(jobs.batchNext(job)).isEmpty();assertThat(status()).isEqualTo("SKIPPED");assertThat(fee().path("http").asInt()).isOne();
        assertThat(jdbc.queryForObject("SELECT user_message FROM marketplace_sync_runs WHERE id=?",String.class,run)).contains("1 passed validation; 1 unsupported; 0 unresolved results", "Comparable per-unit FBA fees: 0 of 1", "Estimates unchanged");
        for(String table:List.of("amazon_fee_estimates","amazon_fee_observations","amazon_fee_comparisons","amazon_fee_approvals","amazon_inventory_snapshots","inventory_ledger_entries"))assertThat(count(table)).as(table).isZero();
    }
    @Test void partialPublicationFailureRollsBackArchiveProgressAndRemainingTargets(){
        var f=partialBatch(false);String before=fee().toString();
        jdbc.execute("CREATE FUNCTION "+schema+".reject_partial_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.records_processed>0 AND NEW.status='WAITING' THEN RAISE EXCEPTION 'fixture publication failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_partial_fixture BEFORE UPDATE ON marketplace_sync_jobs FOR EACH ROW EXECUTE FUNCTION "+schema+".reject_partial_fixture()");
        try{
            assertThatThrownBy(()->jobs.publishBatch(job,f.targets(),f.verified(),f.response())).isInstanceOf(RuntimeException.class);
            assertThat(fee().toString()).isEqualTo(before);assertThat(count("amazon_source_documents")).isZero();assertThat(count("amazon_fee_estimates")).isZero();
            assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isZero();
        }finally{jdbc.execute("DROP TRIGGER reject_partial_fixture ON marketplace_sync_jobs");jdbc.execute("DROP FUNCTION "+schema+".reject_partial_fixture()");}
        jobs.publishBatch(job,f.targets(),f.verified(),f.response());assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void staleOrMismatchedBatchCannotDoubleCountAcceptedSiblings(){
        var f=partialBatch(false);jobs.publishBatch(job,f.targets(),f.verified(),f.response());resumed();jobs.batchNext(job);
        jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(reason()).isEqualTo("FEE_RESPONSE_STATE_INVALID");assertThat(count("amazon_source_documents")).isOne();
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void expiryDuringBatchArchiveRollsBackCountsAndEvidence(){
        var f=partialBatch(false);
        jdbc.execute("CREATE FUNCTION "+schema+".expire_batch_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN UPDATE marketplace_sync_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id='"+id+"'; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER expire_batch_fixture BEFORE INSERT ON amazon_source_documents FOR EACH ROW EXECUTE FUNCTION "+schema+".expire_batch_fixture()");
        try{
            assertThatThrownBy(()->jobs.publishBatch(job,f.targets(),f.verified(),f.response())).isInstanceOf(RuntimeException.class);
            assertThat(count("amazon_source_documents")).isZero();assertThat(count("amazon_fee_estimates")).isZero();
            assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isZero();assertThat(fee().path("acceptedCount").asInt()).isZero();
        }finally{jdbc.execute("DROP TRIGGER expire_batch_fixture ON amazon_source_documents");jdbc.execute("DROP FUNCTION "+schema+".expire_batch_fixture()");}
    }
    @Test void unavailablePerUnitFbaKeepsSuccessfulQuotesButCompletesWithWarning(){
        batchPublicationIsFencedAndPreservesExactRawEvidence();resumed();assertThat(jobs.batchNext(job)).isEmpty();
        assertThat(status()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_runs WHERE id=?",String.class,run)).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(jdbc.queryForObject("SELECT user_message FROM marketplace_sync_runs WHERE id=?",String.class,run)).contains("1 passed validation", "Comparable per-unit FBA fees: 0 of 1").doesNotContain("Estimates unchanged");
    }
    @Test void mixedPartialBatchKeepsLaterTargetsAndCountsEachAcceptedSkuExactlyOnce(){
        batchScope();jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,listing_status) SELECT ?,?,?,'REST-'||lpad(n::text,2,'0'),30,'USD','AFN','ACTIVE' FROM generate_series(0,23) n",tenant,connection,MARKET);
        var first=batchResponse(jobs.batchNext(job),"REST-00","REST-01");assertThat(first.targets()).hasSize(20);
        assertThat(jobs.reserve(job,first.targets().getFirst().sku(),"FEE")).isTrue();jobs.received(job);jobs.publishBatch(job,first.targets(),first.verified(),first.response());
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(18);
        assertThat(fee().path("unsupportedCount").asInt()).isOne();assertThat(fee().path("batchManifest")).hasSize(6);
        resumed();state("nextAt",Instant.now().minusSeconds(1).toString());var rest=jobs.batchNext(job);
        assertThat(rest).extracting(AmazonProductFeeJobs.Target::sku).containsExactly("REST-01","REST-19","REST-20","REST-21","REST-22","REST-23");
        assertThat(jobs.reserve(job,rest.getFirst().sku(),"FEE")).isTrue();jobs.received(job);
        var second=batchResponse(rest,null,null);jobs.publishBatch(job,rest,second.verified(),second.response());resumed();assertThat(jobs.batchNext(job)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(24);
        assertThat(count("amazon_fee_estimates")).isEqualTo(24);assertThat(count("amazon_source_documents")).isEqualTo(2);assertThat(fee().path("acceptedCount").asInt()).isEqualTo(24);assertThat(fee().path("unsupportedCount").asInt()).isOne();assertThat(fee().path("http").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT user_message FROM marketplace_sync_runs WHERE id=?",String.class,run)).contains("24 passed validation; 1 unsupported; 0 unresolved results");
    }
    @Test void exhaustedRetryReportsAcceptedUnsupportedAndMissingSeparately(){
        var f=partialBatch(false);state("skuAttempts",3);jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(reason()).isEqualTo("FEE_RETRY_EXHAUSTED");assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_message FROM marketplace_sync_runs WHERE id=?",String.class,run)).contains("1 passed validation; 0 unsupported; 1 unresolved results");
    }
    @Test void feeOnlyStatusReaderPreservesAuthoritativeTerminalAndActiveStates(){
        var reader=new com.nextaicommerce.platform.web.WorkspaceAccessRepository(jdbc);
        for(String state:List.of("QUEUED","RUNNING","COMPLETED","COMPLETED_WITH_WARNINGS","FAILED","CANCELLED")){
            jdbc.update("UPDATE marketplace_sync_runs SET status=? WHERE id=?",state,run);
            assertThat(reader.listCurrentSyncStatuses(tenant)).singleElement().satisfies(view->assertThat(view.status()).as(state).isEqualTo(state));
        }
        assertThat(reader.listCurrentSyncStatuses(UUID.randomUUID())).isEmpty();
    }
    @Test void mixedInitializationStatusProjectionRemainsUnchanged(){
        var reader=new com.nextaicommerce.platform.web.WorkspaceAccessRepository(jdbc);
        jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status,required_for_ready) VALUES (?,?,?,'INVENTORY_SNAPSHOT',2,'COMPLETED',true)",tenant,run,connection);
        assertThat(reader.listCurrentSyncStatuses(tenant)).singleElement().satisfies(view->assertThat(view.status()).isEqualTo("ENRICHING"));
        jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status,required_for_ready) VALUES (?,?,?,'FINAL_RECONCILIATION',3,'COMPLETED',true)",tenant,run,connection);
        assertThat(reader.listCurrentSyncStatuses(tenant)).singleElement().satisfies(view->{assertThat(view.status()).isEqualTo("COMPLETED");assertThat(view.message()).isEqualTo("Amazon data is ready");});
    }
    ObjectNode publicationContract(boolean second){
        var c=pilot().put("format","FEE_RUN_V2").put("publicationMode","ESTIMATES_AND_EVIDENCE").put("maxHttp",1);
        if(second){((tools.jackson.databind.node.ArrayNode)c.path("skus")).add("SECOND");var digest=json.createArrayNode();for(String sku:List.of(SKU,"SECOND"))digest.addArray().add(sku).add("30").add("USD").add("FBA").add("0");c.put("basisSha256",com.nextaicommerce.platform.fees.AmazonFeeEvidence.digest(digest.toString()));}
        pilot(c);return c;
    }
    void restrictedPublisher(boolean enabled){
        var original=jdbc.getDataSource();var scoped=new org.springframework.jdbc.datasource.AbstractDataSource(){
            private java.sql.Connection scoped(java.sql.Connection c)throws java.sql.SQLException{try(var statement=c.createStatement()){statement.execute("SET ROLE "+publicationRole);}return c;}
            public java.sql.Connection getConnection()throws java.sql.SQLException{return scoped(original.getConnection());}
            public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{return scoped(original.getConnection(user,password));}
        };
        var db=new JdbcTemplate(scoped);var transaction=new TransactionTemplate(new DataSourceTransactionManager(scoped));jobs=new AmazonProductFeeJobs(db,transaction,json,new AmazonSyncStore(db,transaction),0.5);
        org.springframework.test.util.ReflectionTestUtils.setField(jobs,"publicationMode","ESTIMATES_AND_EVIDENCE");jobs.configureFeeEvidence(new com.nextaicommerce.platform.fees.AmazonFeeEvidence(db,json,enabled));
    }
    BatchFixture withComparableFba(BatchFixture f){
        for(var accepted:f.verified().accepted())for(var node:accepted.result().path("FeesEstimate").path("FeeDetailList"))if("FBAFees".equals(node.path("FeeType").asText())){
            var child=((ObjectNode)node).putArray("IncludedFeeDetailList").addObject().put("FeeType","FBAPerUnitFulfillmentFee");child.set("FeeAmount",node.path("FeeAmount").deepCopy());child.set("FinalFee",node.path("FinalFee").deepCopy());
        }
        var requests=new ArrayList<AmazonProductFeeBatch.Request>();f.verified().accepted().forEach(a->requests.add(a.request()));requests.addAll(f.verified().unresolved());
        var body=f.response().json();return new BatchFixture(f.targets(),AmazonProductFeeBatch.verify(requests,body),new AmazonSpApiClient.ApiResponse(200,"synthetic-publication",body,body.toString()));
    }
    @Test void controlledPublicationWritesOnlySupportedNewQuotesUnderNonBypassRole(){
        publicationContract(true);restrictedPublisher(true);var f=withComparableFba(partialBatch(true));
        var before=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);jobs.publishBatch(job,f.targets(),f.verified(),f.response());
        assertThat(count("amazon_fee_estimates")).isOne();assertThat(count("amazon_fee_observations")).isEqualTo(2);assertThat(count("amazon_source_documents")).isOne();
        for(String sequence:List.of("amazon_order_fee_charge_observations_observation_seq_seq","amazon_fee_comparisons_revision_seq_seq"))assertThat(jdbc.queryForObject("SELECT has_sequence_privilege(?, ?, 'USAGE')",Boolean.class,publicationRole,schema+"."+sequence)).as("unrelated sequence "+sequence).isFalse();
        var scopedDb=(JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(jobs,"jdbc");var scopedTx=(TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(jobs,"tx");
        scopedTx.executeWithoutResult(x->{scopedDb.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(scopedDb.queryForObject("SELECT count(*) FROM amazon_fee_observations",Integer.class)).isZero();});
        scopedTx.executeWithoutResult(x->{scopedDb.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());assertThat(scopedDb.queryForObject("SELECT count(*) FROM amazon_fee_observations WHERE marketplace_connection_id=?",Integer.class,UUID.randomUUID())).isZero();});
        assertThat(jdbc.queryForObject("SELECT min(boundary_at)>=? FROM amazon_fee_observations",Boolean.class,before)).isTrue();
        for(String table:List.of("amazon_order_fee_charge_observations","amazon_fee_comparisons","amazon_fee_approvals","amazon_inventory_snapshots","inventory_ledger_entries"))assertThat(count(table)).as(table).isZero();
        resumed();assertThat(jobs.batchNext(job)).isEmpty();assertThat(jdbc.queryForObject("SELECT user_message FROM marketplace_sync_runs WHERE id=?",String.class,run)).contains("Saved fee quotes: 1 passed validation; 1 unsupported").doesNotContain("Estimates unchanged");
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(?, 'amazon_fee_approvals', 'INSERT') OR has_table_privilege(?, 'amazon_fee_comparisons', 'INSERT') OR has_table_privilege(?, 'amazon_order_fee_charge_observations', 'INSERT')",Boolean.class,publicationRole,publicationRole,publicationRole)).isFalse();
    }
    @Test void disabledEvidenceMissingRightsAndPrivilegedRuntimeCannotDispatchPublication(){
        publicationContract(false);restrictedPublisher(false);assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_PUBLICATION_NOT_READY");assertThat(count("amazon_source_documents")).isZero();
        fixture();publicationContract(false);restrictedPublisher(true);jdbc.execute("REVOKE INSERT ON amazon_fee_observations FROM "+publicationRole);
        try{assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_PUBLICATION_NOT_READY");}finally{jdbc.execute("GRANT INSERT ON amazon_fee_observations TO "+publicationRole);}
        fixture();publicationContract(false);org.springframework.test.util.ReflectionTestUtils.setField(jobs,"publicationMode","ESTIMATES_AND_EVIDENCE");jobs.configureFeeEvidence(new com.nextaicommerce.platform.fees.AmazonFeeEvidence(jdbc,json,true));
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_PUBLICATION_NOT_READY");
    }
    @Test void publicationCannotWidenV1OrUseWrongModeOrMoreThanOneBatch(){
        for(String bad:List.of("v1","mode","targets","http","seconds")){
            fixture();var c=publicationContract(false);restrictedPublisher(true);
            switch(bad){case "v1"->c.put("format","FEE_RUN_V1");case "mode"->c.put("publicationMode","ARCHIVE_ONLY");case "targets"->c.put("maxTargets",21);case "http"->c.put("maxHttp",2);case "seconds"->c.put("maxSeconds",301);}
            pilot(c);assertThat(jobs.batchNext(job)).as(bad).isEmpty();assertThat(reason()).as(bad).isEqualTo("FEE_RUN_INVALID");assertThat(count("amazon_source_documents")).isZero();
        }
    }
    @Test void observationFailureRollsBackArchiveEstimateProgressAndManifest(){
        publicationContract(true);restrictedPublisher(true);var f=withComparableFba(partialBatch(true));assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);var before=fee().toString();
        jdbc.execute("CREATE FUNCTION "+schema+".reject_quote_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture observation failure'; END $$");jdbc.execute("CREATE TRIGGER reject_quote_fixture BEFORE INSERT ON amazon_fee_observations FOR EACH ROW EXECUTE FUNCTION "+schema+".reject_quote_fixture()");
        try{assertThatThrownBy(()->jobs.publishBatch(job,f.targets(),f.verified(),f.response())).isInstanceOf(RuntimeException.class);assertThat(fee().toString()).isEqualTo(before);for(String table:List.of("amazon_source_documents","amazon_fee_estimates","amazon_fee_observations"))assertThat(count(table)).as(table).isZero();assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isZero();}
        finally{jdbc.execute("DROP TRIGGER reject_quote_fixture ON amazon_fee_observations");jdbc.execute("DROP FUNCTION "+schema+".reject_quote_fixture()");}
    }
    @Test void controlledPublicationBypassesCompatibleCacheWithoutBackdatingItsBoundary(){
        publicationContract(false);var basis=json.createObjectNode().put("MarketplaceId",MARKET).put("IsAmazonFulfilled",true).put("_platformSellerId",connection.toString()).put("_platformShippingApproved",true);
        var prices=basis.putObject("PriceToEstimateFees");prices.putObject("ListingPrice").put("CurrencyCode","USD").put("Amount",30);prices.putObject("Shipping").put("CurrencyCode","USD").put("Amount",0);
        var raw=(ObjectNode)response().json().path("payload").path("FeesEstimateResult").deepCopy();((ObjectNode)raw.path("FeesEstimateIdentifier")).put("SellerId",connection.toString());raw.set("_platformQuoteBasis",basis);raw.put("_platformFeeContract",AmazonProductFeeBatch.CONTRACT);
        UUID source=UUID.randomUUID();jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,source_key,sha256,payload,received_at) VALUES (?,?,?,'FEES_SNAPSHOT','old-cache',?,'{}',now()-interval '1 hour')",source,tenant,connection,source.toString());
        jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,estimated_fee_total,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES (?,?,?,?,'USD',8,3,5,now()-interval '1 hour',CAST(? AS jsonb),?)",tenant,connection,MARKET,SKU,raw.toString(),source);
        var prior=jdbc.queryForMap("SELECT * FROM amazon_fee_estimates");var candidate=json.createArrayNode();candidate.addObject().put("sku",SKU).put("currency","USD").set("basis",basis);
        assertThat(jdbc.queryForList(AmazonProductFeeJobs.BATCH_CACHE_SQL,String.class,candidate.toString(),tenant,connection,MARKET,java.sql.Timestamp.from(Instant.now().minusSeconds(86400)),java.sql.Timestamp.from(Instant.now()),AmazonProductFeeBatch.CONTRACT)).containsExactly(SKU);
        restrictedPublisher(true);var targets=jobs.batchNext(job);assertThat(targets).hasSize(1);assertThat(fee().path("cacheHits").asInt()).isZero();var f=withComparableFba(batchResponse(targets,null,null));
        var before=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);jobs.publishBatch(job,targets,f.verified(),f.response());
        assertThat(count("amazon_fee_estimates")).isEqualTo(2);assertThat(count("amazon_fee_observations")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT min(boundary_at)>=? FROM amazon_fee_observations",Boolean.class,before)).isTrue();assertThat(jdbc.queryForMap("SELECT * FROM amazon_fee_estimates WHERE source_document_id=?",source)).isEqualTo(prior);
    }
    @Test void publicationHasAnAbsoluteTwentyEstimateFortyObservationCeiling(){
        var c=publicationContract(false);var names=(tools.jackson.databind.node.ArrayNode)c.path("skus");var digest=json.createArrayNode();digest.addArray().add(SKU).add("30").add("USD").add("FBA").add("0");
        for(int n=1;n<=19;n++){String sku="TWENTY-"+String.format("%02d",n);names.add(sku);digest.addArray().add(sku).add("30").add("USD").add("FBA").add("0");jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,listing_status) VALUES (?,?,?,?,30,'USD','AFN','ACTIVE')",tenant,connection,MARKET,sku);}
        c.put("basisSha256",com.nextaicommerce.platform.fees.AmazonFeeEvidence.digest(digest.toString()));pilot(c);restrictedPublisher(true);var targets=jobs.batchNext(job);var f=withComparableFba(batchResponse(targets,null,null));assertThat(targets).hasSize(20);
        assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);jobs.publishBatch(job,targets,f.verified(),f.response());jobs.publishBatch(job,targets,f.verified(),f.response());
        assertThat(count("amazon_fee_estimates")).isEqualTo(20);assertThat(count("amazon_fee_observations")).isEqualTo(40);assertThat(count("amazon_source_documents")).isOne();assertThat(jdbc.queryForObject("SELECT records_processed FROM marketplace_sync_jobs WHERE id=?",Long.class,id)).isEqualTo(20);
        resumed();assertThat(jobs.batchNext(job)).isEmpty();assertThat(status()).isEqualTo("COMPLETED");
    }
    @Test void amazonEstimationTimeCannotBackdateNewResponseReceiptBoundary(){
        publicationContract(false);restrictedPublisher(true);var targets=jobs.batchNext(job);var f=withComparableFba(batchResponse(targets,null,null));
        ((ObjectNode)f.verified().accepted().getFirst().result().path("FeesEstimate")).put("TimeOfFeesEstimation","2000-01-01T00:00:00Z");
        var before=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);
        jobs.publishBatch(job,targets,f.verified(),new AmazonSpApiClient.ApiResponse(200,"synthetic-time",f.response().json(),f.response().json().toString()));
        assertThat(count("amazon_fee_estimates")).isOne();assertThat(count("amazon_fee_observations")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT min(boundary_at)>=? AND min(observed_at)>=? FROM amazon_fee_observations",Boolean.class,before,before)).isTrue();
        assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents",String.class)).contains("2000-01-01T00:00:00Z");
    }
    @Test void publicationRequiresItsOwnDurableRequestAndCannotReplayAnotherJobArchive(){
        publicationContract(false);restrictedPublisher(true);var targets=jobs.batchNext(job);var f=withComparableFba(batchResponse(targets,null,null));jobs.publishBatch(job,targets,f.verified(),f.response());
        assertThat(reason()).isEqualTo("FEE_PUBLICATION_RESPONSE_INVALID");assertThat(count("amazon_fee_estimates")).isZero();assertThat(count("amazon_fee_observations")).isZero();
        fixture();publicationContract(false);restrictedPublisher(true);targets=jobs.batchNext(job);f=withComparableFba(batchResponse(targets,null,null));var accepted=f.verified().accepted().getFirst();String old=UUID.randomUUID()+":"+UUID.randomUUID();((ObjectNode)accepted.result().path("FeesEstimateIdentifier")).put("SellerInputIdentifier",old);
        var request=new AmazonProductFeeBatch.Request(accepted.request().target(),accepted.request().basis(),old);var checked=AmazonProductFeeBatch.verify(List.of(request),f.response().json());
        assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);jobs.publishBatch(job,targets,checked,new AmazonSpApiClient.ApiResponse(200,"synthetic-replay",f.response().json(),f.response().json().toString()));
        assertThat(reason()).isEqualTo("FEE_PUBLICATION_RESPONSE_INVALID");assertThat(count("amazon_fee_estimates")).isZero();assertThat(count("amazon_fee_observations")).isZero();
    }
    @Test void publicationClaimRequiresTheSameExplicitConfiguredModeAndFrozenContract(){
        var c=publicationContract(false);restrictedPublisher(true);jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0 WHERE id=?",id);String hash=jobs.pilotContractHash(c);
        assertThat(jobs.claimPilot(tenant,connection,MARKET,id,hash,"publication")).isNull();
        var claimed=jobs.claimPilot(tenant,connection,MARKET,id,hash,"publication","ESTIMATES_AND_EVIDENCE");assertThat(claimed).isNotNull();assertThat(jobs.matchesPilot(claimed,hash)).isTrue();assertThat(jobs.batchNext(claimed)).hasSize(1);
        pilot(c.put("publicationMode","ARCHIVE_ONLY"));assertThat(jobs.batchNext(claimed)).isEmpty();assertThat(reason()).isEqualTo("FEE_RUN_INVALID");assertThat(count("amazon_source_documents")).isZero();
    }
    @Test void unrelatedDmlOrInheritedMembershipBlocksPublicationBeforeAnyRequest(){
        publicationContract(false);restrictedPublisher(true);jdbc.execute("GRANT UPDATE(quantity_ordered) ON amazon_order_items TO "+publicationRole);
        try{assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_PUBLICATION_NOT_READY");assertThat(count("amazon_source_documents")).isZero();}finally{jdbc.execute("REVOKE UPDATE(quantity_ordered) ON amazon_order_items FROM "+publicationRole);}
        fixture();publicationContract(false);restrictedPublisher(true);String broader=publicationRole+"_group";jdbc.execute("CREATE ROLE "+broader+" NOLOGIN");jdbc.execute("GRANT "+broader+" TO "+publicationRole);
        try{assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_PUBLICATION_NOT_READY");assertThat(count("amazon_source_documents")).isZero();}finally{jdbc.execute("REVOKE "+broader+" FROM "+publicationRole);jdbc.execute("DROP ROLE "+broader);}
    }
    AmazonFeeOperator.Grant operatorGrant(ObjectNode contract){
        var identity=jdbc.queryForMap("SELECT current_database() db,inet_server_addr()::text address,inet_server_port() port,(SELECT oid::bigint FROM pg_database WHERE datname=current_database()) oid");
        var original=java.net.URI.create(((DriverManagerDataSource)jdbc.getDataSource()).getUrl().substring(5));
        String url="jdbc:postgresql://"+original.getHost()+":"+original.getPort()+original.getPath()+"?currentSchema="+schema;
        return new AmazonFeeOperator.Grant("FEE_OPERATOR_V1",url,identity.get("db").toString(),schema,identity.get("address").toString(),((Number)identity.get("port")).intValue(),((Number)identity.get("oid")).longValue(),publicationRole,tenant,connection,MARKET,id,jobs.pilotContractHash(contract),contract.path("publicationMode").asText(),Instant.parse(contract.path("approvalExpiresAt").asText()),false);
    }
    void queueOperator(){jdbc.update("UPDATE marketplace_sync_jobs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,attempt_count=0 WHERE id=?",id);}
    AmazonFeeOperator.Result operate(AmazonFeeOperator.Grant grant,AmazonProductFeeSync sync)throws Exception{
        var db=(JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(jobs,"jdbc");var transaction=(TransactionTemplate)org.springframework.test.util.ReflectionTestUtils.getField(jobs,"tx");
        return AmazonFeeOperator.execute(grant,db,transaction,jobs,sync,()->{jdbc.update("UPDATE marketplace_sync_jobs SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",id);});
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ARCHIVE_ONLY","ESTIMATES_AND_EVIDENCE"})
    void foregroundOperatorUsesActualRestrictedClaimAndSyncForBothModes(String mode)throws Exception{
        var c=publicationContract(false).put("publicationMode",mode);pilot(c);restrictedPublisher(true);org.springframework.test.util.ReflectionTestUtils.setField(jobs,"publicationMode",mode);var g=operatorGrant(c);queueOperator();
        var api=mock(AmazonSpApiClient.class);
        when(api.quoteProductFees(eq(tenant),eq(connection),anyString(),any())).thenAnswer(call->{
            var permit=(AmazonSpApiClient.FeePermit)call.getArgument(3);assertThat(permit.getAsBoolean()).isTrue();permit.completed();
            var request=json.readTree((String)call.getArgument(2)).get(0).path("FeesEstimateRequest");
            var item=(ObjectNode)response().json().path("payload").path("FeesEstimateResult").deepCopy();
            var identifier=(ObjectNode)item.path("FeesEstimateIdentifier");identifier.put("SellerId",connection.toString()).put("IsAmazonFulfilled",true).put("SellerInputIdentifier",request.path("Identifier").asText());identifier.set("PriceToEstimateFees",request.path("PriceToEstimateFees"));
            for(var f:item.path("FeesEstimate").path("FeeDetailList")){((ObjectNode)f).set("FinalFee",f.path("FeeAmount").deepCopy());if("FBAFees".equals(f.path("FeeType").asText())){
                var child=((ObjectNode)f).putArray("IncludedFeeDetailList").addObject().put("FeeType","FBAPerUnitFulfillmentFee");child.set("FeeAmount",f.path("FeeAmount").deepCopy());child.set("FinalFee",f.path("FinalFee").deepCopy());}}
            var envelope=json.createArrayNode().add(item);return new AmazonSpApiClient.ApiResponse(200,"synthetic-operator",envelope,envelope.toString());
        });
        var sync=new AmazonProductFeeSync(api,jobs,true);org.springframework.test.util.ReflectionTestUtils.setField(sync,"batchEnabled",true);
        assertThat(operate(g,sync)).as("reason=%s",reason()).isEqualTo(AmazonFeeOperator.Result.COMPLETED);verify(api,times(1)).quoteProductFees(eq(tenant),eq(connection),anyString(),any());
        assertThat(count("amazon_fee_estimates")).isEqualTo(mode.equals("ARCHIVE_ONLY")?0:1);assertThat(count("amazon_fee_observations")).isEqualTo(mode.equals("ARCHIVE_ONLY")?0:2);assertThat(count("amazon_inventory_snapshots")).isZero();
        assertThat(count("amazon_orders")).isZero();assertThat(count("amazon_fee_comparisons")).isZero();
    }
    @Test void foregroundOperatorMissingScopeHashModeJobAndCancellationNeverDispatch()throws Exception{
        var c=publicationContract(false);restrictedPublisher(true);var g=operatorGrant(c);queueOperator();var sync=mock(AmazonProductFeeSync.class);
        for(String key:List.of("tenant","connection","marketplace","job","contractSha256","publicationMode")){
            var value=(ObjectNode)json.valueToTree(g);value.put(key,switch(key){case "marketplace"->"OTHER";case "contractSha256"->"0".repeat(64);case "publicationMode"->"ARCHIVE_ONLY";default->UUID.randomUUID().toString();});
            assertThat(operate(json.treeToValue(value,AmazonFeeOperator.Grant.class),sync)).isEqualTo(AmazonFeeOperator.Result.BLOCKED);
        }
        jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=?",run);assertThat(operate(g,sync)).isEqualTo(AmazonFeeOperator.Result.CANCELLED);verifyNoInteractions(sync);
    }
    @Test void foregroundOperatorExpiryAndUncertainRequestCannotRestartBudgets()throws Exception{
        var c=publicationContract(false);restrictedPublisher(true);var g=operatorGrant(c);queueOperator();var sync=mock(AmazonProductFeeSync.class);
        jdbc.update("UPDATE marketplace_sync_jobs SET started_at=now()-interval '6 minutes' WHERE id=?",id);assertThat(operate(g,sync)).isEqualTo(AmazonFeeOperator.Result.EXPIRED);verifyNoInteractions(sync);
        fixture();c=publicationContract(false);restrictedPublisher(true);g=operatorGrant(c);jobs.batchNext(job);assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();
        jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?",id);
        var api=mock(AmazonSpApiClient.class);var actual=new AmazonProductFeeSync(api,jobs,true);org.springframework.test.util.ReflectionTestUtils.setField(actual,"batchEnabled",true);
        assertThat(operate(g,actual)).isEqualTo(AmazonFeeOperator.Result.COMPLETED_WITH_WARNINGS);assertThat(reason()).isEqualTo("FEE_REQUEST_OUTCOME_UNKNOWN");verifyNoInteractions(api);assertThat(fee().path("http").asInt()).isOne();
    }
    @Test void operatorDatabaseIdentityUsesARealRestrictedLoginNotAnAdminRoleSwitch()throws Exception{
        var c=publicationContract(false);var g=operatorGrant(c);String password="synthetic-"+UUID.randomUUID();jdbc.execute("ALTER ROLE "+publicationRole+" LOGIN PASSWORD '"+password+"'");
        try{var actual=new JdbcTemplate(new DriverManagerDataSource(g.jdbcUrl(),publicationRole,password));AmazonFeeOperator.verifyDatabase(actual,g);
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(actual.getDataSource()));
            transaction.executeWithoutResult(s->{actual.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(actual.queryForObject("SELECT count(*) FROM amazon_listings",Integer.class)).isZero();});
            var jsonGrant=(ObjectNode)json.valueToTree(g);jsonGrant.put("databaseOid",g.databaseOid()+1);assertThatThrownBy(()->AmazonFeeOperator.verifyDatabase(actual,json.treeToValue(jsonGrant,AmazonFeeOperator.Grant.class))).isInstanceOf(IllegalStateException.class);
        }finally{jdbc.execute("ALTER ROLE "+publicationRole+" NOLOGIN");}
    }
    @Test void batchShippingScopeMustBeExplicit(){assertThat(jobs.batchNext(job)).isEmpty();assertThat(reason()).isEqualTo("FEE_SHIPPING_BASIS_REQUIRES_APPROVAL");}
    @Test void batchFreezesExactTargetsAndDoesNotFollowLaterPrice(){batchScope();var targets=jobs.batchNext(job);assertThat(targets).hasSize(1);jdbc.update("UPDATE amazon_listings SET price=77 WHERE tenant_id=?",tenant);assertThat(jobs.batchNext(job).getFirst().price()).isEqualByComparingTo(targets.getFirst().price());}
    @Test void legacyQuotesDoNotBypassTheNewFinalFeeCacheContract(){batchScope();publish();resumed();assertThat(jobs.batchNext(job)).hasSize(1);assertThat(fee().path("cacheHits").asInt()).isZero();assertThat(fee().path("http").asInt()).isZero();}
    @Test void completeBatchCacheRetainsReceiptTimeAndExactBasis(){
        batchPublicationIsFencedAndPreservesExactRawEvidence();var before=jdbc.queryForObject("SELECT effective_at FROM amazon_fee_estimates",java.sql.Timestamp.class);
        resumed();jdbc.update("UPDATE marketplace_sync_jobs SET cursor=jsonb_set(cursor,'{productFees}',(cursor->'productFees')-'batchManifest'-'batchIndex') WHERE id=?",id);
        assertThat(jobs.batchNext(job)).isEmpty();assertThat(fee().path("cacheHits").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT effective_at FROM amazon_fee_estimates",java.sql.Timestamp.class)).isEqualTo(before);
        assertThat(count("amazon_fee_estimates")).isOne();assertThat(fee().path("http").asInt()).isZero();
    }
    @Test void perItemBatchAttemptsCannotResetWhenFirstSkuChanges(){batchScope();assertThat(jobs.batchNext(job)).hasSize(1);for(int n=0;n<3;n++){state("nextAt",Instant.now().minusSeconds(1).toString());assertThat(jobs.reserve(job,SKU,"FEE")).isTrue();jobs.received(job);}state("attemptSku","different-first");state("skuAttempts",0);state("nextAt",Instant.now().minusSeconds(1).toString());assertThat(jobs.reserve(job,"different-first","FEE")).isFalse();assertThat(reason()).isEqualTo("FEE_ITEM_RETRY_EXHAUSTED");}

    @Test void blankCurrencyFallbackIsExplicitAndNeverRewritesListing(){batchScope();jdbc.update("UPDATE amazon_listings SET currency=NULL WHERE tenant_id=?",tenant);org.springframework.test.util.ReflectionTestUtils.setField(jobs,"currencyFallback",true);var targets=jobs.batchNext(job);assertThat(targets).hasSize(1);assertThat(targets.getFirst().currency()).isEqualTo("USD");assertThat(jdbc.queryForObject("SELECT currency FROM amazon_listings WHERE tenant_id=?",String.class,tenant)).isNull();}
    @Test void batchPublicationIsFencedAndPreservesExactRawEvidence(){batchScope();var targets=jobs.batchNext(job);var target=targets.getFirst();var basis=jobs.basis(job,target);var raw=(ObjectNode)response().json().path("payload").path("FeesEstimateResult").deepCopy();var identifier=(ObjectNode)raw.path("FeesEstimateIdentifier");identifier.put("SellerId",connection.toString()).put("SellerInputIdentifier","batch-id").put("IsAmazonFulfilled",true);identifier.set("PriceToEstimateFees",basis.path("PriceToEstimateFees"));for(var fee:raw.path("FeesEstimate").path("FeeDetailList"))((ObjectNode)fee).set("FinalFee",fee.path("FeeAmount").deepCopy());var requests=List.of(new AmazonProductFeeBatch.Request(target,basis,"batch-id"));var envelope=json.createArrayNode().add(raw);var accepted=AmazonProductFeeBatch.verify(requests,envelope);assertThat(accepted.accepted()).hasSize(1);var response=new AmazonSpApiClient.ApiResponse(200,"batch-request",envelope,envelope.toString());jobs.publishBatch(job,targets,accepted,response);assertThat(count("amazon_fee_estimates")).isOne();assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents",String.class)).isEqualTo(response.body());assertThat(fee().path("batchIndex").asInt()).isEqualTo(1);assertThat(status()).isEqualTo("WAITING");assertThat(count("amazon_inventory_snapshots")).isZero();}

}
