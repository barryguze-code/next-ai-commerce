package com.nextaicommerce.platform.receiving;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.nextaicommerce.platform.orders.OrderRepository;

@Component
public class PhysicalCountWorker {
    private static final Logger log=LoggerFactory.getLogger(PhysicalCountWorker.class);
    private static final int MAX_LOCK_ATTEMPTS=180;
    private final PhysicalCountImportService counts;private final PhysicalCountProgress progress;private final OrderRepository orders;
    PhysicalCountWorker(PhysicalCountImportService counts,PhysicalCountProgress progress,OrderRepository orders){this.counts=counts;this.progress=progress;this.orders=orders;}
    @Async
    public void start(UUID tenantId,String actor,UUID importId,UUID vendorId,Map<String,String> mapping){
        try{
            progress.update(tenantId,importId,8,"Reading uploaded rows");
            applyWhenInventoryIsAvailable(tenantId,actor,importId,vendorId,mapping);
            progress.update(tenantId,importId,97,"Reallocating pending orders against counted stock");
            int shortages=orders.reconcileTenantAfterPhysicalCount(tenantId);
            progress.complete(tenantId,importId,shortages);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            progress.fail(tenantId,importId,"Processing was interrupted. Your staged count is safe; choose Validate and apply to resume.");
        }catch(Exception problem){progress.fail(tenantId,importId,problem.getMessage());log.error("Physical count background job failed importId={}",importId,problem);}
    }

    private void applyWhenInventoryIsAvailable(UUID tenantId,String actor,UUID importId,UUID vendorId,
            Map<String,String> mapping) throws InterruptedException {
        for(int attempt=1;attempt<=MAX_LOCK_ATTEMPTS;attempt++){
            try{
                boolean retry=attempt>1;
                counts.apply(tenantId,actor,importId,vendorId,mapping,(percent,phase)->{
                    if(!retry||percent>=68)progress.update(tenantId,importId,percent,phase);
                });
                return;
            }catch(PhysicalCountBusyException busy){
                progress.update(tenantId,importId,68,"Waiting for the current order sync to finish");
                if(attempt==MAX_LOCK_ATTEMPTS)throw new IllegalStateException(
                    "The inventory service stayed busy. Your staged count is safe; choose Validate and apply to resume.",busy);
                Thread.sleep(1_000);
            }
        }
    }
}
