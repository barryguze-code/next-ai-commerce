package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.sync.InventoryPublicationRepository.Pending;

/** Local defaults cannot call Amazon. Live activation is a separate, explicit deployment decision. */
@Component
@ConditionalOnProperty(name="app.amazon.inventory-publication-enabled",havingValue="true")
public class InventoryPublicationWorker {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InventoryPublicationWorker.class);
 private final InventoryPublicationRepository repository; private final TransactionTemplate tx;
 private final AmazonSpApiClient amazon; private final ObjectMapper json;
 private final boolean dryRun;
 private final java.time.Instant startup=java.time.Instant.now();
 public InventoryPublicationWorker(InventoryPublicationRepository repository,TransactionTemplate tx,AmazonSpApiClient amazon,ObjectMapper json,
  @Value("${app.amazon.inventory-publication-mode:DRY_RUN}") String mode,
  @Value("${app.local-development:false}") boolean local,
  @Value("${app.amazon.write-enabled:false}") boolean writes,
  @Value("${app.amazon.listing-actions-enabled:false}") boolean legacy){
  if(!mode.equals("DRY_RUN")&&!mode.equals("LIVE"))throw new IllegalArgumentException("Unknown inventory publication mode");
  if(mode.equals("LIVE")&&(local||!writes||legacy))throw new IllegalStateException("Live inventory requires nonlocal writes and the legacy listing worker disabled");
  this.repository=repository;this.tx=tx;this.amazon=amazon;this.json=json;this.dryRun=mode.equals("DRY_RUN");
 }
 @Scheduled(fixedDelayString="${app.amazon.inventory-publication-delay-ms:1000}",initialDelayString="${app.amazon.inventory-publication-initial-delay-ms:30000}")
 public void tick(){
  // One request-bearing transaction per tick, globally (including multiple application instances).
  for(var tenant:repository.tenants()){
   try{
    tx.executeWithoutResult(s->{repository.plan(tenant);if(dryRun)repository.simulate(tenant);});
    if(!dryRun){Boolean sent=tx.execute(s->{
     repository.scope(tenant);
     var pending=repository.next(tenant,startup);if(pending.isEmpty()||!repository.acquireRequestSlot())return false;
     process(pending.get());return true;
    });if(Boolean.TRUE.equals(sent))break;}
   }catch(RuntimeException e){log.error("Inventory publication failed for tenant {}",tenant,e);}
  }
 }
 void process(Pending p){
  // Last-resort guard: tests and local code cannot accidentally invoke this live path.
  if(dryRun){repository.result(p,"DRY_RUN",0,0,"Local simulation only — no Amazon request sent.",null);return;}
  int attempt=p.attempts()+1;
  try{
   String path="/listings/2021-08-01/items/"+encode(p.seller())+"/"+encode(p.sku())+"?marketplaceIds="+encode(p.marketplace());
   if(!p.status().equals("PENDING")){
    var response=amazon.get(p.tenant(),p.connection(),path+"&includedData=fulfillmentAvailability,issues");
    Integer observed=null;
    for(var availability:response.json().path("fulfillmentAvailability"))if("DEFAULT".equals(availability.path("fulfillmentChannelCode").asText())&&availability.path("quantity").isNumber())observed=availability.path("quantity").intValue();
    if(observed!=null&&observed==p.quantity()){
     repository.result(p,"CONFIRMED",0,900,null,observed);return;
    }
    // A lower positive quantity may be a sale we have not imported. Never replenish it by retrying a stale number.
    if(p.quantity()>0&&(observed==null||observed<p.quantity())){
     repository.result(p,"ATTENTION",attempt,300,"Live quantity is lower or unavailable; awaiting stock/order reconciliation, not restoring a stale quantity.",observed);return;
    }
    // Separate reads and writes into different ticks to respect request budgets.
    repository.result(p,attempt>=3?"ATTENTION":"PENDING",attempt,retryDelay(attempt),"Live quantity not confirmed; retry required.",observed);
    if(attempt>=3&&p.quantity()==0)repository.result(p,"PENDING",attempt,retryDelay(attempt),"Urgent: zero quantity still unconfirmed after repeated attempts.",observed);
    return;
   }
   var body=json.createObjectNode().put("productType","PRODUCT");
   var patch=body.putArray("patches").addObject().put("op","merge").put("path","/attributes/fulfillment_availability");
   patch.putArray("value").addObject().put("fulfillment_channel_code","DEFAULT").put("quantity",p.quantity());
   var response=amazon.patch(p.tenant(),p.connection(),path,json.writeValueAsString(body));
   if(!"ACCEPTED".equals(response.json().path("status").asText()))throw new IllegalStateException("Amazon did not accept inventory submission");
   repository.result(p,"VERIFYING",attempt,10,null,null);
  }catch(Exception e){
   String message="Inventory request failed; "+e.getClass().getSimpleName()+". Verification/retry pending.";
   repository.result(p,attempt>=3?"ATTENTION":"RETRY",attempt,retryDelay(attempt),message,null);
   log.warn("Inventory update unconfirmed: tenant={} sku={} revision={} attempt={}",p.tenant(),p.sku(),p.revision(),attempt);
  }
 }
 static int retryDelay(int attempt){return Math.min(900,10*(1<<Math.min(6,Math.max(0,attempt))))+ThreadLocalRandom.current().nextInt(5);}
 private static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8).replace("+","%20");}
}
