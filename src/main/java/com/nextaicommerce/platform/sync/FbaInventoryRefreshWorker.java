package com.nextaicommerce.platform.sync;
import java.time.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Bounded explicit GET-only inventory read; no scheduled/general worker activation. */
@Component
public class FbaInventoryRefreshWorker {
 private final FbaInventoryRefresh refresh;private final AmazonSpApiClient amazon;private final ObjectMapper json;private final com.nextaicommerce.platform.fba.FbaReplenishmentForecasts forecasts;
 public FbaInventoryRefreshWorker(FbaInventoryRefresh refresh,AmazonSpApiClient amazon,ObjectMapper json,com.nextaicommerce.platform.fba.FbaReplenishmentForecasts forecasts){this.refresh=refresh;this.amazon=amazon;this.json=json;this.forecasts=forecasts;}
 @Async @EventListener public void read(FbaInventoryRefresh.Request request){String market=refresh.claim(request);if(market==null)return;try{
  var pages=json.createArrayNode();var tokens=new HashSet<String>();String next="";Instant deadline=Instant.now().plusSeconds(600);
  // Marketplace routing is explicit; the generic client defaults to North America.
  String endpoint=endpoint(market);
  for(int i=0;i<100;i++){
   if(Instant.now().isAfter(deadline)||Thread.currentThread().isInterrupted())throw new IllegalStateException("Inventory read budget reached.");
   String path="/fba/inventory/v1/summaries?details=true&granularityType=Marketplace&granularityId="+encode(market)+"&marketplaceIds="+encode(market)+(next.isBlank()?"":"&nextToken="+encode(next));
   var api=amazon.getAt(request.tenant(),request.store(),endpoint,path);pages.add(json.createObjectNode().put("observedAt",Instant.now().toString()).put("requestId",api.requestId()).set("response",api.json()));
   next=api.json().path("pagination").path("nextToken").asText("");if(next.isBlank()){refresh.publish(request,market,pages);forecasts.refresh(request.tenant(),request.store());return;}
   if(!tokens.add(next))throw new IllegalStateException("Repeated inventory token.");Thread.sleep(700);
  }throw new IllegalStateException("Inventory page budget reached.");
 }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();refresh.failed(request);}}
 static String encode(String s){return java.net.URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8);}
 static String endpoint(String market){return AmazonSpApiClient.endpoint(market);}
}
