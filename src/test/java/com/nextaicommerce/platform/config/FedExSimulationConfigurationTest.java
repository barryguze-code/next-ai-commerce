package com.nextaicommerce.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class FedExSimulationConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(FedExSimulationConfiguration.class);
    final String[] safe={"app.fedex.simulation-enabled=true","spring.profiles.active=fee-release-uat","spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/fixture","app.fedex.external-enabled=false"};
    @Test void offByDefault(){runner.run(c->assertThat(c).doesNotHaveBean("protectShippingSimulation"));}
    @Test void rejectsLiveCarrierProductionRemoteAndEveryWriteGuard(){
        runner.withPropertyValues(safe).run(c->assertThat(c).hasNotFailed());
        for(String unsafe:java.util.List.of("spring.profiles.active=prod","spring.datasource.url=jdbc:postgresql://remote:5432/live","app.fedex.platform-environment=PRODUCTION",
            "app.fedex.external-enabled=true","app.fedex.confirmation-enabled=true","app.amazon.write-enabled=true","app.amazon.inventory-publication-enabled=true",
            "app.amazon.buy-shipping-enabled=true","app.shopify.inventory.write-enabled=true","app.shopify.prices.write-enabled=true","app.shopify.sale-prices.write-enabled=true"))
            runner.withPropertyValues(safe).withPropertyValues(unsafe).run(c->assertThat(c).hasFailed());
    }
}
