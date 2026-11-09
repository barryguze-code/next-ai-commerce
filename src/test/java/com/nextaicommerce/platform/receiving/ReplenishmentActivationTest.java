package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ReplenishmentActivationTest {
 @Test void draftLoadsWithoutLocalProfile(){
  try(var context=new AnnotationConfigApplicationContext()){
   context.registerBean(JdbcTemplate.class,()->mock(JdbcTemplate.class));
   context.registerBean(TransactionTemplate.class,()->mock(TransactionTemplate.class));
   context.registerBean(InventoryRepository.class,()->mock(InventoryRepository.class));
   context.registerBean(CatalogRepository.class,()->mock(CatalogRepository.class));
   context.registerBean(ObjectMapper.class,()->new ObjectMapper());
   context.register(ReplenishmentDataLoader.class,ReplenishmentSuggestions.class,ReplenishmentDraftController.class);
   context.refresh();
   assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("local");
   assertThat(context.getBean(ReplenishmentDraftController.class)).isNotNull();
   assertThat(context.getBean(ReplenishmentSuggestions.class)).isNotNull();
  }
 }
 @Test void scheduledForecastDoesNotBlockCallerOrQueueDuplicateWork() throws Exception {
  var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  var service=new ReplenishmentSuggestions(null,null,null,null){@Override public void refresh(){calls.incrementAndGet();started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}};
  try{service.queueRefresh();assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();service.queueRefresh();assertThat(calls.get()).isEqualTo(1);}
  finally{release.countDown();service.stopWorker();}
 }
 @Test void manualForecastRunsWithSchedulingDisabled() throws Exception {
  var started=new CountDownLatch(1);var tenant=java.util.UUID.randomUUID();
  var service=new ReplenishmentSuggestions(null,null,null,null){@Override public void refresh(){started.countDown();}};
  try{service.requestRefresh(tenant);assertThat(started.await(2,TimeUnit.SECONDS)).as("Manual request must dispatch offline forecast without scheduler").isTrue();}
  finally{service.stopWorker();}
 }

 @Test void requestWhileWorkerBusyIsNotLostWithoutScheduler() throws Exception {
  var started=new CountDownLatch(1);var release=new CountDownLatch(1);var followup=new CountDownLatch(1);var calls=new AtomicInteger();
  var service=new ReplenishmentSuggestions(null,null,null,null){@Override public void refresh(){if(calls.incrementAndGet()==1){started.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}else followup.countDown();}};
  try{service.queueRefresh();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();service.requestRefresh(java.util.UUID.randomUUID());release.countDown();assertThat(followup.await(2,TimeUnit.SECONDS)).isTrue();}
  finally{release.countDown();service.stopWorker();}
 }

 @Test void staleSnapshotDispatchesWithoutSchedulerAndRetryClearsOldError() throws Exception {
  var started=new CountDownLatch(1);var release=new CountDownLatch(1);var tenant=java.util.UUID.randomUUID();
  var stale=new ReplenishmentSuggestions.Snapshot(java.util.List.of(),java.time.Instant.now().minusSeconds(30000));
  var service=new ReplenishmentSuggestions(null,null,null,null){@Override public Snapshot snapshot(java.util.UUID id){return stale;}@Override public void refresh(){started.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}};
  try{service.ensureFresh(tenant);assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
   @SuppressWarnings("unchecked") var errors=(java.util.Map<java.util.UUID,String>)org.springframework.test.util.ReflectionTestUtils.getField(service,"errors");errors.put(tenant,"old failure");service.requestRefresh(tenant);assertThat(service.error(tenant)).isNull();
  }finally{release.countDown();service.stopWorker();}
 }

}
