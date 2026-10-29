package com.nextaicommerce.platform.sync;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper;
import com.nextaicommerce.platform.orders.OrderRepository;
import java.time.*;import java.util.*;import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class AmazonOrderWorkerResumeTest {
 private AmazonSyncStore.Job job(){var now=Instant.parse("2026-10-29T00:00:00Z");return new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"ORDERS_API_DELTA","ORDER_CHANGES","US",now.minusSeconds(300),now,null,0,0,true,null,"claim");}
 private AmazonSyncWorker worker(AmazonSyncStore s,AmazonSpApiClient a){return new AmazonSyncWorker(s,a,mock(AmazonReportNormalizer.class),mock(MarketplaceSkuAutoMapper.class),mock(OrderRepository.class),true);}
 @Test void crashResumeSkipsCommittedOrdersAndContinuesExactItemPage(){
  var j=job();var s=mock(AmazonSyncStore.class);var a=mock(AmazonSpApiClient.class);when(s.claimOrderRefresh(anyString())).thenReturn(j);
  when(s.orderDeltaProgress(j)).thenReturn(new AmazonOrderDeltaProgress(1,true,null,1,List.of("DONE","REMAINING"),1,"page-two",1,5,Instant.EPOCH));
  var body="{\"payload\":{\"OrderItems\":[]}}";when(a.get(eq(j.tenantId()),eq(j.connectionId()),anyString())).thenReturn(new AmazonSpApiClient.ApiResponse(200,"fixture",new ObjectMapper().readTree(body),body));
  when(s.saveOrderDeltaPage(eq(j),anyString(),anyString(),anyString(),any(),any())).thenAnswer(c->((LongFunction<?>)c.getArgument(5)).apply(2));
  worker(s,a).processOrderRefresh();verify(a,times(1)).get(j.tenantId(),j.connectionId(),"/orders/v0/orders/REMAINING/orderItems?NextToken=page-two");verify(s,times(2)).renewOrderLease(j);verify(s).complete(j,7);
 }
 @Test void lostOwnershipStopsBeforeNetworkAndDoesNotOverwriteRetry(){
  var j=job();var s=mock(AmazonSyncStore.class);var a=mock(AmazonSpApiClient.class);when(s.claimOrderRefresh(anyString())).thenReturn(j);when(s.orderDeltaProgress(j)).thenThrow(new AmazonSyncStore.LostOrderLease());
  worker(s,a).processOrderRefresh();verifyNoInteractions(a);verify(s,never()).retryOrFail(any(),any(),any());verify(s,never()).complete(any(),anyLong());
 }
 @Test void throttlingRetainsCheckpointAndHonorsRetryAfter(){
  var j=job();var s=mock(AmazonSyncStore.class);var a=mock(AmazonSpApiClient.class);when(s.claimOrderRefresh(anyString())).thenReturn(j);when(s.orderDeltaProgress(j)).thenReturn(AmazonOrderDeltaProgress.empty());
  var delay=Duration.ofSeconds(47);var rejected=new AmazonSpApiClient.AmazonApiException(429,delay,"fixture throttle");when(a.get(any(),any(),anyString())).thenThrow(rejected);
  worker(s,a).processOrderRefresh();verify(s).renewOrderLease(j);verify(s).retryOrFail(j,rejected,delay);verify(s,never()).saveOrderDeltaPage(any(),any(),any(),any(),any(),any());verify(s,never()).complete(any(),anyLong());
 }
 @Test void ownershipLostWhileRecordingRetryStopsQuietly(){
  var j=job();var s=mock(AmazonSyncStore.class);var a=mock(AmazonSpApiClient.class);when(s.claimOrderRefresh(anyString())).thenReturn(j);when(s.orderDeltaProgress(j)).thenReturn(AmazonOrderDeltaProgress.empty());
  var rejected=new AmazonSpApiClient.AmazonApiException(429,Duration.ofSeconds(47),"fixture throttle");when(a.get(any(),any(),anyString())).thenThrow(rejected);
  doThrow(new AmazonSyncStore.LostOrderLease()).when(s).retryOrFail(eq(j),eq(rejected),any());
  org.assertj.core.api.Assertions.assertThatCode(()->worker(s,a).processOrderRefresh()).doesNotThrowAnyException();
  verify(s,times(1)).retryOrFail(eq(j),eq(rejected),any());verify(s,never()).complete(any(),anyLong());verify(s,never()).fail(any(),any());
 }
}
