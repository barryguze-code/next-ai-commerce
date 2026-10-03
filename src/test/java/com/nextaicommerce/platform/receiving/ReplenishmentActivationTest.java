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
}
