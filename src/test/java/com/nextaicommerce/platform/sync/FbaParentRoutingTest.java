package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.fba.FbaDepletionWorker;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FbaParentRoutingTest {
 final ObjectMapper json=new ObjectMapper();
 final AtomicReference<String> cursor=new AtomicReference<>();
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);final AmazonSpApiClient amazon=mock(AmazonSpApiClient.class);final AmazonSyncStore store=mock(AmazonSyncStore.class);final FbaDepletionWorker depletion=mock(FbaDepletionWorker.class);
 FbaInboundSync sync;
 AmazonSyncStore.Job job(String profile){return new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FBA_INBOUND",profile,"ATVPDKIKX0DER",Instant.now().minusSeconds(86400),Instant.now(),null,0,0,false,null);}
 @BeforeEach void setup(){
  when(jdbc.queryForObject(startsWith("SELECT cursor::text"),eq(String.class),any(Object[].class))).thenAnswer(i->cursor.get());
  when(jdbc.update(startsWith("UPDATE marketplace_sync_jobs SET cursor=CAST"),any(Object[].class))).thenAnswer(i->{cursor.set((String)i.getArgument(1));return 1;});
  var normalizer=mock(AmazonReportNormalizer.class);when(normalizer.scopedShipmentFinances(any(),any())).thenReturn(json.createObjectNode());
  sync=new FbaInboundSync(amazon,store,jdbc,new TransactionTemplate(mock(PlatformTransactionManager.class)),json,normalizer);ReflectionTestUtils.setField(sync,"depletionWorker",depletion);
 }
 AmazonSpApiClient.ApiResponse response(String body){return new AmazonSpApiClient.ApiResponse(200,"request",json.readTree(body),body,2.0);}
 @Test void parentOnlyFinishesWithoutLegacyFeesOrDepletionAndRepeatedCompletionCannotFallThrough(){
  cursor.set("{\"parentsOnly\":true,\"itemsRefreshOnly\":true,\"shipmentsComplete\":true,\"feesComplete\":true}");var job=job("FBA_MANUAL");
  when(amazon.getAt(any(),any(),any(),any())).thenReturn(response("{\"inboundPlans\":[]}"));
  for(int i=0;i<3;i++)sync.processPage(job);
  assertThat(json.readTree(cursor.get()).path("parentImport").path("coverage").asText()).isEqualTo("API_DISCOVERY_COMPLETE");
  verify(store).complete(job,0);sync.processPage(job);verify(amazon,times(3)).getAt(any(),any(),any(),any());verify(amazon,never()).get(any(),any(),any());verifyNoInteractions(depletion);verify(store,never()).saveJsonPage(any(),any(),any(),any(),any());
 }
 @Test void standardManualSyncStartsVerifiedParentRead(){cursor.set("{\"shipmentsComplete\":true}");when(amazon.getAt(any(),any(),any(),any())).thenReturn(response("{\"inboundPlans\":[]}"));when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"transactions\":[]}}"));sync.processPage(job("FBA_MANUAL"));verify(amazon).getAt(any(),any(),eq("https://sellingpartnerapi-na.amazon.com"),contains("/inbound/fba/2024-03-20/inboundPlans"));}
 @Test void automaticAndInitializationJobsDoNotStartNewParentReads(){for(String profile:new String[]{"FBA_INBOUND","INITIALIZATION"}){cursor.set("{\"shipmentsComplete\":true}");when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"transactions\":[]}}"));sync.processPage(job(profile));}verify(amazon,never()).getAt(any(),any(),any(),any());}
 @Test void selectedItemsDoNotStartParentReads(){cursor.set("{\"itemsRefreshOnly\":true,\"feesComplete\":true,\"pending\":[\"FBA123\"]}");when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"ItemData\":[]}}"));sync.processPage(job("FBA_MANUAL"));verify(amazon,never()).getAt(any(),any(),any(),any());verifyNoInteractions(depletion);}
 @Test void parentOnlyCannotBeInjectedIntoAutomaticProfile(){cursor.set("{\"parentsOnly\":true}");assertThatThrownBy(()->sync.processPage(job("FBA_INBOUND"))).isInstanceOf(IllegalStateException.class);verifyNoInteractions(amazon,depletion);}
 @Test void throttleDoesNotWriteOrAdvanceParentCheckpoint(){cursor.set("{\"parentsOnly\":true,\"itemsRefreshOnly\":true}");String before=cursor.get();when(amazon.getAt(any(),any(),any(),any())).thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(60),"rate"));assertThatThrownBy(()->sync.processPage(job("FBA_MANUAL"))).isInstanceOf(AmazonSpApiClient.AmazonApiException.class);assertThat(cursor.get()).isEqualTo(before);verifyNoInteractions(store,depletion);}
 @Test void parentStatusNeverClaimsSuccessForQueuedDeniedOrPartialReads(){
  assertThat(FbaManualSync.parentMessage("QUEUED","API_DISCOVERY_COMPLETE")).isEqualTo("Refreshing parent shipment links…");
  assertThat(FbaManualSync.parentMessage("COMPLETED","API_DISCOVERY_COMPLETE")).isEqualTo("Parent shipment links refreshed.");
  assertThat(FbaManualSync.parentMessage("COMPLETED_WITH_WARNINGS","PARTIAL")).isEqualTo("Some parent shipment links could not be verified. Existing links are retained.");
  assertThat(FbaManualSync.parentMessage("FAILED","UNAVAILABLE")).isEqualTo("Parent shipment links could not be refreshed. Existing links are retained.");
 }
}
