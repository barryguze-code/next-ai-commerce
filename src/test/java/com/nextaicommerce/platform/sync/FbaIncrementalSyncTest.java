package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.fba.FbaDepletionWorker;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FbaIncrementalSyncTest {
 final ObjectMapper json=new ObjectMapper();final AtomicReference<String> saved=new AtomicReference<>();
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);final AmazonSpApiClient amazon=mock(AmazonSpApiClient.class);final AmazonSyncStore store=mock(AmazonSyncStore.class);final FbaSyncHistory history=mock(FbaSyncHistory.class);final FbaDepletionWorker depletion=mock(FbaDepletionWorker.class);
 final Instant end=Instant.parse("2026-11-20T00:00:00Z");final AmazonSyncStore.Job job=new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FBA_INBOUND","FBA_MANUAL","ATVPDKIKX0DER",end.minus(Duration.ofDays(180)),end,null,0,0,false,null);
 FbaInboundSync sync;
 @BeforeEach void setup(){
  var c=json.createObjectNode();FbaShipmentReadPlan.initialize(c,true,end.minusSeconds(86400),end);saved.set(c.toString());
  when(jdbc.queryForObject(startsWith("SELECT cursor::text"),eq(String.class),any(Object[].class))).thenAnswer(i->saved.get());
  when(jdbc.update(startsWith("UPDATE marketplace_sync_jobs SET cursor="),any(Object[].class))).thenAnswer(i->{String value=i.getArgument(1);if(value.startsWith("{"))saved.set(value);return 1;});
  sync=new FbaInboundSync(amazon,store,jdbc,new TransactionTemplate(mock(PlatformTransactionManager.class)),json,mock(AmazonReportNormalizer.class));ReflectionTestUtils.setField(sync,"history",history);ReflectionTestUtils.setField(sync,"depletionWorker",depletion);
  when(history.metadata(any(),any())).thenReturn(Map.of());
  when(history.nextOpenBatch(any(),any())).thenAnswer(i->{ObjectNode checkpoint=i.getArgument(1);if(checkpoint.path("shipmentRead").has("openBatch"))return true;checkpoint.withObject("shipmentRead").put("stage","DONE");return false;});
 }
 AmazonSpApiClient.ApiResponse response(String body){return new AmazonSpApiClient.ApiResponse(200,"request",json.readTree(body),body,2.0);}
 ObjectNode cursor(){return (ObjectNode)json.readTree(saved.get());}
 @Test void recentClosedTransitionGetsFinalItemReadBeforeOlderOpenSweep(){
  when(amazon.get(any(),any(),contains("QueryType=DATE_RANGE"))).thenReturn(response("{\"payload\":{\"ShipmentData\":[{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"CLOSED\"}]}}"));
  sync.processPage(job);assertThat(cursor().path("pending").get(0).asText()).isEqualTo("FBA-A");assertThat(cursor().path("shipmentRead").path("coveredThrough").asText()).isEqualTo(end.minusSeconds(86400).toString());
  when(amazon.get(any(),any(),endsWith("/FBA-A/items"))).thenReturn(response("{\"payload\":{\"ItemData\":[]}}"));sync.processPage(job);
  assertThat(cursor().path("shipmentRead").path("stage").asText()).isEqualTo("OPEN");verify(amazon).get(any(),any(),endsWith("/FBA-A/items"));verifyNoInteractions(depletion);verify(store,never()).complete(any(),anyLong());
 }
 @Test void unchangedClosedRecentMetadataDoesNotRefetchItems(){
  var row=json.readTree("{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"CLOSED\",\"LastUpdatedDate\":\"2026-11-19T00:00:00Z\"}");var prior=json.createObjectNode().put("itemEvidenceVersion",1).put("itemsMetadataRevision",FbaShipmentReadPlan.revision(row));prior.putArray("itemEvidence");when(history.metadata(any(),any())).thenReturn(Map.of("FBA-A",prior));
  when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"ShipmentData\":[{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"CLOSED\",\"LastUpdatedDate\":\"2026-11-19T00:00:00Z\"}]}}"));sync.processPage(job);assertThat(cursor().path("pending")).isEmpty();assertThat(cursor().path("shipmentRead").path("stage").asText()).isEqualTo("OPEN");verify(amazon,never()).get(any(),any(),endsWith("/items"));
 }
 @Test void throttleRetainsDateBoundsAndCoverageWithoutDepletionOrCompletion(){String before=saved.get();when(amazon.get(any(),any(),any())).thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(60),"cooldown"));for(int i=0;i<2;i++)assertThatThrownBy(()->sync.processPage(job)).isInstanceOf(AmazonSpApiClient.AmazonApiException.class);assertThat(saved.get()).isEqualTo(before);verifyNoInteractions(depletion);verify(store,never()).complete(any(),anyLong());}
 @Test void hourlyBudgetPausesExactCheckpointWithoutNetworkOrFalseCompletion(){var c=cursor();c.putObject("readBudget").put("start",Instant.now().toString()).put("requests",600);saved.set(c.toString());sync.processPage(job);assertThat(cursor().path("paused").asBoolean()).isTrue();assertThat(cursor().path("shipmentRead").path("windowEnd").asText()).isEqualTo(end.toString());verifyNoInteractions(amazon,depletion,store);}
 @Test void olderOpenMissingFromResponseDoesNotAdvanceSuccessfulWatermark(){var c=cursor();c.withObject("shipmentRead").put("stage","OPEN").putArray("openBatch").add("FBA-OLD");saved.set(c.toString());when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"ShipmentData\":[]}}"));sync.processPage(job);assertThat(cursor().path("shipmentRead").path("unresolvedOpen").asBoolean()).isTrue();assertThat(cursor().path("shipmentRead").path("coveredThrough").asText()).isEqualTo(end.minusSeconds(86400).toString());verify(amazon).get(any(),any(),contains("ShipmentIdList=FBA-OLD"));verifyNoInteractions(depletion);}
 @Test void legacyCheckpointAboveOldBudgetResumesSameTokenWithoutRestart(){saved.set("{\"pages\":1300,\"next\":\"legacy-token\"}");when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"ShipmentData\":[],\"NextToken\":\"next-page\"}}"));sync.processPage(job);verify(amazon).get(any(),any(),contains("QueryType=NEXT_TOKEN&NextToken=legacy-token"));assertThat(cursor().path("pages").asInt()).isEqualTo(1301);assertThat(cursor().path("next").asText()).isEqualTo("next-page");}
 @Test void explicitBudgetResumeMakesNextRealRequestWithoutResettingProgress(){var c=cursor();c.put("pages",1500).put("paused",true).put("pauseReason","READ_BUDGET");c.putObject("readBudget").put("start",Instant.now().toString()).put("requests",600);assertThat(FbaShipmentReadPlan.resume(c,Instant.now())).isTrue();saved.set(c.toString());when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"ShipmentData\":[]}}"));sync.processPage(job);verify(amazon).get(any(),any(),contains("QueryType=DATE_RANGE"));assertThat(cursor().path("pages").asInt()).isEqualTo(1501);assertThat(cursor().path("paused").asBoolean()).isFalse();}
 @Test void incrementalEmptyRecentAndOpenSweepsNeverQueryHistoricalClosedStatuses(){when(amazon.get(any(),any(),any())).thenReturn(response("{\"payload\":{\"ShipmentData\":[]}}"));sync.processPage(job);sync.processPage(job);verify(amazon,times(1)).get(any(),any(),contains("QueryType=DATE_RANGE"));verify(amazon,never()).get(any(),any(),contains("ShipmentStatusList"));assertThat(cursor().path("shipmentsComplete").asBoolean()).isTrue();}
}
