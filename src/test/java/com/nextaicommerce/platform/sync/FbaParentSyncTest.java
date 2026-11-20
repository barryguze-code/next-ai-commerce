package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.fba.FbaShipmentParents;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FbaParentSyncTest {
 static EmbeddedPostgres postgres;static JdbcTemplate jdbc;static TransactionTemplate tx;
 final ObjectMapper json=new ObjectMapper();final String plan="wf1234abcd-1234-abcd-5678-1234abcd5678",child="sh1234abcd-1234-abcd-5678-1234abcd5678";
 UUID tenant,connection,run,id;AmazonSyncStore.Job job;AmazonSpApiClient amazon;FbaInboundSync sync;AmazonSyncStore store;
 @BeforeAll static void database()throws Exception{postgres=EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();var ds=postgres.getPostgresDatabase();Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));}
 @AfterAll static void close()throws Exception{if(postgres!=null)postgres.close();}
 @BeforeEach void setup(){tenant=UUID.randomUUID();connection=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Parent read')",tenant,tenant.toString());jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','ACTIVE','Test','America/Los_Angeles',now())",connection,tenant);run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end) VALUES (?,?,'RECONCILIATION','FBA_MANUAL',now()-interval '1 day',now()) RETURNING id",UUID.class,tenant,connection);id=jdbc.queryForObject("INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,required_for_ready,cursor) VALUES (?,?,?,'FBA_INBOUND',1,false,'{\"shipmentsComplete\":true,\"feesComplete\":false,\"pages\":1199}'::jsonb) RETURNING id",UUID.class,tenant,connection,run);job=new AmazonSyncStore.Job(id,tenant,run,connection,"FBA_INBOUND","FBA_MANUAL","ATVPDKIKX0DER",Instant.now().minusSeconds(86400),Instant.now(),null,0,0,false,null);amazon=mock(AmazonSpApiClient.class);store=spy(new AmazonSyncStore(jdbc,tx));sync=new FbaInboundSync(amazon,store,jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null));}
 AmazonSpApiClient.ApiResponse response(String body,String request){return new AmazonSpApiClient.ApiResponse(200,request,json.readTree(body),body,2.0);}
 void reply(String body,String request){when(amazon.getAt(eq(tenant),eq(connection),eq("https://sellingpartnerapi-na.amazon.com"),anyString())).thenReturn(response(body,request));sync.processPage(job);}
 String listing(){return "{\"inboundPlans\":[{\"inboundPlanId\":\""+plan+"\",\"marketplaceIds\":[\"ATVPDKIKX0DER\"],\"name\":\"Test\"}]}";}
 String detail(){return "{\"inboundPlanId\":\""+plan+"\",\"marketplaceIds\":[\"ATVPDKIKX0DER\"],\"shipments\":[{\"shipmentId\":\""+child+"\"}]}";}
 String cursor(){return jdbc.queryForObject("SELECT cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",String.class,tenant,id);}
 long documents(){return jdbc.queryForObject("SELECT count(*) FROM amazon_source_documents WHERE tenant_id=? AND marketplace_connection_id=? AND source_type='FBA_PARENT_RELATIONSHIPS'",Long.class,tenant,connection);}
 @Test void standardJobPublishesOnlyCompleteScopedEvidenceAndLeavesShipmentsUntouched(){reply(listing(),"list");reply(detail(),"plan");assertThat(documents()).isZero();reply("{\"shipmentId\":\""+child+"\",\"shipmentConfirmationId\":\"FBA123456789\"}","child");assertThat(documents()).isEqualTo(1);var projection=new FbaShipmentParents(jdbc,json).read(tenant,connection,List.of("FBA123456789"));assertThat(projection.links().get("FBA123456789").id()).isEqualTo(plan);assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fba_shipments WHERE tenant_id=?",Long.class,tenant)).isZero();assertThat(json.readTree(cursor()).path("pages").asInt()).isEqualTo(1199);verify(amazon,never()).post(any(),any(),any(),any());verify(amazon,never()).put(any(),any(),any(),any());}
 @Test void throttleLeavesExactCheckpointForResumption(){reply(listing(),"list");String before=cursor();reset(amazon);when(amazon.getAt(any(),any(),any(),any())).thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(60),"rate"));assertThatThrownBy(()->sync.processPage(job)).isInstanceOf(AmazonSpApiClient.AmazonApiException.class);assertThat(cursor()).isEqualTo(before);assertThat(documents()).isZero();}
 @Test void unavailableParentReadIsDistinctFromCompleteCoverageAndPreservesLegacyFlow(){when(amazon.getAt(any(),any(),any(),any())).thenThrow(new AmazonSpApiClient.AmazonApiException(403,null,"denied"));sync.processPage(job);assertThat(cursor()).contains("UNAVAILABLE","AUTHORIZATION_DENIED","403","parentsComplete");assertThat(documents()).isZero();assertThat(json.readTree(cursor()).path("feesComplete").asBoolean()).isFalse();}
 @Test void childMismatchCannotPublishOrAdvanceCheckpoint(){reply(listing(),"list");reply(detail(),"plan");String before=cursor();when(amazon.getAt(any(),any(),any(),any())).thenReturn(response("{\"shipmentId\":\"sh2234abcd-1234-abcd-5678-1234abcd5678\"}","wrong"));assertThatThrownBy(()->sync.processPage(job)).isInstanceOf(IllegalStateException.class);assertThat(cursor()).isEqualTo(before);assertThat(documents()).isZero();}
 @Test void dailyFbaRefreshesParentsWhileInitializationDoesNot(){
  for(String profile:List.of("FBA_INBOUND","INITIALIZATION")){
   var automatic=new AmazonSyncStore.Job(id,tenant,run,connection,"FBA_INBOUND",profile,job.marketplaceId(),job.windowStart(),job.windowEnd(),null,0,0,false,null);
   when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"transactions\":[]}}","fees"));
   when(amazon.getAt(any(),any(),any(),any())).thenReturn(response("{\"inboundPlans\":[]}","parents"));
   sync.processPage(automatic);
   if("FBA_INBOUND".equals(profile))verify(amazon).getAt(any(),any(),any(),any());else verify(amazon,never()).getAt(any(),any(),any(),any());
   reset(amazon);
   jdbc.update("UPDATE marketplace_sync_jobs SET cursor='{\"shipmentsComplete\":true,\"feesComplete\":false}'::jsonb WHERE tenant_id=? AND id=?",tenant,id);
  }
 }
 @Test void explicitParentQueueIsTenantScopedIdempotentAndUsesExistingWorker(){
  jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED' WHERE tenant_id=? AND id=?",tenant,id);
  jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED' WHERE tenant_id=? AND id=?",tenant,run);
  jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",tenant,connection,new byte[]{1},new byte[]{2});
  var worker=mock(FbaManualWorker.class);var manual=new FbaManualSync(jdbc,tx,worker,true);
  UUID queued=manual.requestParents(tenant,connection);assertThat(manual.requestParents(tenant,connection)).isEqualTo(queued);
  String checkpoint=jdbc.queryForObject("SELECT cursor::text FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",String.class,tenant,queued);
  assertThat(checkpoint).contains("parentsOnly", "itemsRefreshOnly", "feesComplete", "shipmentsComplete");
  verify(worker,times(2)).start(tenant,connection,queued);
  jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',completed_at=now(),error_message='HTTP 429',cursor=cursor||'{\"parentImport\":{\"requests\":7}}'::jsonb WHERE tenant_id=? AND id=?",tenant,queued);
  jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS' WHERE tenant_id=? AND id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?)",tenant,tenant,queued);
  assertThat(manual.requestParents(tenant,connection)).isEqualTo(queued);
  assertThat(jdbc.queryForObject("SELECT cursor->'parentImport'->>'requests' FROM marketplace_sync_jobs WHERE tenant_id=? AND id=?",String.class,tenant,queued)).isEqualTo("7");
  verify(worker,times(3)).start(tenant,connection,queued);
  assertThatThrownBy(()->manual.requestParents(UUID.randomUUID(),connection)).isInstanceOf(IllegalArgumentException.class);
  verifyNoMoreInteractions(worker);
 }
 @Test void immutableEvidenceDeduplicatesWithoutReassigningItsOriginalJob(){reply(listing(),"list");reply(detail(),"plan");reply("{\"shipmentId\":\""+child+"\",\"shipmentConfirmationId\":\"FBA123456789\"}","child");var capture=org.mockito.ArgumentCaptor.forClass(String.class);verify(store).saveFbaParentEvidence(eq(job),capture.capture(),eq("child"));String body=capture.getValue();tx.executeWithoutResult(s->{store.saveFbaParentEvidence(job,body,"repeat");store.saveFbaParentEvidence(job,body,"repeat");});assertThat(documents()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT amazon_request_id FROM amazon_source_documents WHERE tenant_id=?",String.class,tenant)).isEqualTo("child");assertThat(new FbaShipmentParents(jdbc,json).read(UUID.randomUUID(),connection,List.of("FBA123456789")).links()).isEmpty();}
}
