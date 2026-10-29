package com.nextaicommerce.platform.shopify;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Default off, explicit account/store pairs, no local outbound inventory under any configuration. */
@Component
@ConditionalOnProperty(name="app.shopify.inventory.publication-enabled",havingValue="true")
public class ShopifyInventoryPublicationWorker {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ShopifyInventoryPublicationWorker.class);
 private final ShopifyInventoryPublications repository;private final ShopifyInventoryClient client;
 private final List<Scope> scopes;private final Instant startup=Instant.now();private int next;
 record Scope(UUID tenant,UUID connection){}
 public ShopifyInventoryPublicationWorker(ShopifyInventoryPublications repository,ShopifyInventoryClient client,
  @Value("${app.local-development:false}") boolean local,
  @Value("${app.shopify.inventory.write-enabled:false}") boolean writes,
  @Value("${app.shopify.inventory.publication-scopes:}") String configured){
  if(local||!writes)throw new IllegalStateException("Shopify publication requires nonlocal inventory writes explicitly enabled");
  this.repository=repository;this.client=client;
  this.scopes=Arrays.stream(configured.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(s->{var pair=s.split("/",-1);if(pair.length!=2)throw new IllegalArgumentException("Specify tenant/connection scopes");return new Scope(UUID.fromString(pair[0]),UUID.fromString(pair[1]));}).distinct().toList();
  if(scopes.isEmpty())throw new IllegalStateException("Explicit Shopify publication scopes required");
 }
 @Scheduled(fixedDelayString="${app.shopify.inventory.publication-delay-ms:2000}",initialDelayString="${app.shopify.inventory.publication-initial-delay-ms:30000}")
 public void tick(){
  var scope=scopes.get(Math.floorMod(next++,scopes.size()));
  try{
   repository.plan(scope.tenant(),scope.connection(),startup);
   repository.next(scope.tenant(),scope.connection()).ifPresent(p->{switch(p.state()){
    case "PREPARING"->repository.prepare(p,client,startup);
    case "READY"->{if(repository.markSubmitting(p,startup))repository.submit(p,client,startup);}
    case "VERIFYING"->repository.verify(p,client);
    default->throw new IllegalStateException("Unsupported publication state");
   }});
  }catch(RuntimeException e){log.warn("Shopify publication paused for account {} connection {} ({})",scope.tenant(),scope.connection(),e.getClass().getSimpleName());}
 }
}
