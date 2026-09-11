package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class LocalEnvironmentSafetyTest {
    private MockEnvironment safe(){return new MockEnvironment()
        .withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_local")
        .withProperty("app.mail.provider","disabled")
        .withProperty("app.integrations.external-enabled","true").withProperty("app.scheduling.enabled","true")
        .withProperty("app.mail.enabled","false").withProperty("app.amazon.write-enabled","false")
        .withProperty("app.amazon.listing-actions-enabled","false");}
    @Test void localDatabaseAndDisabledIntegrationsAreRequired(){
        assertThatCode(()->LocalEnvironmentSafety.protectLocalEnvironment(safe()).postProcessBeanFactory(new DefaultListableBeanFactory())).doesNotThrowAnyException();
        for(String key:java.util.List.of("app.mail.enabled","app.amazon.write-enabled","app.amazon.listing-actions-enabled"))
            assertThatThrownBy(()->LocalEnvironmentSafety.protectLocalEnvironment(safe().withProperty(key,"true"))
                .postProcessBeanFactory(new DefaultListableBeanFactory())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalEnvironmentSafety.protectLocalEnvironment(safe().withProperty("app.integrations.external-enabled","false"))
            .postProcessBeanFactory(new DefaultListableBeanFactory())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalEnvironmentSafety.protectLocalEnvironment(safe().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:15432/next_ai_commerce"))
            .postProcessBeanFactory(new DefaultListableBeanFactory())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalEnvironmentSafety.protectLocalEnvironment(safe().withProperty("app.mail.provider","graph"))
            .postProcessBeanFactory(new DefaultListableBeanFactory())).isInstanceOf(IllegalStateException.class);
    }
}
