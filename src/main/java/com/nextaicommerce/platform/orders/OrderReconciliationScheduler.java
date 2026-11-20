package com.nextaicommerce.platform.orders;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrderReconciliationScheduler {
    private static final Logger log=LoggerFactory.getLogger(OrderReconciliationScheduler.class);
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;private final OrderRepository orders;
    public OrderReconciliationScheduler(JdbcTemplate jdbc,TransactionTemplate transactions,OrderRepository orders){
        this.jdbc=jdbc;this.transactions=transactions;this.orders=orders;
    }
    @Scheduled(initialDelayString="${app.orders.reconcile-initial-delay-ms:30000}",
        fixedDelayString="${app.orders.reconcile-delay-ms:60000}")
    public void reconcile(){
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(rs,row)->rs.getObject(1,UUID.class));
        for(UUID tenantId:tenants){
            try{transactions.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
                List<UUID> connections=jdbc.query("SELECT id FROM marketplace_connections WHERE tenant_id=? AND channel='AMAZON' AND status='ACTIVE'",(rs,row)->rs.getObject(1,UUID.class),tenantId);
                for(UUID connectionId:connections)orders.reconcile(tenantId,connectionId);
            });}catch(RuntimeException e){log.error("Live order reconciliation failed tenantId={}: {}",tenantId,e.getMessage(),e);}
        }
    }
}

