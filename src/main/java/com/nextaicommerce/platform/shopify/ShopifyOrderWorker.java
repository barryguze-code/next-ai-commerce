package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.time.*;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Explicit existing async executor; no startup enqueue, scheduler, Shopify mutation or hidden service. */
@Component
public class ShopifyOrderWorker {
    private final ShopifyOrderJobs jobs;private final ShopifyOrderClient client;
    private final java.util.Set<UUID> active=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public ShopifyOrderWorker(ShopifyOrderJobs jobs,ShopifyOrderClient client){this.jobs=jobs;this.client=client;}
    @Async public void start(UUID tenant,UUID connection,UUID id){
        if(!jobs.enabled()||!active.add(id))return;
        try{
        for(int step=0;step<2400&&!Thread.currentThread().isInterrupted();step++){
            var job=jobs.claim(tenant,connection,id);
            if(job==null){var status=jobs.status(tenant,connection);if(status==null||!id.equals(status.id())||!status.running())return;
                long pause=Math.max(100,Duration.between(Instant.now(),status.availableAt()).toMillis());
                try{Thread.sleep(Math.min(60000,pause));}catch(InterruptedException ex){Thread.currentThread().interrupt();return;}continue;
            }
            if(!process(job))return;
        }
        }finally{active.remove(id);}
    }
    boolean process(ShopifyOrderJobs.Job job){
        String raw=null;
        try{
            var response=client.fetch(job.tenant(),job.connection(),job.cursor(),job.start(),job.end(),()->jobs.reserve(job));raw=response.body();
            if(!job.revision().equals(response.connectionRevision()))throw new ShopifyOrderJobs.LostLease();
            return jobs.publish(job,raw,client.parse(response,job.cursor()));
        }catch(ShopifyOrderJobs.LostLease|org.springframework.security.access.AccessDeniedException|ShopifyConnectionService.AuthorizationRejected ex){jobs.pauseRevoked(job);return false;}
        catch(ShopifyConnectionService.RateLimited ex){try{return jobs.retry(job,ex.retryAt());}catch(ShopifyOrderJobs.LostLease|org.springframework.security.access.AccessDeniedException lost){return false;}}
        catch(IllegalStateException ex){try{return jobs.retry(job,Instant.now().plusSeconds(5));}catch(ShopifyOrderJobs.LostLease|org.springframework.security.access.AccessDeniedException lost){return false;}}
        catch(RuntimeException ex){try{jobs.fail(job,raw,ex instanceof IllegalArgumentException?ex.getMessage():"Shopify Orders import failed; prior records and allocations were retained.");}catch(ShopifyOrderJobs.LostLease|org.springframework.security.access.AccessDeniedException lost){/* revoked/cancelled workers cannot publish */}return false;}
    }
}
