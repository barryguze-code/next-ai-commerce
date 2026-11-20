package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.profit.ProfitRepository;
import com.nextaicommerce.platform.profit.ProfitEstimate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderProfitSummaryTest {
    private static ProfitRepository.View view(String id,String currency,BigDecimal amount){
        return new ProfitRepository.View(id,"ORDER",id,currency,LocalDate.now(),List.of(),List.of(),amount==null?null:new ProfitEstimate.Breakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,amount),"",null);
    }
    @Test void missingCostsAndUnsupportedCurrenciesAreNotZeroProfit(){
        var result=OrderProfitSummary.total(List.of("a","b","c","d"),Map.of("a",view("a","USD",new BigDecimal("12.34")),"b",view("b","USD",null),"c",view("c","CAD",BigDecimal.TEN)));
        assertEquals(new BigDecimal("12.34"),result.amount());assertEquals(1,result.estimated());assertEquals(3,result.pending());
    }
    @Test void batchesOverlappingScopesAndCachesRepeatedRequests(){
        var orders=mock(OrderRepository.class);var profit=mock(ProfitRepository.class);var tenant=UUID.randomUUID();var store=UUID.randomUUID();
        when(orders.profitOrderKeys(tenant,store,"ALL","",Map.of("f_date_preset","today"))).thenReturn(List.of("a"));
        when(orders.profitOrderKeys(tenant,store,"ALL","",Map.of())).thenReturn(List.of("a","b"));
        when(profit.orders(tenant,store,List.of("a","b"))).thenReturn(Map.of("a",view("a","USD",BigDecimal.TEN),"b",view("b","USD",new BigDecimal("-2"))));
        when(orders.profitOrderKeys(eq(tenant),eq(store),eq("ALL"),eq(""),argThat(filters->filters.containsKey("f_date_min")))).thenReturn(List.of("b"));
        when(orders.filteredSummary(eq(tenant),eq(store),eq("ALL"),eq(""),argThat(filters->filters.containsKey("f_date_min")))).thenReturn(new OrderRepository.FilteredSummary(2,5,List.of()));
        var refunds=mock(SkuRefundHistory.class);
        when(refunds.thirtyDays(tenant,store)).thenReturn(List.of(new SkuRefundHistory.RefundTotal("USD",new BigDecimal("43.97"),1,0,0)));
        var service=new OrderProfitSummary(orders,profit,refunds);var first=service.get(tenant,store,"ALL","",Map.of());
        assertEquals(new BigDecimal("-2"),first.thirtyDays().amount());assertEquals(1,first.refundsThirtyDays().getFirst().orders());
        assertEquals(2,first.volumeThirtyDays().orders());assertEquals(5,first.volumeThirtyDays().units());
        assertEquals(BigDecimal.TEN,first.today().amount());assertEquals(new BigDecimal("8"),first.filtered().amount());
        assertSame(first,service.get(tenant,store,"ALL","",Map.of()));verify(profit,times(1)).orders(tenant,store,List.of("a","b"));
        verify(refunds,times(1)).thirtyDays(tenant,store);
    }
}
