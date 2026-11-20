package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AmazonOrderLeaseTest {
    @Test void expiredOrReplacedWorkerCannotImportAnOrderPage() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(c->((TransactionCallback<?>)c.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        var store=new AmazonSyncStore(jdbc,tx);
        var now=Instant.parse("2026-10-29T00:00:00Z");
        var job=new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            "ORDERS_API_DELTA","ORDER_CHANGES","ATVPDKIKX0DER",now.minusSeconds(300),now,null,0,0,false,null,"old-claim");
        @SuppressWarnings("unchecked") ToLongFunction<UUID> importer=mock(ToLongFunction.class);
        assertThatThrownBy(()->store.saveJsonPage(job,"page","{}",null,importer)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(importer);
    }
    @Test void currentOwnerCanRenewOnlyItsScopedClaim() {
        var jdbc=mock(JdbcTemplate.class);var tx=new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class));
        var store=new AmazonSyncStore(jdbc,tx);var job=orderJob();
        when(jdbc.queryForList(anyString(),eq(UUID.class),eq(job.tenantId()),eq(job.connectionId()),eq(job.runId()),eq(job.id()),eq(job.leaseOwner())))
            .thenReturn(java.util.List.of(job.id()));
        store.renewOrderLease(job);
        verify(jdbc).update(contains("lease_expires_at=clock_timestamp()"),eq(job.tenantId()),eq(job.connectionId()),eq(job.id()),eq(job.leaseOwner()));
    }
    @Test void rejectedClaimCannotRenewCompleteRetryOrFailAndRollsBack() {
        for(String action:java.util.List.of("renew","complete","retry","fail")){
            var jdbc=mock(JdbcTemplate.class);var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
            var status=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(status);
            var store=new AmazonSyncStore(jdbc,new TransactionTemplate(manager));var job=orderJob();
            assertThatThrownBy(()->{switch(action){
                case "renew"->store.renewOrderLease(job);
                case "complete"->store.complete(job,3);
                case "retry"->store.retryOrFail(job,new RuntimeException("fixture"),null);
                case "fail"->store.fail(job,new RuntimeException("fixture"));
            }}).isInstanceOf(AmazonSyncStore.LostOrderLease.class);
            verify(manager).rollback(status);verify(manager,never()).commit(any());
            assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i->i.getMethod().getName().equals("update"));
        }
    }
    private AmazonSyncStore.Job orderJob(){
        var now=Instant.parse("2026-10-29T00:00:00Z");
        return new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            "ORDERS_API_DELTA","ORDER_CHANGES","US",now.minusSeconds(300),now,null,0,0,true,null,"claim");
    }
}
