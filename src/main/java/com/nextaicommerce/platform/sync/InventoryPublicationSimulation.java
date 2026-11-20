package com.nextaicommerce.platform.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared durable planning, deliberately without an HTTP client or live publication method. */
@Component
@ConditionalOnProperty(name="app.inventory.publication-simulation-enabled",havingValue="true")
public class InventoryPublicationSimulation {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InventoryPublicationSimulation.class);
 private final InventoryPublicationRepository repository;private final TransactionTemplate tx;
 public InventoryPublicationSimulation(InventoryPublicationRepository repository,TransactionTemplate tx,Environment env){
  for(String key:java.util.List.of("app.amazon.write-enabled","app.amazon.inventory-publication-enabled","app.shopify.inventory.write-enabled","app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled"))
   if(!"false".equals(env.getProperty(key,"false")))throw new IllegalStateException("Stock simulation requires external write guards OFF: "+key);
  this.repository=repository;this.tx=tx;
 }
 @Scheduled(fixedDelayString="${app.inventory.publication-simulation-delay-ms:5000}",initialDelay=5000)
 public void tick(){for(var tenant:repository.tenants())try{tx.executeWithoutResult(s->{repository.plan(tenant);repository.simulate(tenant);});}catch(RuntimeException ex){log.error("Stock simulation failed for tenant {} ({}); other accounts continue.",tenant,ex.getClass().getSimpleName());}}
}
