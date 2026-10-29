package com.nextaicommerce.platform.config;

import com.nextaicommerce.platform.shopify.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopifyOrderSchedulingTest {
    private final String[] safe={"spring.profiles.active=local","spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/fixture","app.shopify.orders.only-scheduling=true","app.scheduling.enabled=false","spring.flyway.enabled=false","app.shopify.orders.enabled=true","app.shopify.orders.polling-enabled=true","app.amazon.sync-enabled=false","app.amazon.recurring-enabled=false","app.amazon.write-enabled=false","app.amazon.inventory-publication-enabled=false","app.amazon.listing-actions-enabled=false","app.mail.enabled=false"};
    private ApplicationContextRunner runner(){var scheduler=mock(TaskScheduler.class,inv->{if(ScheduledFuture.class.isAssignableFrom(inv.getMethod().getReturnType()))return mock(ScheduledFuture.class);return RETURNS_DEFAULTS.answer(inv);});return new ApplicationContextRunner().withUserConfiguration(ShopifyOrderSchedulingConfiguration.class).withBean(TaskScheduler.class,()->scheduler);}
    @Test void defaultOffDoesNotStartAnyTasks(){runner().run(c->assertThat(c).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));}
    @Test void onlyShopifyTickIsRegisteredAndNoWorkStartsDuringConfiguration(){
        var jobs=mock(ShopifyOrderJobs.class);var worker=mock(ShopifyOrderWorker.class);var connection=mock(com.nextaicommerce.platform.web.ShopifyConnectionService.class);
        runner().withPropertyValues(safe).run(c->{assertThat(c).hasNotFailed();var p=c.getBean(ScheduledAnnotationBeanPostProcessor.class);
            p.postProcessAfterInitialization(new ShopifyOrderPolling(jobs,worker,connection,true),"shopify");
            p.postProcessAfterInitialization(mock(com.nextaicommerce.platform.orders.OrderReconciliationScheduler.class),"amazon-orders");
            p.postProcessAfterInitialization(mock(com.nextaicommerce.platform.sync.AmazonVariationRelationshipSync.class),"amazon-relationships");
            assertThat(p.getScheduledTasks()).hasSize(1);assertThat(p.getScheduledTasks().iterator().next().getTask().getRunnable().toString()).contains("ShopifyOrderPolling.tick");verifyNoInteractions(jobs,worker,connection);
        });
    }
    @Test void unsafeGuardsOrProductionProfileFailClosed(){
        for(String property:List.of("app.amazon.write-enabled=true","app.amazon.inventory-publication-enabled=true","app.scheduling.enabled=true","spring.flyway.enabled=true","spring.profiles.active=production","spring.datasource.url=jdbc:postgresql://example.invalid:5432/production"))runner().withPropertyValues(safe).withPropertyValues(property).run(c->assertThat(c).hasFailed());
    }
    @Test void preservesExistingGuardedPreviewProfile(){runner().withPropertyValues(safe).withPropertyValues("spring.profiles.active=fee-release-uat").run(c->assertThat(c).hasNotFailed());}
}
