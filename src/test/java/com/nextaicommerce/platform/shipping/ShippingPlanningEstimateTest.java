package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.profit.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShippingPlanningEstimateTest {
    FedExShippingService service(){return new FedExShippingService(null,null,null,null,null,null,null,null,null,null,null);}
    @Test void localUpsUsesTheExistingAccountPlanningScheduleWithoutWritingOrQuoting(){
        var costs=mock(ProfitCostRepository.class);UUID tenant=UUID.randomUUID();when(costs.rates(tenant)).thenReturn(ProfitShippingRates.defaults());var service=service();ReflectionTestUtils.setField(service,"simulation",true);ReflectionTestUtils.setField(service,"planningCosts",costs);
        FedExShippingService.PlanningEstimate estimate=ReflectionTestUtils.invokeMethod(service,"upsEstimate",tenant);
        assertThat(estimate.carrier()).isEqualTo("UPS");assertThat(estimate.amount()).isEqualByComparingTo("14.20");assertThat(estimate.currency()).isEqualTo("USD");verify(costs).rates(tenant);verifyNoMoreInteractions(costs);
        String json=new tools.jackson.databind.ObjectMapper().writeValueAsString(estimate);assertThat(json).doesNotContain("quoteId","shipmentId","serviceType","tracking");
    }
    @Test void userConfiguredRateIsRespectedAndMissingRateIsNotInvented(){
        var costs=mock(ProfitCostRepository.class);UUID tenant=UUID.randomUUID();var service=service();ReflectionTestUtils.setField(service,"simulation",true);ReflectionTestUtils.setField(service,"planningCosts",costs);
        when(costs.rates(tenant)).thenReturn(List.of(new ProfitShippingRates.Rate(ProfitShippingRates.PackageType.UPS,LocalDate.of(2020,1,1),new BigDecimal("16.25"))));
        FedExShippingService.PlanningEstimate estimate=ReflectionTestUtils.invokeMethod(service,"upsEstimate",tenant);assertThat(estimate.amount()).isEqualByComparingTo("16.25");
        when(costs.rates(tenant)).thenReturn(List.of());assertThat((Object)ReflectionTestUtils.invokeMethod(service,"upsEstimate",tenant)).isNull();
    }
    @Test void normalRuntimeDoesNotExposeTheLocalPlanningCard(){
        var costs=mock(ProfitCostRepository.class);var service=service();ReflectionTestUtils.setField(service,"planningCosts",costs);
        assertThat((Object)ReflectionTestUtils.invokeMethod(service,"upsEstimate",UUID.randomUUID())).isNull();verifyNoInteractions(costs);
    }
}
