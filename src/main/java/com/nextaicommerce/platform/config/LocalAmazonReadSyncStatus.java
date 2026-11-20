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
    private final boolean emailUatEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    LocalAmazonReadSyncStatus(@Value("${app.amazon.sync-enabled:true}") boolean syncEnabled,
            @Value("${app.amazon.recurring-enabled:true}") boolean recurringEnabled,
            @Value("${app.local-email-uat-enabled:false}") boolean emailUatEnabled) {
        this.syncEnabled=syncEnabled;
        this.recurringEnabled=recurringEnabled;
        this.emailUatEnabled=emailUatEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce(){
        log.info("Local UAT Amazon reads: sync={}, recurring={}. SP-API writes, listing actions, and Buy Shipping purchases remain blocked. Invitation email UAT is {}.",
            syncEnabled,recurringEnabled,emailUatEnabled?"enabled only for allow-listed recipients":"blocked");
        Integer credentials=jdbc.queryForObject("SELECT count(*) FROM marketplace_connection_credentials",Integer.class);
        if(credentials==null||credentials==0)log.warn("Local Amazon sync is blocked: no marketplace credentials were restored. Configure the local Amazon connections securely before syncing. No Amazon request was sent.");
    }
}
