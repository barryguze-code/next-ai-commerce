package com.nextaicommerce.platform.shipping;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class BuyShippingActivationTest {
 @Test void inventoryWritesDoNotActivateLabelPurchasesByDefault(){
  new ApplicationContextRunner().withUserConfiguration(BuyShippingWorker.class)
   .withPropertyValues("app.amazon.write-enabled=true")
   .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(BuyShippingWorker.class));
 }
 @Test void explicitlyDisabledLabelPurchasesStayDisabled(){
  new ApplicationContextRunner().withUserConfiguration(BuyShippingWorker.class)
   .withPropertyValues("app.amazon.write-enabled=true","app.amazon.buy-shipping-enabled=false")
   .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(BuyShippingWorker.class));
 }
 @Test void disabledAmazonWritesStillDisableLabelPurchases(){
  new ApplicationContextRunner().withUserConfiguration(BuyShippingWorker.class)
   .withPropertyValues("app.amazon.write-enabled=false","app.amazon.buy-shipping-enabled=true")
   .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(BuyShippingWorker.class));
 }
}
