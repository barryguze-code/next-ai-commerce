package com.nextaicommerce.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class FedExSandboxLabelTestConfigurationTest {
 private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(FedExSandboxLabelTestConfiguration.class);
 private final String[] safe={"app.fedex.sandbox-label-test-enabled=true","spring.profiles.active=fee-release-uat","spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/fixture","app.amazon.write-enabled=false","app.fedex.platform-environment=SANDBOX"};
 @Test void defaultOffDoesNotEnableShippedOrderTests(){runner.run(c->assertThat(c).doesNotHaveBean("protectSandboxLabelTest"));}
 @Test void localSandboxPassesAndUnsafeEnvironmentFailsBeforeRuntime(){
  runner.withPropertyValues(safe).run(c->assertThat(c).hasNotFailed());
  for(String unsafe:java.util.List.of("spring.profiles.active=production","spring.datasource.url=jdbc:postgresql://remote:5432/live","app.fedex.platform-environment=PRODUCTION","app.amazon.write-enabled=true","app.fedex.confirmation-enabled=true","app.shopify.inventory.write-enabled=true"))runner.withPropertyValues(safe).withPropertyValues(unsafe).run(c->assertThat(c).hasFailed());
 }
}
