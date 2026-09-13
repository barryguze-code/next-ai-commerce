package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class AmazonPricingCooldownTest {
    @SuppressWarnings("unchecked")
    @Test void cooldownPreventsAmazonCallsAndSurvivesServiceRecreation(){
        var jdbc=mock(JdbcTemplate.class);var amazon=mock(AmazonSpApiClient.class);
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();
        when(jdbc.update(contains("interval '3 hours'"),eq(tenant),eq(connection))).thenReturn(1,0);
        var first=new AmazonCompetitivePricingService(jdbc,amazon,tx,new ObjectMapper());
        first.refresh(tenant,connection,"US");
        var restarted=new AmazonCompetitivePricingService(jdbc,amazon,tx,new ObjectMapper());
        restarted.refresh(tenant,connection,"US");
        verify(jdbc,times(1)).query(contains("SELECT seller_sku,asin"),any(RowMapper.class),eq(tenant),eq(connection));
        verifyNoInteractions(amazon);
    }

    @SuppressWarnings("unchecked")
    @Test void throttlingDefersRatherThanRetryingAndHonorsLongRetryAfter(){
        var jdbc=mock(JdbcTemplate.class);var amazon=mock(AmazonSpApiClient.class);
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        doAnswer(call->{((java.util.function.Consumer<TransactionStatus>)call.getArgument(0)).accept(mock(TransactionStatus.class));return null;})
            .when(tx).executeWithoutResult(any());
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();
        when(jdbc.update(contains("interval '3 hours'"),eq(tenant),eq(connection))).thenReturn(1);
        when(jdbc.query(contains("SELECT seller_sku,asin"),any(RowMapper.class),eq(tenant),eq(connection)))
            .thenReturn(List.of(new AmazonCompetitivePricingService.ListingKey("SKU","ASIN")));
        when(amazon.get(eq(tenant),eq(connection),anyString()))
            .thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofHours(4),"Slow down"));
        var service=new AmazonCompetitivePricingService(jdbc,amazon,tx,new ObjectMapper());
        assertThatThrownBy(()->service.refresh(tenant,connection,"US")).isInstanceOf(AmazonSpApiClient.AmazonApiException.class);
        verify(amazon,times(1)).get(eq(tenant),eq(connection),anyString());
        verify(jdbc).update(contains("GREATEST"),eq(14401L),eq(tenant),eq(connection));
        verifyNoMoreInteractions(amazon);
    }
}
