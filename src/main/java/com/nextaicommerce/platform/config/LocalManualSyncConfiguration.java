package com.nextaicommerce.platform.config;

import java.lang.reflect.Method;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only finite jobs explicitly initiated in this local process may make outbound reads. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.local.manual-sync-only",havingValue="true")
public class LocalManualSyncConfiguration {
    @Bean static BeanFactoryPostProcessor localManualGuards(Environment env){return beans->{
        if(!env.acceptsProfiles(Profiles.of("local","fee-release-uat"))||!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://127[.]0[.]0[.]1:55432/[a-z0-9_]+"))throw new IllegalStateException("Local manual mode requires the approved loopback datasource/profile.");
        for(String key:List.of("app.scheduling.enabled","app.amazon.recurring-enabled","app.amazon.startup-order-reconciliation-enabled","app.amazon.local-read-only-scheduling","app.shopify.orders.only-scheduling","app.shopify.orders.polling-enabled","app.amazon.fee-pilot.only-scheduling","app.amazon.variation.trial-only-scheduling","app.amazon.write-enabled","app.amazon.listing-actions-enabled","app.amazon.inventory-publication-enabled","app.amazon.shelf-sale-enabled","app.amazon.buy-shipping-enabled","app.fedex.confirmation-enabled","app.shopify.inventory.write-enabled","app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled","app.fba.shipment-write-enabled","app.fba.charge-write-enabled","app.fba.depletion.automatic-enabled","app.mail.enabled"))
            if(env.getProperty(key,Boolean.class,true))throw new IllegalStateException("Local manual mode requires explicit "+key+"=false.");
    };}
    static boolean permitted(Class<?> type,String method){return type==com.nextaicommerce.platform.sync.AmazonSyncWorker.class&&Set.of("processNext","processOrderRefresh").contains(method);}
    @Bean(name=TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ScheduledAnnotationBeanPostProcessor localManualWorkers(ConfigurableListableBeanFactory beans){return new ScheduledAnnotationBeanPostProcessor(){
        @Override protected void processScheduled(Scheduled scheduled,Method method,Object bean){if(method.getParameterCount()==0&&permitted(AopProxyUtils.ultimateTargetClass(bean),method.getName()))super.processScheduled(scheduled,method,bean);}
        @Override public void afterSingletonsInstantiated(){if(beans.getBeanNamesForType(SchedulingConfigurer.class,false,false).length!=0)throw new IllegalStateException("Local manual mode excludes custom schedulers.");super.afterSingletonsInstantiated();}
    };}
    @Bean(name={"applicationTaskExecutor","taskExecutor"})
    ThreadPoolTaskExecutor localManualExecutor(LocalIntegrationSafety safety){var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(4);executor.setQueueCapacity(20);executor.setThreadNamePrefix("local-user-request-");executor.setTaskDecorator(task->{boolean manual=safety.manualReadActive();return ()->{if(manual){try(var scope=safety.manualRequest()){task.run();}}else task.run();};});return executor;}
    static boolean explicitReadAction(String method,String path){
        if("POST".equals(method))return Set.of("/app/orders/sync","/app/marketplace-skus/sync","/app/marketplace-skus/shopify/sync","/app/marketplace-skus/fba-inventory","/app/inventory/fba-shipments/sync","/app/inventory/fba-shipments/sync-parents","/app/inventory/fba-shipments/depletion/refresh-items").contains(path)
            ||path.matches("/app/connections/[0-9a-f-]+/(credentials|shopify/(credentials|verify|permissions))")
            ||path.matches("/app/orders/[^/]+/buy-shipping/(rates|fedex/(rates|sandbox-rates))")
            ||path.matches("/app/catalog/products/[0-9a-f-]+/image/amazon");
        return "GET".equals(method)&&path.matches("/app/orders/[^/]+/buy-shipping(/fedex)?");
    }
    @Bean FilterRegistrationBean<OncePerRequestFilter> localManualRequests(LocalIntegrationSafety safety){
        var filter=new OncePerRequestFilter(){@Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,java.io.IOException{
            // External operations remain classified separately; granting a user request never permits writes.
            if(request.getUserPrincipal()!=null&&explicitReadAction(request.getMethod(),request.getRequestURI())){
                try(var scope=safety.manualRequest()){chain.doFilter(request,response);}
            }else chain.doFilter(request,response);
        }};
        var registration=new FilterRegistrationBean<OncePerRequestFilter>(filter);registration.setOrder(Ordered.LOWEST_PRECEDENCE);return registration;
    }
}
