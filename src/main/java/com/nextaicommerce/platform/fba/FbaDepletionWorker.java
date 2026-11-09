package com.nextaicommerce.platform.fba;

import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Each database job contains at most 25 attempts. A completed import drives the backlog. */
@Component
public class FbaDepletionWorker {
    private final FbaAutomaticDepletion automatic;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<FbaDepletionWorker> continuation;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FbaDepletionWorker.class);
    private void continueLater(UUID tenant,UUID connection){
        if(continuation==null)return; // Direct isolated fixtures; durable PENDING remains for the next notification.
        try{continuation.getObject().resumePending(tenant,connection);}
        catch(org.springframework.core.task.TaskRejectedException rejected){log.warn("FBA depletion continuation deferred; durable pending work retained for connection={}",connection);}
    }

    public FbaDepletionWorker(FbaAutomaticDepletion automatic) {
        this.automatic = automatic;
    }

    @Async
    public void afterCompletedImport(UUID tenant, UUID connection, UUID importJob) {
        if (!automatic.enabled()) return;
        drain(tenant,connection,automatic.enqueue(tenant, connection, importJob));
    }

    @Async
    public void resumePending(UUID tenant,UUID connection){
        if(!automatic.enabled())return;
        drain(tenant,connection,automatic.nextBatch(tenant,connection));
    }

    private void drain(UUID tenant,UUID connection,UUID job){
        long started=System.nanoTime();int batches=0;
        while (job != null && !Thread.currentThread().isInterrupted()) {
            String owner = automatic.claim(tenant, connection, job);
            if (owner == null) return; // The current owner drains pending imports after its batch.
            try {
                for (int i = 0; i <= 25 && !Thread.currentThread().isInterrupted(); i++) {
                    if (!automatic.processOne(tenant, connection, job, owner)) break;
                }
            } catch (RuntimeException error) {
                automatic.failed(tenant, connection, job, owner);
            }
            if (Thread.currentThread().isInterrupted()) return;
            if(++batches>=8||System.nanoTime()-started>java.time.Duration.ofSeconds(30).toNanos()){
                continueLater(tenant,connection);return;
            }
            job = automatic.nextBatch(tenant, connection);
        }
        if(!Thread.currentThread().isInterrupted()&&automatic.pending(tenant,connection))continueLater(tenant,connection);
    }
}
