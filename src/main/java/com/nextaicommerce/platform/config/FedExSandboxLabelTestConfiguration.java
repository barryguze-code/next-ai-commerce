package com.nextaicommerce.platform.config;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.*;

/** Imported shipped orders are label fixtures only in an explicitly guarded local sandbox. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.fedex.sandbox-label-test-enabled",havingValue="true")
public class FedExSandboxLabelTestConfiguration {
 @Bean static BeanFactoryPostProcessor protectSandboxLabelTest(Environment env){return factory->{
  if(env.getActiveProfiles().length!=1||!env.acceptsProfiles(Profiles.of("local","fee-release-uat"))
    ||!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://127[.]0[.]0[.]1:55432/[a-z0-9_]+")
    ||!"SANDBOX".equals(env.getProperty("app.fedex.platform-environment","SANDBOX")))
   throw new IllegalStateException("Sandbox label tests require an approved local database/profile and sandbox credentials.");
  for(String key:java.util.List.of("app.amazon.write-enabled","app.amazon.inventory-publication-enabled","app.amazon.buy-shipping-enabled","app.fedex.confirmation-enabled","app.shopify.inventory.write-enabled","app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled"))
   if(!"false".equals(env.getProperty(key,"false")))throw new IllegalStateException("Sandbox label tests require external write guard OFF: "+key);
 };}
}
