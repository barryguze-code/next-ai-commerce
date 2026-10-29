package com.nextaicommerce.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class FedExRegisteredCustomerConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(FedExRegisteredCustomerConfiguration.class);
    final String[] safe={"app.fedex.registered-customer-enabled=true","spring.profiles.active=fee-release-uat",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/fixture","app.fedex.platform-environment=SANDBOX","app.fedex.sandbox-rate-api=COMPREHENSIVE"};
    @Test void capabilityDefaultsOff(){runner.run(c->assertThat(c).doesNotHaveBean("protectRegisteredCustomerPreview"));}
    @Test void localGuardRejectsProductionRemoteStandardAndExternalWrites(){
        runner.withPropertyValues(safe).run(c->assertThat(c).hasNotFailed());
        for(String unsafe:java.util.List.of("spring.profiles.active=prod","spring.datasource.url=jdbc:postgresql://remote:5432/live",
            "app.fedex.platform-environment=PRODUCTION","app.fedex.sandbox-rate-api=STANDARD","app.amazon.write-enabled=true",
            "app.amazon.inventory-publication-enabled=true","app.fedex.confirmation-enabled=true","app.shopify.inventory.write-enabled=true",
            "app.shopify.prices.write-enabled=true","app.shopify.sale-prices.write-enabled=true"))
            runner.withPropertyValues(safe).withPropertyValues(unsafe).run(c->assertThat(c).hasFailed());
    }
}
