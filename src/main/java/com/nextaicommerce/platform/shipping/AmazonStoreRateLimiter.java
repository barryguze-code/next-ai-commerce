package com.nextaicommerce.platform.shipping;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Small per-store pacing guard; Amazon response limits still remain authoritative. */
@Component
public class AmazonStoreRateLimiter {
    private final ConcurrentHashMap<Key,AtomicLong> nextSlot=new ConcurrentHashMap<>();
    public void rates(UUID tenantId,UUID connectionId){acquire(new Key(tenantId,connectionId,"rates"),170_000_000L);}
    public void purchase(UUID tenantId,UUID connectionId){acquire(new Key(tenantId,connectionId,"purchase"),500_000_000L);}
    public void refund(UUID tenantId,UUID connectionId){acquire(new Key(tenantId,connectionId,"refund"),1_000_000_000L);}
    private void acquire(Key key,long interval){
        AtomicLong slot=nextSlot.computeIfAbsent(key,ignored->new AtomicLong());long now=System.nanoTime(),reserved;
        while(true){long current=slot.get(),start=Math.max(now,current);if(slot.compareAndSet(current,start+interval)){reserved=start;break;}}
        long wait=reserved-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);
    }
    private record Key(UUID tenantId,UUID connectionId,String operation){}
}
