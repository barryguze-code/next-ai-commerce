package com.nextaicommerce.platform.shipping;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
public class OrderRecipientWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderRecipientWorker.class);
    private final JdbcTemplate jdbc;private final OrderRecipientService service;private final OrderRecipientRepository repository;private final boolean enabled;
    public OrderRecipientWorker(JdbcTemplate jdbc,OrderRecipientService service,OrderRecipientRepository repository,@org.springframework.beans.factory.annotation.Value("${app.amazon.recipient-import-enabled:false}") boolean enabled){this.jdbc=jdbc;this.service=service;this.repository=repository;this.enabled=enabled;}
    @Scheduled(fixedDelay=2100,initialDelay=10000)
    public void fetch(){if(!enabled)return;for(UUID tenant:tenants())try{service.process(tenant);}catch(RuntimeException ex){log.warn("Recipient worker deferred; no customer data recorded in logs.");}}
    @Scheduled(fixedDelay=60000,initialDelay=10000)
    public void expire(){for(UUID tenant:tenants())try{repository.purge(tenant);}catch(RuntimeException ex){log.warn("Recipient expiry requires operational attention.");}}
    private java.util.List<UUID> tenants(){return jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE'",(rs,n)->rs.getObject(1,UUID.class));}
}
