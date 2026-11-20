package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PlatformReadCacheTest {
    @Test void isolatesAccountsExpiresAndBoundsMemory(){
        var time=new AtomicLong();var cache=new PlatformReadCache(true,Duration.ofSeconds(10),2,time::get);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();var calls=new AtomicInteger();
        assertThat(cache.get(a,"catalogue","q",calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get(a,"catalogue","q",calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get(b,"catalogue","q",calls::incrementAndGet)).isEqualTo(2);
        time.set(Duration.ofSeconds(11).toNanos());
        assertThat(cache.get(a,"catalogue","q",calls::incrementAndGet)).isEqualTo(3);
        for(int i=0;i<50;i++)cache.get(a,"catalogue",i,()->"row");
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
        assertThat(cache.stats().hitCount()).isPositive();
    }
    @Test void failedLoadsAreNotCachedAndWriteTransactionsBypass(){
        var cache=new PlatformReadCache(true,Duration.ofSeconds(10),10);UUID tenant=UUID.randomUUID();
        assertThatThrownBy(()->cache.get(tenant,"test",1,()->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(tenant,"test",1,()->"old")).isEqualTo("old");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThat(cache.get(tenant,"test",1,()->"fresh")).isEqualTo("fresh");}
        finally{TransactionSynchronizationManager.clear();}
        cache.invalidate();assertThat(cache.get(tenant,"test",1,()->"new")).isEqualTo("new");
    }
    @Test void aLoadFinishingAfterACommitCannotRepublishOldData()throws Exception{
        var cache=new PlatformReadCache(true,Duration.ofSeconds(10),10);UUID tenant=UUID.randomUUID();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()){
            var old=executor.submit(()->cache.get(tenant,"catalogue",1,()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return "old";}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();cache.invalidate();release.countDown();old.get(5,TimeUnit.SECONDS);
            assertThat(cache.get(tenant,"catalogue",1,()->"new")).isEqualTo("new");
        }
    }
}
