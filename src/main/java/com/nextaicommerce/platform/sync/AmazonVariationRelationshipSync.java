package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipPublisher;

/** Opt-in, bounded Listings Items reads over the existing queue. Never enqueues on startup. */
@Component
public class AmazonVariationRelationshipSync {
    private final AmazonVariationRelationshipJobs jobs;
    private final AmazonVariationRelationshipPublisher publisher;
    private final AmazonSpApiClient amazon;
    private final boolean enabled;
    public AmazonVariationRelationshipSync(AmazonVariationRelationshipJobs jobs,AmazonVariationRelationshipPublisher publisher,
            AmazonSpApiClient amazon,@Value("${app.amazon.variation.sync-enabled:false}") boolean enabled){
        this.jobs=jobs;this.publisher=publisher;this.amazon=amazon;this.enabled=enabled;
    }
    public UUID startTrial(UUID key){if(!enabled)throw new IllegalStateException("Acquisition is disabled");return jobs.startTrial(key);}
    public UUID startCoverage(UUID key){if(!enabled)throw new IllegalStateException("Acquisition is disabled");return jobs.startCoverage(key);}
    public UUID enqueue(Scope scope,Collection<String> exactSkus){return enabled?jobs.enqueue(scope,exactSkus):null;}
    @Scheduled(initialDelayString="${app.amazon.variation.initial-delay-ms:30000}",fixedDelayString="${app.amazon.variation.delay-ms:1000}")
    public void tick(){if(enabled){var lease=jobs.claim("variation");if(lease!=null)process(lease);}}
    public void process(AmazonVariationRelationshipJobs.Lease lease){
        if(!enabled||!Boolean.TRUE.equals(jobs.owned(lease,j->true)))return;
        String sku=lease.cursor().path("pending").path(0).asText();
        try{
            String endpoint=endpoint(lease.job().marketplaceId());
            if(lease.seller()==null||lease.seller().isEmpty()||sku.isEmpty())throw new IllegalArgumentException("Missing exact listing identity");
            String path="/listings/2021-08-01/items/"+encode(lease.seller())+"/"+encode(sku)
                +"?marketplaceIds="+encode(lease.job().marketplaceId())+"&includedData=summaries,attributes,relationships";
            UUID request=UUID.randomUUID();
            var response=amazon.getVariationAt(lease.job().tenantId(),lease.job().connectionId(),endpoint,path,new AmazonSpApiClient.VariationPermit(){
                public boolean getAsBoolean(){return jobs.reserve(lease,request);}
                public boolean oauth(){return jobs.reserveOAuth(lease,request);}
                public java.time.Duration remaining(){return jobs.trialRemaining(lease);}
                public void completed(){jobs.requestCompleted(lease,request);}
                public void uncertain(){jobs.requestUncertain(lease,request);}
            });
            jobs.owned(lease,j->{if(!jobs.familyAllowed(lease,sku,response.json())){
                    publisher.rejectFamily(lease.job(),sku,response.body(),response.requestId(),jobs.now());jobs.stopFamily(lease);return true;
                }
                var references=publisher.publish(lease.job(),sku,response.body(),response.requestId(),jobs.now());
                jobs.observeRate(response.rateLimit());jobs.advanceInside(lease,references);return true;});
        }catch(AmazonSpApiClient.VariationDeferredException deferred){
            // Reservation already persisted a future due time, or the lease is no longer owned.
        }catch(AmazonSpApiClient.AmazonApiException ex){
            jobs.owned(lease,j->{if(ex instanceof AmazonSpApiClient.VariationReadException read){publisher.archiveFailure(lease.job(),sku,read);jobs.observeRate(read.rateLimit());}publisher.failure(lease.job(),sku,ex.status(),jobs.now());
                if(ex.status()==404)jobs.advanceInside(lease,List.of());else jobs.retryInside(lease,ex.status(),ex.retryAfter());return true;});
        }catch(RuntimeException ex){
            // No response bodies, seller identity or credentials are written to logs/error text.
            jobs.owned(lease,j->{publisher.failure(lease.job(),sku,0,jobs.now());jobs.retryInside(lease,0,null);return true;});
        }
    }
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    static String endpoint(String market){return AmazonSpApiClient.endpoint(market); }
}
