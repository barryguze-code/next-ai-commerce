package com.nextaicommerce.platform.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Fail before the datasource/Flyway is created if a local launch inherits remote settings. */
@Configuration(proxyBeanMethods=false) @Profile("local")
public class LocalEnvironmentSafety {
    @Bean static BeanFactoryPostProcessor protectLocalEnvironment(Environment env){
        return factory->{
            if(!"jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_local".equals(env.getProperty("spring.datasource.url")))
                throw new IllegalStateException("Local mode requires the isolated local development database on port 55432.");
            boolean emailUat=env.getProperty("app.local-email-uat-enabled",Boolean.class,false);
            if(emailUat){
                if(!"graph".equals(env.getProperty("app.mail.provider"))||!env.getProperty("app.mail.enabled",Boolean.class,false)
                    ||env.getProperty("app.mail.local-uat-recipients","").isBlank())
                    throw new IllegalStateException("Local email UAT requires Microsoft Graph, enabled mail, and an allow-listed test recipient.");
            }else if(!"disabled".equals(env.getProperty("app.mail.provider")))
                throw new IllegalStateException("Local mode requires the disabled email provider unless local email UAT is explicitly enabled.");
            if(!env.getProperty("app.integrations.external-enabled",Boolean.class,false))
                throw new IllegalStateException("Local mode allows read-only Amazon access and requires external reads to be enabled.");
            for(String key:java.util.List.of("app.amazon.write-enabled","app.amazon.listing-actions-enabled"))
                if(env.getProperty(key,Boolean.class,true))throw new IllegalStateException("Local mode requires "+key+"=false.");
            if(!emailUat&&env.getProperty("app.mail.enabled",Boolean.class,true))
                throw new IllegalStateException("Local mode requires app.mail.enabled=false unless local email UAT is explicitly enabled.");
        };
    }
}
