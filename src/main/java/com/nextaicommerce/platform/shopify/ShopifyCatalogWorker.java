package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.marketplace.ShopifySkuRepository;
import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.time.*;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Explicitly started through the existing application async executor; never auto-enqueued at startup. */
@Component
public class ShopifyCatalogWorker {
    private final ShopifyCatalogJobs jobs;private final ShopifyCatalogClient client;private final ShopifySkuRepository skus;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ShopifyOrderRepository orders;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ShopifyOrderPage orderPage;
    public ShopifyCatalogWorker(ShopifyCatalogJobs jobs,ShopifyCatalogClient client,ShopifySkuRepository skus){this.jobs=jobs;this.client=client;this.skus=skus;}
    @Async public void start(UUID tenant,UUID connection,UUID id){
        if(!jobs.enabled())return;
        for(int step=0;step<2400&&!Thread.currentThread().isInterrupted();step++){
            var job=jobs.claim(tenant,connection,id);
            if(job==null){var state=jobs.status(tenant,connection);if(state==null||!state.id().equals(id)||!state.running()||!"WAITING".equals(state.state()))return;
                long delay=Math.max(100,Duration.between(Instant.now(),state.availableAt()).toMillis());
                try{Thread.sleep(Math.min(60000,delay));}catch(InterruptedException e){Thread.currentThread().interrupt();return;}continue;
            }
            if(!process(job))return;
        }
    }
    boolean process(ShopifyCatalogJobs.Job job){
        String raw=null;
        try{
            var response=client.fetch(job.tenant(),job.connection(),job.cursor(),()->jobs.reserve(job));raw=response.body();
            if(!job.revision().equals(response.connectionRevision()))throw new ShopifyCatalogJobs.LostLease();
            var page=client.parse(response,job.cursor());
            jobs.publish(job,raw,page,response.currency(),()->skus.autoMap(job.tenant(),job.connection(),job.run()));
            if(!page.more())try{if(orders!=null)orders.catalogueReady(job.tenant(),job.connection());if(orderPage!=null)orderPage.initialize(job.tenant(),job.connection(),job.state().get("actor").toString());}catch(RuntimeException ignored){/* Catalogue completion remains valid; Orders shows its own permission/job state. */}
            return page.more();
        }catch(ShopifyCatalogJobs.LostLease e){return false;}
        catch(org.springframework.security.access.AccessDeniedException e){jobs.pauseRevoked(job);return false;}
        catch(ShopifyConnectionService.RateLimited e){try{jobs.retry(job,e.retryAt());return true;}catch(ShopifyCatalogJobs.LostLease|org.springframework.security.access.AccessDeniedException lost){return false;}}
        catch(IllegalStateException e){try{jobs.retry(job,Instant.now().plusSeconds(5));return true;}catch(ShopifyCatalogJobs.LostLease|org.springframework.security.access.AccessDeniedException lost){return false;}}
        catch(RuntimeException e){
            String message=e instanceof IllegalArgumentException?e.getMessage():"Shopify catalogue import failed. Existing records and mappings were retained.";
            try{jobs.fail(job,raw,message);}catch(ShopifyCatalogJobs.LostLease|org.springframework.security.access.AccessDeniedException lost){/* cancelled/revoked workers cannot publish */}return false;
        }
    }
}
