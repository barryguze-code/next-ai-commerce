package com.nextaicommerce.platform.sync;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps Buy Box prices warm without adding Amazon calls to Marketplace SKU or Orders requests. */
@Component
public class AmazonCompetitivePricingScheduler {
    private static final Logger log=LoggerFactory.getLogger(AmazonCompetitivePricingScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AmazonCompetitivePricingService pricing;
    private final boolean enabled;
    private final AtomicBoolean running=new AtomicBoolean();

    AmazonCompetitivePricingScheduler(JdbcTemplate jdbc,TransactionTemplate transactions,
            AmazonCompetitivePricingService pricing,@Value("${app.amazon.sync-enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.transactions=transactions;this.pricing=pricing;this.enabled=enabled;
    }

    @Scheduled(initialDelayString="${app.amazon.pricing-initial-delay-ms:300000}",
        fixedDelayString="${app.amazon.pricing-delay-ms:3600000}")
    public void refreshStalePrices(){
        if(!enabled||!running.compareAndSet(false,true))return;
        try{
            List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",
                (rs,row)->rs.getObject(1,UUID.class));
            for(UUID tenantId:tenants)for(Target target:targets(tenantId)){
                try{
                    long updated=pricing.refresh(tenantId,target.connectionId(),target.marketplaceId());
                    log.info("Buy Box refresh complete for {} — {} SKU price(s) updated.",target.storeName(),updated);
                }catch(RuntimeException ex){
                    log.warn("Buy Box refresh unavailable for {}: {}",target.storeName(),ex.getMessage());
                }
            }
        }finally{running.set(false);}
    }

    private List<Target> targets(UUID tenantId){
        List<Target> result=transactions.execute(status->{
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
            return jdbc.query("""
                SELECT connection.id,connection.marketplace_identifier,connection.display_name
                FROM marketplace_connections connection
                WHERE connection.tenant_id=? AND connection.channel='AMAZON' AND connection.status='ACTIVE'
                  AND (connection.buy_box_refresh_after IS NULL OR connection.buy_box_refresh_after<=now())
                  AND EXISTS (SELECT 1 FROM marketplace_connection_credentials credential
                    WHERE credential.tenant_id=connection.tenant_id
                      AND credential.marketplace_connection_id=connection.id)
                  AND EXISTS (SELECT 1 FROM amazon_listings listing
                    WHERE listing.tenant_id=connection.tenant_id
                      AND listing.marketplace_connection_id=connection.id
                      AND listing.platform_status NOT IN ('DELETED','REMOVED')
                      AND (listing.buy_box_updated_at IS NULL OR listing.buy_box_updated_at<now()-interval '3 hours'))
                ORDER BY connection.id
                """,(rs,row)->new Target(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),tenantId);
        });
        return result==null?List.of():result;
    }

    record Target(UUID connectionId,String marketplaceId,String storeName){}
}
