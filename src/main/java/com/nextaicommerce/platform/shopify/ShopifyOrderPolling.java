package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only previously enabled, scoped connections poll; failed/cancelled stores pause instead of retrying forever. */
@Component
public class ShopifyOrderPolling {
    private final ShopifyOrderJobs jobs;private final ShopifyOrderWorker worker;private final ShopifyConnectionService connections;private final boolean enabled;
    public ShopifyOrderPolling(ShopifyOrderJobs jobs,ShopifyOrderWorker worker,ShopifyConnectionService connections,@Value("${app.shopify.orders.polling-enabled:false}") boolean enabled){this.jobs=jobs;this.worker=worker;this.connections=connections;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${app.shopify.orders.poll-delay-ms:5000}",initialDelay=30000)
    public void tick(){
        if(!enabled||!jobs.enabled())return;
        for(var due:jobs.due())try{
            connections.renewOrdersAuthorization(due.tenant(),due.connection(),()->jobs.authorizeActor(due.tenant(),due.connection(),due.actor()));
            var id=jobs.enqueue(due.tenant(),due.connection(),due.actor());worker.start(due.tenant(),due.connection(),id);
        }catch(RuntimeException ex){jobs.pausePolling(due.tenant(),due.connection(),"Shopify Orders sync paused. Review the saved store authorization and retry.");}
    }
}
