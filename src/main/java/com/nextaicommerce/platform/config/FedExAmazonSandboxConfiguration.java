package com.nextaicommerce.platform.config;
import org.springframework.context.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.beans.factory.config.BeanFactoryPostProcessor;import org.springframework.core.env.*;
/** Explicit local native-Amazon shipping capability; no collectors or marketplace writes. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.fedex.amazon-sandbox-enabled",havingValue="true")
public class FedExAmazonSandboxConfiguration {
 @Bean static BeanFactoryPostProcessor protectAmazonSandboxShipping(Environment env){return factory->{
  if(env.getActiveProfiles().length!=1||!env.acceptsProfiles(Profiles.of("local","fee-release-uat"))||!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://127[.]0[.]0[.]1:55432/[a-z0-9_]+")||!"SANDBOX".equals(env.getProperty("app.fedex.platform-environment","SANDBOX")))throw new IllegalStateException("Amazon sandbox shipping requires the approved local database and FedEx sandbox.");
  for(String key:java.util.List.of("app.fedex.sandbox-rate-only.enabled","app.fedex.local-shipping-uat.enabled","app.fedex.simulation-enabled"))if(!env.getProperty(key,Boolean.class,false))throw new IllegalStateException("Amazon sandbox shipping requires guarded local carrier capabilities.");
  for(String key:java.util.List.of("app.amazon.write-enabled","app.amazon.inventory-publication-enabled","app.amazon.buy-shipping-enabled","app.fedex.external-enabled","app.fedex.confirmation-enabled","app.shopify.inventory.write-enabled","app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled"))if(!"false".equals(env.getProperty(key,"false")))throw new IllegalStateException("Amazon sandbox shipping requires external writes OFF: "+key);
 };}
}
