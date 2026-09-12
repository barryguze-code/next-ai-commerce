package com.nextaicommerce.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Makes Local UAT's Amazon boundary visible in the Eclipse console at every startup. */
@Component
@Profile("local")
public class LocalAmazonReadSyncStatus {
    private static final Logger log=LoggerFactory.getLogger(LocalAmazonReadSyncStatus.class);
    private final boolean syncEnabled;
    private final boolean recurringEnabled;

    LocalAmazonReadSyncStatus(@Value("${app.amazon.sync-enabled:true}") boolean syncEnabled,
            @Value("${app.amazon.recurring-enabled:true}") boolean recurringEnabled) {
        this.syncEnabled=syncEnabled;
        this.recurringEnabled=recurringEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce(){
        log.info("Local UAT Amazon reads: sync={}, recurring={}. SP-API writes, listing actions, mail, and Buy Shipping purchases remain blocked.",
            syncEnabled,recurringEnabled);
    }
}
