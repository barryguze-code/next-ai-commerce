package com.nextaicommerce.platform.shipping;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ShippingDeskWorker {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;private final ShippingDeskRepository repository;private final ShippingDeskService service;
    public ShippingDeskWorker(JdbcTemplate jdbc,TransactionTemplate transactions,ShippingDeskRepository repository,ShippingDeskService service){
        this.jdbc=jdbc;this.transactions=transactions;this.repository=repository;this.service=service;}
    @Scheduled(fixedDelayString="${app.amazon.shipping-desk-worker-delay-ms:1000}",initialDelayString="${app.amazon.shipping-desk-worker-initial-delay-ms:6000}")
    public void work(){
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(rs,row)->rs.getObject(1,UUID.class));
        for(UUID tenant:tenants){
            transactions.executeWithoutResult(status->{setTenant(tenant);repository.recoverAbandonedRatings(tenant);});
            var command=transactions.execute(status->{setTenant(tenant);return repository.claimPending(tenant);});if(command!=null)service.rate(command);
        }
    }
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
}
