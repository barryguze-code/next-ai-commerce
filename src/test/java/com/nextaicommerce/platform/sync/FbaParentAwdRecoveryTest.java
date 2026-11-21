package com.nextaicommerce.platform.sync;
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
class FbaParentAwdRecoveryTest {
 final ObjectMapper json=new ObjectMapper();final AtomicReference<String> saved=new AtomicReference<>();
 final JdbcTemplate jdbc=mock(JdbcTemplate.class);final AmazonSpApiClient amazon=mock(AmazonSpApiClient.class);final AmazonSyncStore store=mock(AmazonSyncStore.class);final FbaSyncHistory history=mock(FbaSyncHistory.class);
 final Instant end=Instant.parse("2026-11-20T22:35:13.306946Z");
 final AmazonSyncStore.Job job=new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FBA_INBOUND","FBA_INBOUND","ATVPDKIKX0DER",end.minusSeconds(86400),end,null,0,8,false,null);
 final String awd="wf1234abcd-1234-abcd-5678-1234abcd5678",fba="wf2234abcd-1234-abcd-5678-1234abcd5678";
 final String message="ERROR: Operation GetInboundPlan is not supported for Amazon Warehousing and Distribution inbound plans.";
 FbaInboundSync sync;
 @BeforeEach void setup(){var c=json.createObjectNode().put("shipmentsComplete",true).put("feesComplete",true).put("pages",348).put("records",532);var p=c.putObject("parentImport").put("reuseInitialized",true).put("completedPlans",18).put("requests",19).put("planIndex",0).put("listingRequestId","saved-list");for(String id:List.of(awd,fba)){var entry=p.withArray("listed").addObject().put("inboundPlanId",id).put("createdAt","2026-11-19T00:00:00Z");entry.putArray("marketplaceIds").add(job.marketplaceId());}saved.set(c.toString());
 when(jdbc.queryForObject(startsWith("SELECT cursor::text"),eq(String.class),any(Object[].class))).thenAnswer(i->saved.get());
 when(jdbc.update(startsWith("UPDATE marketplace_sync_jobs SET cursor="),any(Object[].class))).thenAnswer(i->{String value=i.getArgument(1);if(value.startsWith("{"))saved.set(value);return 1;});
 sync=new FbaInboundSync(amazon,store,jdbc,new TransactionTemplate(mock(PlatformTransactionManager.class)),json,mock(AmazonReportNormalizer.class));ReflectionTestUtils.setField(sync,"history",history);
 }
 ObjectNode cursor(){return (ObjectNode)json.readTree(saved.get());}
 @Test void exactUnsupportedAwdDoesNotTerminateParentDiscoveryAndNextFbaPlanStillImports(){
 when(amazon.getAt(any(),any(),any(),any())).thenThrow(new AmazonSpApiClient.AmazonApiException(400,null,message));sync.processPage(job);
 assertThat(cursor().path("parentsComplete").asBoolean()).isFalse();assertThat(cursor().path("parentImport").path("planIndex").asInt()).isEqualTo(1);assertThat(cursor().path("parentImport").path("skippedAwdPlans").asInt()).isEqualTo(1);
 assertThat(cursor().path("pages").asInt()).isEqualTo(348);assertThat(cursor().path("records").asInt()).isEqualTo(532);assertThat(cursor().path("feesComplete").asBoolean()).isTrue();
 var detail=json.createObjectNode().put("inboundPlanId",fba);detail.putArray("marketplaceIds").add(job.marketplaceId());detail.putArray("shipments");
 doReturn(new AmazonSpApiClient.ApiResponse(200,"next-fba",detail,detail.toString())).when(amazon).getAt(any(),any(),any(),any());sync.processPage(job);
 verify(amazon).getAt(any(),any(),any(),eq(FbaParentPlanImport.BASE+"/"+fba));verify(store).saveFbaParentEvidence(eq(job),anyString(),eq("next-fba"));verify(store,never()).complete(any(),anyLong());
 }
 @Test void otherBadRequestAndAuthorizationAreNotSkipped(){for(int status:List.of(400,403)){setup();doThrow(new AmazonSpApiClient.AmazonApiException(status,null,status==400?"Different invalid request":message)).when(amazon).getAt(any(),any(),any(),any());sync.processPage(job);assertThat(cursor().path("parentsComplete").asBoolean()).isTrue();assertThat(cursor().path("parentImport").path("planIndex").asInt()).isZero();assertThat(cursor().path("parentImport").path("skippedAwdPlans").asInt()).isZero();}}
 @Test void throttleDoesNotSkipAndRetainsSavedPagination(){when(amazon.getAt(any(),any(),any(),any())).thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(60),message));assertThatThrownBy(()->sync.processPage(job)).isInstanceOf(AmazonSpApiClient.AmazonApiException.class);assertThat(cursor().path("parentImport").path("planIndex").asInt()).isZero();assertThat(cursor().path("parentImport").path("skippedAwdPlans").asInt()).isZero();verify(store,never()).saveFbaParentEvidence(any(),anyString(),anyString());}
 @Test void completedResumedParentDoesNotRestartShipmentOrFeeReads(){var c=cursor();c.put("parentsComplete",true);saved.set(c.toString());sync.processPage(job);verify(store).complete(job,532);verifyNoInteractions(amazon);assertThat(cursor().path("pages").asInt()).isEqualTo(348);}
}
