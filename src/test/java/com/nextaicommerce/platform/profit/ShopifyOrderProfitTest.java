package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShopifyOrderProfitTest {
    final UUID item=UUID.randomUUID(),location=UUID.randomUUID();
    ShopifyOrderProfitRepository.Basis basis(String demand,String stock,UUID origin){return new ShopifyOrderProfitRepository.Basis(Map.of(item,new BigDecimal(demand)),Map.of(new ShopifyOrderProfitRepository.Identity(item,origin),new BigDecimal(stock)));}
    @Test void reuseRequiresWholeCompositionWithoutDependingOnReservations(){
        var a=basis("6","6",location);
        assertThat(a.matches(basis("6.0000","6.0",location))).isTrue();
        for(var other:List.of(basis("3","3",location)))assertThat(a.matches(other)).isFalse();
        assertThat(a.matches(basis("6","3",UUID.randomUUID()))).isTrue();
        assertThat(a.matches(new ShopifyOrderProfitRepository.Basis(Map.of(item,BigDecimal.valueOf(6)),Map.of()))).isTrue();
        var extra=new HashMap<>(a.items());extra.put(UUID.randomUUID(),BigDecimal.ONE);
        assertThat(a.matches(new ShopifyOrderProfitRepository.Basis(extra,a.origins()))).isFalse();
    }
    @Test void candidatesUseEveryOrderLineAndRejectIncompleteMappings(){
        UUID second=UUID.randomUUID();
        var rows=new ArrayList<Map<String,Object>>();
        rows.add(candidateRow("one","line1",item,2,3));
        rows.add(candidateRow("one","line2",second,1,2));
        var candidates=ShopifyOrderProfitRepository.packageCandidates(rows);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().basis().items()).containsEntry(item,new BigDecimal("6")).containsEntry(second,new BigDecimal("2"));
        assertThat(candidates.getFirst().basis().matches(new ShopifyOrderProfitRepository.Basis(Map.of(item,new BigDecimal("6")),Map.of()))).isFalse();
        var unmapped=candidateRow("one","line3",item,1,1);unmapped.remove("item");rows.add(unmapped);
        assertThat(ShopifyOrderProfitRepository.packageCandidates(rows)).isEmpty();
    }
    @Test void separateLinesOfSameProductAreSummedForPackageReuse(){
        var candidates=ShopifyOrderProfitRepository.packageCandidates(List.of(candidateRow("one","a",item,2,1),candidateRow("one","b",item,2,1)));
        assertThat(candidates.getFirst().basis().matches(new ShopifyOrderProfitRepository.Basis(Map.of(item,new BigDecimal("4")),Map.of()))).isTrue();
    }
    private Map<String,Object> candidateRow(String id,String line,UUID product,int quantity,int ordered){
        var row=new HashMap<String,Object>();row.put("kind","Amazon order");row.put("candidate_id",id);row.put("packaging","FedEx Pak");row.put("stamp","2026-11-15");row.put("line_id",line);row.put("item",product);row.put("quantity",BigDecimal.valueOf(quantity));row.put("quantity_ordered",ordered);return row;
    }
    @Test void partialCostsAreUnknownAndPaymentFeesNeverBecomeAmazonReferralRates(){
        var lines=List.of(new ProfitRepository.Line("A","Product",2,new BigDecimal("20"),new BigDecimal("3.25"),null,null));
        var result=ShopifyOrderProfitRepository.totals(lines,new BigDecimal("14.20"));
        assertThat(result.revenue()).isEqualByComparingTo("40");assertThat(result.productCost()).isEqualByComparingTo("6.50");assertThat(result.shippingCost()).isEqualByComparingTo("14.20");
        assertThat(result.referralFee()).isNull();assertThat(result.otherCost()).isNull();assertThat(result.profit()).isNull();
        var unknown=new ArrayList<>(lines);unknown.add(new ProfitRepository.Line("B","Missing cost",1,BigDecimal.ONE,null,null,null));
        assertThat(ShopifyOrderProfitRepository.totals(unknown,null).productCost()).isNull();
        assertThat(ShopifyOrderProfitRepository.totals(List.of(),null).revenue()).isNull();
    }
    @Test void ownerEstimateChargesOtherPerSoldSkuAndShippingOnce(){
        var lines=List.of(new ProfitRepository.Line("A","Bundle",2,new BigDecimal("20"),new BigDecimal("3.25"),ProfitEstimate.DEFAULT_OTHER_COST,null));
        var result=ShopifyOrderProfitRepository.totals(lines,new BigDecimal("14.20"));
        assertThat(result.otherCost()).isEqualByComparingTo("2.00");assertThat(result.profit()).isEqualByComparingTo("17.30");assertThat(result.referralFee()).isNull();
        var overridden=List.of(new ProfitRepository.Line("A","Bundle",2,new BigDecimal("20"),new BigDecimal("3.25"),BigDecimal.ZERO,null));
        assertThat(ShopifyOrderProfitRepository.totals(overridden,new BigDecimal("14.20")).profit()).isEqualByComparingTo("19.30");
        assertThat(ShopifyOrderProfitRepository.totals(lines,null).profit()).isNull();
    }

    @Test void storedOtherCostRespectsCurrencyAndExplicitZero(){
        assertThat(ShopifyOrderProfitRepository.otherCost("USD",null,"")).isEqualByComparingTo("1");
        assertThat(ShopifyOrderProfitRepository.otherCost("USD",BigDecimal.ZERO,"USD")).isEqualByComparingTo("0");
        assertThat(ShopifyOrderProfitRepository.otherCost("CAD",new BigDecimal("2.50"),"CAD")).isEqualByComparingTo("2.50");
        assertThat(ShopifyOrderProfitRepository.otherCost("CAD",null,"")).isNull();
        assertThat(ShopifyOrderProfitRepository.otherCost("CAD",BigDecimal.ONE,"USD")).isNull();
    }
    @Test void persistenceUsesExactTenantConnectionVariantAndCurrency(){
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var repo=new ShopifyOrderProfitRepository(jdbc,org.mockito.Mockito.mock(ProfitCostRepository.class));
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var variant=UUID.randomUUID();
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.contains("UPDATE shopify_variants"),org.mockito.ArgumentMatchers.eq(BigDecimal.ZERO),org.mockito.ArgumentMatchers.eq("USD"),org.mockito.ArgumentMatchers.eq("fixture"),org.mockito.ArgumentMatchers.eq(tenant),org.mockito.ArgumentMatchers.eq(connection),org.mockito.ArgumentMatchers.eq(variant),org.mockito.ArgumentMatchers.eq("USD"))).thenReturn(1);
        repo.saveOtherCost(tenant,connection,variant,BigDecimal.ZERO,"USD","fixture");
        assertThatThrownBy(()->repo.saveOtherCost(tenant,UUID.randomUUID(),variant,BigDecimal.ZERO,"USD","fixture")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repo.saveOtherCost(tenant,connection,variant,new BigDecimal("-1"),"USD","fixture")).isInstanceOf(IllegalArgumentException.class);
    }
}
