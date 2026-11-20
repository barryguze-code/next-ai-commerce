package com.nextaicommerce.platform.config;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import com.nextaicommerce.platform.sync.AmazonVariationRelationshipSync;
import com.nextaicommerce.platform.sync.AmazonVariationRelationshipJobs;
import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipPublisher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VariationTrialSchedulingTest {
    private static final String[] SAFE={
        "spring.profiles.active=local","app.amazon.variation.trial-only-scheduling=true",
        "app.scheduling.enabled=false","spring.flyway.enabled=false",
        "app.amazon.variation.operator-enabled=true","app.amazon.variation.sync-enabled=true",
        "app.amazon.variation.inventory-enforcement-enabled=false",
        "app.amazon.sync-enabled=false","app.amazon.recurring-enabled=false",
        "app.amazon.startup-order-reconciliation-enabled=false",
        "app.amazon.catalog-barcodes-enabled=false","app.amazon.catalog-images-enabled=false",
        "app.amazon.inventory-publication-enabled=false","app.amazon.write-enabled=false",
        "app.amazon.listing-actions-enabled=false","app.amazon.recipient-import-enabled=false",
        "app.amazon.unresolved-order-checks-enabled=false","app.amazon.shelf-sale-enabled=false",
        "app.mail.enabled=false","app.mail.provider=disabled"};
    private ApplicationContextRunner runner(){
        TaskScheduler scheduler=mock(TaskScheduler.class,invocation->{
            if(ScheduledFuture.class.isAssignableFrom(invocation.getMethod().getReturnType()))
                return mock(ScheduledFuture.class);
            return RETURNS_DEFAULTS.answer(invocation);
        });
        return new ApplicationContextRunner().withUserConfiguration(VariationTrialSchedulingConfiguration.class)
            .withBean(TaskScheduler.class,()->scheduler);
    }
    @Test void defaultOffDoesNotRegisterAnyProcessor(){
        runner().run(context->assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
    @Test void normalSchedulingIsUnchangedWhenTrialModeIsAbsent(){
        runner().withUserConfiguration(SchedulingConfiguration.class)
            .withPropertyValues("app.scheduling.enabled=true")
            .withBean(NormalTask.class,NormalTask::new)
            .run(context->{assertThat(context).hasNotFailed();
                assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);});
    }
    static class NormalTask {@Scheduled(fixedDelay=60000,initialDelay=60000) void work(){}}

    @Test void onlyExistingVariationTickRegistersFromEntireScheduledBeanInventory() throws Exception {
        var context=runner().withPropertyValues(SAFE);Set<String> inventory=new TreeSet<>();
        Map<String,Object> scheduledBeans=new LinkedHashMap<>();
        try(var files=Files.walk(Path.of("src/main/java"))){
            for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
                String name=Path.of("src/main/java").relativize(file).toString().replace('/','.').replaceAll("\\.java$","");
                Class<?> type=Class.forName(name,false,getClass().getClassLoader());
                if(Arrays.stream(type.getDeclaredMethods()).noneMatch(m->m.isAnnotationPresent(Scheduled.class)))continue;
                inventory.add(name);scheduledBeans.put(name,mock(type));
            }
        }
        assertThat(inventory).contains(
            "com.nextaicommerce.platform.sync.AmazonSourceReprocessor",
            "com.nextaicommerce.platform.shipping.ShippingDeskWorker",
            "com.nextaicommerce.platform.orders.OrderReconciliationScheduler",
            "com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper",
            "com.nextaicommerce.platform.receiving.ReplenishmentSuggestions");
        var jobs=mock(AmazonVariationRelationshipJobs.class);
        scheduledBeans.put(AmazonVariationRelationshipSync.class.getName(),new AmazonVariationRelationshipSync(
            jobs,mock(AmazonVariationRelationshipPublisher.class),mock(AmazonSpApiClient.class),true));
        context.run(c->{assertThat(c).hasNotFailed();
            var processor=c.getBean(ScheduledAnnotationBeanPostProcessor.class);
            scheduledBeans.forEach((name,bean)->processor.postProcessAfterInitialization(bean,name));
            var tasks=processor.getScheduledTasks();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.iterator().next().getTask().getRunnable().toString()).contains("AmazonVariationRelationshipSync.tick");
            verifyNoInteractions(jobs);
            // Control: the same inventory really does register unrelated tasks without the filter.
            var unfiltered=new ScheduledAnnotationBeanPostProcessor();
            unfiltered.setScheduler(c.getBean(TaskScheduler.class));
            unfiltered.setEmbeddedValueResolver(c.getEnvironment()::resolveRequiredPlaceholders);
            try {
                scheduledBeans.forEach((name,bean)->unfiltered.postProcessAfterInitialization(bean,name));
                assertThat(unfiltered.getScheduledTasks().size()).isGreaterThan(1);
            } finally {unfiltered.destroy();}
        });
    }
    @Test void everyRequiredGuardMustBeExplicitAndCorrect(){
        for(String property:SAFE){
            if(property.startsWith("app.amazon.variation.trial-only-scheduling="))continue;
            String key=property.substring(0,property.indexOf('='));
            String wrong=property.endsWith("=false")?"true":property.endsWith("=true")?"false":"wrong";
            runner().withPropertyValues(SAFE).withPropertyValues(key+"="+wrong)
                .run(c->assertThat(c).hasFailed());
            String[] missing=Arrays.stream(SAFE).filter(v->!v.startsWith(key+"=")).toArray(String[]::new);
            runner().withPropertyValues(missing).run(c->assertThat(c).hasFailed());
        }
    }
    @Test void explicitTrialAndGlobalSchedulingTogetherFailClosed(){
        runner().withUserConfiguration(SchedulingConfiguration.class).withPropertyValues(SAFE)
            .withPropertyValues("app.scheduling.enabled=true").run(c->assertThat(c).hasFailed());
    }
    @Test void customConfigurerCannotAddUnrelatedTasks(){
        runner().withPropertyValues(SAFE).withBean(SchedulingConfigurer.class,()->registrar->{})
            .run(c->assertThat(c).hasFailed());
    }
    @Test void mixedLocalAndOtherProfileFailsClosed(){
        runner().withPropertyValues(SAFE).withPropertyValues("spring.profiles.active=local,production")
            .run(c->assertThat(c).hasFailed());
    }
}
