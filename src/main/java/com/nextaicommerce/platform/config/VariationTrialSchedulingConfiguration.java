package com.nextaicommerce.platform.config;

import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import com.nextaicommerce.platform.sync.AmazonVariationRelationshipSync;

/** Explicit local trial mode: the existing relationship tick is the only scheduled task. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.amazon.variation.trial-only-scheduling",havingValue="true")
public class VariationTrialSchedulingConfiguration {
    private static final Map<String,String> REQUIRED=Map.ofEntries(
        Map.entry("app.scheduling.enabled","false"),
        Map.entry("spring.flyway.enabled","false"),
        Map.entry("app.amazon.variation.operator-enabled","true"),
        Map.entry("app.amazon.variation.sync-enabled","true"),
        Map.entry("app.amazon.variation.inventory-enforcement-enabled","false"),
        Map.entry("app.amazon.sync-enabled","false"),
        Map.entry("app.amazon.recurring-enabled","false"),
        Map.entry("app.amazon.startup-order-reconciliation-enabled","false"),
        Map.entry("app.amazon.catalog-barcodes-enabled","false"),
        Map.entry("app.amazon.catalog-images-enabled","false"),
        Map.entry("app.amazon.inventory-publication-enabled","false"),
        Map.entry("app.amazon.write-enabled","false"),
        Map.entry("app.amazon.listing-actions-enabled","false"),
        Map.entry("app.amazon.recipient-import-enabled","false"),
        Map.entry("app.amazon.unresolved-order-checks-enabled","false"),
        Map.entry("app.amazon.shelf-sale-enabled","false"),
        Map.entry("app.mail.enabled","false"),
        Map.entry("app.mail.provider","disabled"));

    @Bean(name=TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ScheduledAnnotationBeanPostProcessor trialScheduling(Environment environment,
            ConfigurableListableBeanFactory beans) {
        if(!environment.acceptsProfiles(Profiles.of("local"))||environment.getActiveProfiles().length!=1)
            throw new IllegalStateException("Variation trial scheduling requires the local profile");
        REQUIRED.forEach((key,value)->{
            if(!value.equals(environment.getProperty(key)))
                throw new IllegalStateException("Variation trial scheduling requires explicit guard: "+key);
        });
        return new ScheduledAnnotationBeanPostProcessor() {
            @Override protected void processScheduled(Scheduled scheduled,Method method,Object bean) {
                if(AopProxyUtils.ultimateTargetClass(bean)==AmazonVariationRelationshipSync.class
                        &&method.getName().equals("tick")&&method.getParameterCount()==0)
                    super.processScheduled(scheduled,method,bean);
            }
            @Override public void afterSingletonsInstantiated() {
                // Configurers can register tasks outside annotation processing; fail closed.
                if(beans.getBeanNamesForType(SchedulingConfigurer.class,false,false).length!=0)
                    throw new IllegalStateException("Variation trial scheduling excludes custom scheduling configurers");
                super.afterSingletonsInstantiated();
            }
        };
    }
}
