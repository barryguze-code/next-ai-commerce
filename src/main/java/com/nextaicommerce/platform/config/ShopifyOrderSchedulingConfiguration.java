package com.nextaicommerce.platform.config;

import com.nextaicommerce.platform.shopify.ShopifyOrderPolling;
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

/** Existing selective scheduling pattern: local Shopify Orders polling cannot enable unrelated scheduled jobs. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.shopify.orders.only-scheduling",havingValue="true")
public class ShopifyOrderSchedulingConfiguration {
    @Bean(name=TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ScheduledAnnotationBeanPostProcessor shopifyOrderScheduling(Environment env,ConfigurableListableBeanFactory beans){
        if(!env.acceptsProfiles(Profiles.of("local","fee-release-uat"))||env.getActiveProfiles().length!=1
            ||!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://127[.]0[.]0[.]1:55432/[a-z0-9_]+"))
            throw new IllegalStateException("Shopify-only scheduling requires an approved local preview profile and loopback database.");
        Map.ofEntries(Map.entry("app.scheduling.enabled","false"),Map.entry("spring.flyway.enabled","false"),
            Map.entry("app.shopify.orders.enabled","true"),Map.entry("app.shopify.orders.polling-enabled","true"),
            Map.entry("app.amazon.sync-enabled","false"),Map.entry("app.amazon.recurring-enabled","false"),
            Map.entry("app.amazon.write-enabled","false"),Map.entry("app.amazon.inventory-publication-enabled","false"),
            Map.entry("app.amazon.listing-actions-enabled","false"),Map.entry("app.mail.enabled","false"))
            .forEach((key,value)->{if(!value.equals(env.getProperty(key)))throw new IllegalStateException("Shopify-only scheduling requires explicit guard: "+key);});
        return new ScheduledAnnotationBeanPostProcessor(){
            @Override protected void processScheduled(Scheduled scheduled,Method method,Object bean){
                var type=AopProxyUtils.ultimateTargetClass(bean);
                if((type==ShopifyOrderPolling.class||type==com.nextaicommerce.platform.sync.InventoryPublicationSimulation.class)&&method.getName().equals("tick")&&method.getParameterCount()==0)super.processScheduled(scheduled,method,bean);
            }
            @Override public void afterSingletonsInstantiated(){
                if(beans.getBeanNamesForType(SchedulingConfigurer.class,false,false).length!=0)throw new IllegalStateException("Shopify-only scheduling excludes custom scheduling configurers.");
                super.afterSingletonsInstantiated();
            }
        };
    }
}
