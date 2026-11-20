package com.nextaicommerce.platform.fba;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FbaShipmentCostsTest {
    private static FbaReadRepository.ShipmentCosts cost(String transport,String placement,String currency,long units){
        return new FbaReadRepository.ShipmentCosts(transport==null?null:new BigDecimal(transport),
            placement==null?null:new BigDecimal(placement),currency,units);
    }
    @Test void explicitZeroPlacementIsDisplayedAndIncludedInTotal(){
        var costs=cost("12.25","0","USD",5);
        assertThat(costs.displayAmount(costs.placement())).isEqualTo("$0.00");
        assertThat(costs.getTotal()).isEqualByComparingTo("12.25");
        assertThat(costs.getPerUnit()).isEqualByComparingTo("2.45");
    }
    @Test void absentPlacementRemainsUnknownInsteadOfFree(){
        var costs=cost("12.25",null,"USD",5);
        assertThat(costs.displayAmount(costs.placement())).isEqualTo("—");
        assertThat(costs.getTotal()).isNull();
        assertThat(costs.getPerUnit()).isNull();
    }
    @Test void parentRateUsesTotalCostDividedByAllUnitsRatherThanAverageRates(){
        var costs=FbaReadRepository.ShipmentCosts.aggregate(List.of(cost("10","0","USD",1),cost("9","1","USD",9)),2);
        assertThat(costs.getTotal()).isEqualByComparingTo("20");
        assertThat(costs.shippedUnits()).isEqualTo(10);
        assertThat(costs.getPerUnit()).isEqualByComparingTo("2");
    }
    @Test void incompleteParentOrMixedCurrencyCannotProduceCompleteFinancialTotals(){
        var usd=cost("10","0","USD",1);
        assertThat(FbaReadRepository.ShipmentCosts.aggregate(List.of(usd),2).getTotal()).isNull();
        assertThat(FbaReadRepository.ShipmentCosts.aggregate(List.of(usd,cost("9","1","CAD",9)),2).getTotal()).isNull();
        assertThat(FbaReadRepository.ShipmentCosts.aggregate(List.of(),0).getTotal()).isNull();
    }
    @Test void unknownChildCategoryDoesNotHideKnownTransportOrInventPlacement(){
        var costs=FbaReadRepository.ShipmentCosts.aggregate(List.of(cost("10","0","USD",1),cost("9",null,"USD",9)),2);
        assertThat(costs.transportation()).isEqualByComparingTo("19");
        assertThat(costs.placement()).isNull();
        assertThat(costs.getTotal()).isNull();
    }
    @Test void reversalsReduceCostsAndZeroUnitsNeverDivide(){
        var costs=FbaReadRepository.ShipmentCosts.aggregate(List.of(cost("10","2","USD",2),cost("-3","-1","USD",0)),2);
        assertThat(costs.getTotal()).isEqualByComparingTo("8");
        assertThat(costs.getPerUnit()).isEqualByComparingTo("4");
        assertThat(cost("0","0","USD",0).getPerUnit()).isNull();
    }
    @Test void invalidCurrencyCannotExposeUnqualifiedTotals(){
        assertThat(cost("10","0","",1).getTotal()).isNull();
        assertThat(cost("10","0","invalid",1).getTotal()).isNull();
    }
}
