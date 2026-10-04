package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfitEstimateTest {
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static ProfitEstimate.Line line(String price,int quantity) {
        return new ProfitEstimate.Line(n(price),quantity,n("4"),ProfitEstimate.DEFAULT_OTHER_COST,BigDecimal.ZERO);
    }
    @Test void feeBoundaryUsesWholeUnitSellingPrice() {
        assertThat(ProfitEstimate.referralRate(n("15"))).isEqualByComparingTo("0.08");
        assertThat(ProfitEstimate.referralRate(n("15.01"))).isEqualByComparingTo("0.15");
        assertThat(ProfitEstimate.calculate(List.of(line("15.01",1)),List.of(n("9.99"))).referralFee()).isEqualByComparingTo("2.25");
    }
    @Test void threeSkuUnitsShareOnePackageWithoutMultiplyingShipping() {
        var result=ProfitEstimate.calculate(List.of(line("15",3)),List.of(n("9.99")));
        assertThat(result.referralFee()).isEqualByComparingTo("3.60");
        assertThat(result.otherCost()).isEqualByComparingTo("3.00");
        assertThat(result.profit()).isEqualByComparingTo("16.41");
    }
    @Test void mixedOrderUsesEachSkusFeeBandAndAllPackages() {
        var result=ProfitEstimate.calculate(List.of(line("15",1),line("20",1)),List.of(n("9.99"),n("14.20")));
        assertThat(result.referralFee()).isEqualByComparingTo("4.20");
        assertThat(result.shippingCost()).isEqualByComparingTo("24.19");
        assertThat(result.profit()).isEqualByComparingTo("-3.39");
    }
    @Test void missingCostOrPackageIsNotZeroProfitExpense() {
        assertThat(ProfitEstimate.calculate(List.of(line("20",1)),List.of()).complete()).isFalse();
        var unknown=new ProfitEstimate.Line(n("20"),1,null,n("1"),BigDecimal.ZERO);
        assertThat(ProfitEstimate.calculate(List.of(unknown),List.of(n("9.99"))).profit()).isNull();
    }
    @Test void rejectsInvalidAmountsAndQuantities() {
        assertThatThrownBy(() -> line("20",0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("-1",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfitEstimate.calculate(List.of(line("20",1)),List.of(n("-1")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void laterRateDoesNotRewriteEarlierShippingEstimate() {
        var rates=new ArrayList<>(ProfitShippingRates.defaults());
        rates.add(new ProfitShippingRates.Rate(ProfitShippingRates.PackageType.FEDEX_SMALL,LocalDate.of(2026,11,1),n("15.00")));
        assertThat(ProfitShippingRates.cost(rates,ProfitShippingRates.PackageType.FEDEX_SMALL,LocalDate.of(2025,1,1))).isEqualByComparingTo("13.86");
        assertThat(ProfitShippingRates.cost(rates,ProfitShippingRates.PackageType.FEDEX_SMALL,LocalDate.of(2026,11,1))).isEqualByComparingTo("15.00");
        assertThat(ProfitShippingRates.cost(rates,ProfitShippingRates.PackageType.UPS,LocalDate.of(2024,12,31))).isNull();
    }
}
