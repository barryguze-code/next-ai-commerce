package com.nextaicommerce.platform.config;

import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** Explicit local inbound Amazon tasks only; does not schedule Shopify UAT or publishers. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.amazon.local-read-only-scheduling",havingValue="true")
public class LocalAmazonReadSchedulingConfiguration {
    static boolean permitted(Class<?> type,String method){
        return type==com.nextaicommerce.platform.sync.AmazonSyncWorker.class
                &&java.util.Set.of("processNext","processOrderRefresh").contains(method)
            ||type==com.nextaicommerce.platform.sync.AmazonRecurringSyncScheduler.class&&method.equals("enqueueDueWork")
            ||type==com.nextaicommerce.platform.shipping.OrderRecipientWorker.class
                &&java.util.Set.of("fetch","expire").contains(method);
    }
    @Bean(name=TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ScheduledAnnotationBeanPostProcessor localAmazonReadScheduling(Environment env,ConfigurableListableBeanFactory beans){
        if(env.getActiveProfiles().length!=1||!env.acceptsProfiles(Profiles.of("local","fee-release-uat"))
            ||!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://127[.]0[.]0[.]1:55432/[a-z0-9_]+"))
            throw new IllegalStateException("Local Amazon read scheduling requires the approved loopback database/profile.");
        Map.ofEntries(Map.entry("app.scheduling.enabled","false"),Map.entry("spring.flyway.enabled","false"),
            Map.entry("app.integrations.external-enabled","true"),Map.entry("app.amazon.sync-enabled","true"),
            Map.entry("app.amazon.recurring-enabled","true"),Map.entry("app.amazon.recipient-import-enabled","true"),
            Map.entry("app.shopify.orders.only-scheduling","false"),Map.entry("app.shopify.orders.polling-enabled","false"),
            Map.entry("app.amazon.fee-pilot.only-scheduling","false"),Map.entry("app.amazon.variation.trial-only-scheduling","false"),
            Map.entry("app.amazon.write-enabled","false"),Map.entry("app.amazon.listing-actions-enabled","false"),
            Map.entry("app.amazon.inventory-publication-enabled","false"),Map.entry("app.amazon.shelf-sale-enabled","false"),
            Map.entry("app.amazon.buy-shipping-enabled","false"),Map.entry("app.fedex.confirmation-enabled","false"),
            Map.entry("app.shopify.inventory.write-enabled","false"),Map.entry("app.shopify.prices.write-enabled","false"),
            Map.entry("app.shopify.sale-prices.write-enabled","false"),Map.entry("app.mail.enabled","false"))
            .forEach((key,value)->{if(!value.equals(env.getProperty(key)))throw new IllegalStateException("Local Amazon read scheduling requires explicit guard: "+key);});
        return new ScheduledAnnotationBeanPostProcessor(){
            @Override protected void processScheduled(Scheduled scheduled,Method method,Object bean){
                if(method.getParameterCount()==0&&permitted(AopProxyUtils.ultimateTargetClass(bean),method.getName()))super.processScheduled(scheduled,method,bean);
            }
            @Override public void afterSingletonsInstantiated(){
                if(beans.getBeanNamesForType(SchedulingConfigurer.class,false,false).length!=0)throw new IllegalStateException("Local read scheduling excludes custom scheduling configurers.");
                super.afterSingletonsInstantiated();
            }
        };
    }
}
