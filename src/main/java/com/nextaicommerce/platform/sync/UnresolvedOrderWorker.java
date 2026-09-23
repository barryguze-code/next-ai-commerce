package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import static com.nextaicommerce.platform.sync.UnresolvedOrderRepository.Check;

@Component
@ConditionalOnProperty(name="app.amazon.unresolved-order-checks-enabled",havingValue="true")
public class UnresolvedOrderWorker {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(UnresolvedOrderWorker.class);
 private final UnresolvedOrderRepository repository;private final AmazonSpApiClient amazon;private final TransactionTemplate tx;
 public UnresolvedOrderWorker(UnresolvedOrderRepository repository,AmazonSpApiClient amazon,TransactionTemplate tx){this.repository=repository;this.amazon=amazon;this.tx=tx;}
 @Scheduled(fixedDelayString="${app.amazon.unresolved-order-check-delay-ms:5000}",initialDelayString="${app.amazon.unresolved-order-check-initial-delay-ms:60000}")
 public void tick(){
  for(var tenant:repository.tenants())try{
   var claimed=tx.execute(s->repository.claim(tenant));
   if(claimed!=null&&claimed.isPresent()){check(claimed.get());break;}
  }catch(RuntimeException e){log.warn("Unresolved order verification failed for tenant {}",tenant,e);}
 }
 void check(Check c){
  try{
   var order=amazon.get(c.tenant(),c.connection(),"/orders/v0/orders/"+URLEncoder.encode(c.orderId(),StandardCharsets.UTF_8).replace("+","%20")).json().path("payload");
   String status=order.path("OrderStatus").asText();
   if(!c.orderId().equals(order.path("AmazonOrderId").asText())||!Set.of("Pending","PendingAvailability","Unshipped","PartiallyShipped","Shipped","Canceled","Cancelled","Unfulfillable").contains(status))
    throw new IllegalStateException("Unrecognized or mismatched order response");
   // A terminal response without its update timestamp is not enough evidence to release stock.
   Instant updated=Instant.parse(order.path("LastUpdateDate").asText());
   tx.executeWithoutResult(s->repository.confirmed(c,status,updated));
  }catch(Exception e){
   int delay=Math.min(21600,60*(1<<Math.min(8,c.failures())));
   boolean missing=e instanceof AmazonSpApiClient.AmazonApiException a&&a.status()==404;
   if(e instanceof AmazonSpApiClient.AmazonApiException a&&a.status()==429&&a.retryAfter()!=null)delay=Math.max(delay,(int)Math.min(21600,a.retryAfter().toSeconds()));
   int waitSeconds=delay;
   tx.executeWithoutResult(s->repository.failed(c,missing?"UNCONFIRMED":"RETRY",missing?"Amazon could not find this order. This is not confirmed cancellation.":"Amazon order status could not be verified; retry scheduled.",waitSeconds));
  }
 }
}
