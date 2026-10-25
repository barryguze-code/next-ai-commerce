package com.nextaicommerce.platform.sync;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One explicitly approved local fee job; never enqueues or claims general work. */
@Component
@ConditionalOnProperty(name="app.amazon.fee-pilot.enabled",havingValue="true")
public class AmazonFeePilotRunner {
    private static final Logger log=LoggerFactory.getLogger(AmazonFeePilotRunner.class);
    private final AmazonProductFeeJobs jobs;private final AmazonProductFeeSync sync;
    private final UUID tenant,connection,job;private final String marketplace,contractHash;
    private final String worker="fee-pilot-"+UUID.randomUUID();
    public AmazonFeePilotRunner(AmazonProductFeeJobs jobs,AmazonProductFeeSync sync,
            @Value("${app.amazon.fee-pilot.only-scheduling:false}") boolean selective,
            @Value("${app.amazon.fee-pilot.tenant:}") String tenant,
            @Value("${app.amazon.fee-pilot.connection:}") String connection,
            @Value("${app.amazon.fee-pilot.marketplace:}") String marketplace,
            @Value("${app.amazon.fee-pilot.job-id:}") String job,
            @Value("${app.amazon.fee-pilot.contract-sha256:}") String contractHash){
        if(!selective)throw new IllegalStateException("Fee pilot requires guarded selective scheduling");
        this.tenant=UUID.fromString(tenant);this.connection=UUID.fromString(connection);this.job=UUID.fromString(job);
        if(marketplace.isBlank()||marketplace.length()>30||!contractHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact approved fee pilot scope is required");
        this.jobs=jobs;this.sync=sync;this.marketplace=marketplace;this.contractHash=contractHash;
    }
    @Scheduled(fixedDelayString="${app.amazon.fee-pilot.delay-ms:5000}",initialDelay=5000)
    public void tick(){
        try{
            var claimed=jobs.claimPilot(tenant,connection,marketplace,job,contractHash,worker);
            if(claimed!=null&&jobs.matchesPilot(claimed,contractHash))sync.processPage(claimed);
        }catch(RuntimeException error){
            // Existing lease/budget state survives a worker failure; no generic retry or enqueue.
            log.warn("Fee-only worker stopped this tick ({}); existing lease and reservations retained",error.getClass().getSimpleName());
        }
    }
}
