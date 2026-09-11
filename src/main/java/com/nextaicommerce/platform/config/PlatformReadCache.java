package com.nextaicommerce.platform.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded, disposable display-model cache. Never a source for inventory or authorization decisions. */
@Component
public class PlatformReadCache {
    private record Key(UUID tenant,String area,Object query,long generation) {}
    private final Cache<Key,Object> cache;
    private final AtomicLong generation=new AtomicLong();
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformReadCache(@Value("${app.read-cache.enabled:true}") boolean enabled,
            @Value("${app.read-cache.ttl:10s}") Duration ttl,
            @Value("${app.read-cache.maximum-entries:256}") long maximumEntries) {
        this(enabled,ttl,maximumEntries,Ticker.systemTicker());
    }
    PlatformReadCache(boolean enabled,Duration ttl,long maximumEntries,Ticker ticker) {
        this.enabled=enabled;
        cache=Caffeine.newBuilder().maximumSize(Math.max(1,Math.min(2048,maximumEntries)))
            .expireAfterWrite(ttl).ticker(ticker).recordStats().build();
    }
    @SuppressWarnings("unchecked")
    public <T> T get(UUID tenant,String area,Object query,Supplier<T> loader) {
        Objects.requireNonNull(tenant,"A cache key must include the account");
        // Never return an older read model inside a transaction that may validate or change stock.
        if(!enabled||(TransactionSynchronizationManager.isActualTransactionActive()
                &&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()))return loader.get();
        return (T)cache.get(new Key(tenant,area,query,generation.get()),ignored->loader.get());
    }
    public void invalidate() {
        // A load started before a commit may finish after invalidation. Its generation is never reused.
        // Do not wait on an in-flight Caffeine loader while committing a stock change.
        // Old generations are unreachable immediately and removed by the size/TTL bounds.
        generation.incrementAndGet();
    }
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats(){return cache.stats();}
    public long estimatedSize(){cache.cleanUp();return cache.estimatedSize();}
}
