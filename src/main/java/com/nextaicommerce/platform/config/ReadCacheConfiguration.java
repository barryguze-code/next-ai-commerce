package com.nextaicommerce.platform.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

@Configuration(proxyBeanMethods=false)
public class ReadCacheConfiguration {
    @Bean static BeanPostProcessor invalidateReadModelsAfterCommit(ObjectProvider<PlatformReadCache> caches) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(bean instanceof ConfigurableTransactionManager manager)manager.addListener(new TransactionExecutionListener(){
                    @Override public void afterCommit(TransactionExecution transaction,Throwable failure){
                        // Covers controllers AND background workers, including imports, mappings and receiving.
                        // Deliberately conservative for one application instance; rollbacks keep the old model.
                        if(failure==null&&!transaction.isReadOnly())caches.ifAvailable(PlatformReadCache::invalidate);
                    }
                });
                return bean;
            }
        };
    }
}
