package com.nextaicommerce.platform.shopify;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShopifyStockAllocationTest {
    private final UUID tenant=UUID.randomUUID(),item=UUID.randomUUID(),main=UUID.randomUUID(),other=UUID.randomUUID();
    private final LocalDate boundary=LocalDate.of(2026,11,6);
    private ShopifyStockAllocation.Lot lot(long id,UUID location,boolean active,LocalDate expiry,int age,String available){
        return new ShopifyStockAllocation.Lot(tenant,item,new UUID(0,id),location,active,expiry,
            Instant.parse("2026-10-01T00:00:00Z").plusSeconds(age),new BigDecimal(available));
    }
    @Test void earlierExpiryAtAnotherLocationWinsOverReceiptAndDefault(){
        var old=lot(1,main,true,boundary.plusDays(20),0,"8");
        var earlier=lot(2,other,true,boundary.plusDays(1),100,"3");
        var p=ShopifyStockAllocation.reserve(tenant,item,new BigDecimal("5"),boundary,List.of(old,earlier));
        assertThat(p.picks()).extracting(ShopifyStockAllocation.Pick::layer).containsExactly(earlier.layer(),old.layer());
        assertThat(p.picks()).extracting(ShopifyStockAllocation.Pick::location).containsExactly(other,main);
        assertThat(p.picks()).extracting(ShopifyStockAllocation.Pick::quantity).containsExactly(new BigDecimal("3"),new BigDecimal("2"));
        assertThat(p.shortage()).isZero();
    }
    @Test void equalExpiryUsesOldestReceiptThenStableLayerIdentity(){
        var a=lot(2,main,true,boundary.plusDays(1),0,"1");
        var b=lot(1,other,true,boundary.plusDays(1),0,"1");
        var c=lot(3,main,true,boundary.plusDays(1),5,"1");
        assertThat(ShopifyStockAllocation.reserve(tenant,item,new BigDecimal("3"),boundary,List.of(c,a,b)).picks())
            .extracting(ShopifyStockAllocation.Pick::layer).containsExactly(b.layer(),a.layer(),c.layer());
    }
    @Test void undatedReceiptsUseFifoAfterDatedEligibleLots(){
        var a=lot(1,main,true,null,0,"1");
        var b=lot(2,other,true,null,5,"1");
        var c=lot(3,other,true,boundary.plusYears(1),10,"1");
        assertThat(ShopifyStockAllocation.reserve(tenant,item,new BigDecimal("3"),boundary,List.of(b,a,c)).picks())
            .extracting(ShopifyStockAllocation.Pick::layer).containsExactly(c.layer(),a.layer(),b.layer());
    }
    @Test void inactiveExpiredBoundaryAndUnavailableLotsDoNotReserve(){
        var eligible=lot(4,main,true,boundary.plusDays(1),0,"1.5");
        var lots=List.of(lot(1,main,false,null,0,"100"),lot(2,main,true,boundary,0,"100"),
            lot(3,other,true,boundary.minusDays(1),0,"100"),eligible,lot(5,other,true,null,0,"-1"));
        var p=ShopifyStockAllocation.reserve(tenant,item,new BigDecimal("3"),boundary,lots);
        assertThat(p.picks()).hasSize(1); assertThat(p.shortage()).isEqualByComparingTo("1.5");
        assertThat(eligible.available()).isEqualByComparingTo("1.5");
    }
    @Test void rejectsForeignAccountProductAndDuplicateLayers(){
        var own=lot(1,main,true,null,0,"4");
        assertThatThrownBy(()->ShopifyStockAllocation.reserve(UUID.randomUUID(),item,BigDecimal.ONE,boundary,List.of(own))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ShopifyStockAllocation.reserve(tenant,UUID.randomUUID(),BigDecimal.ONE,boundary,List.of(own))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ShopifyStockAllocation.reserve(tenant,item,BigDecimal.ONE,boundary,List.of(own,own))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zeroDemandAndShortageDoNotInventStock(){
        assertThat(ShopifyStockAllocation.reserve(tenant,item,BigDecimal.ZERO,boundary,List.of()).picks()).isEmpty();
        assertThat(ShopifyStockAllocation.reserve(tenant,item,BigDecimal.TEN,boundary,List.of()).shortage()).isEqualByComparingTo("10");
    }
}
