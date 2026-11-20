package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ColdChainShippingPolicyTest {
    private static final ZoneId PACIFIC=ZoneId.of("America/Los_Angeles");
    private static final ColdChainShippingPolicy.Policy POLICY=new ColdChainShippingPolicy.Policy(0,2,LocalTime.of(11,0),
        List.of("SUREPOST"),new BigDecimal("1.00"),true,true);

    @Test void unpaidColdOrderOnThursdayWaitsForMonday(){
        var result=ColdChainShippingPolicy.schedule(POLICY,PACIFIC,"FROZEN","Standard",BigDecimal.ZERO,
            Instant.parse("2026-09-03T17:00:00Z"));
        assertThat(result.handoffDate()).isEqualTo(LocalDate.of(2026,9,7));
        assertThat(result.extraIce()).isFalse();
    }

    @Test void paidExpeditedColdOrderOnThursdayHandsOffFridayWithExtraIce(){
        var result=ColdChainShippingPolicy.schedule(POLICY,PACIFIC,"REFRIGERATED","Two-Day",new BigDecimal("12.00"),
            Instant.parse("2026-09-03T17:00:00Z"));
        assertThat(result.handoffDate()).isEqualTo(LocalDate.of(2026,9,4));
        assertThat(result.extraIce()).isTrue();
        assertThat(result.expedited()).isTrue();
    }

    @Test void surePostIsRejectedAndUpsGroundWinsWithinOneDollar(){
        LocalDate handoff=LocalDate.of(2026,9,8);
        var surepost=rate("sure","UPS","SurePost",new BigDecimal("6.00"),"2026-09-10T18:00:00Z");
        var cheapest=rate("postal","USPS","Priority",new BigDecimal("7.00"),"2026-09-10T18:00:00Z");
        var ground=rate("ground","UPS","Ground",new BigDecimal("7.75"),"2026-09-10T18:00:00Z");
        var choice=ColdChainShippingPolicy.choose(POLICY,PACIFIC,"FROZEN",handoff,List.of(surepost,cheapest,ground));
        assertThat(choice.offer().serviceId()).isEqualTo("ground");
    }

    @Test void coldChainRejectsServicesBeyondTwoBusinessDays(){
        var slow=rate("slow","FedEx","Ground",new BigDecimal("5.00"),"2026-09-11T18:00:00Z");
        assertThat(ColdChainShippingPolicy.choose(POLICY,PACIFIC,"FROZEN",LocalDate.of(2026,9,8),List.of(slow)).offer()).isNull();
    }

    private static AmazonMerchantFulfillmentClient.Rate rate(String id,String carrier,String service,BigDecimal cost,String delivery){
        return new AmazonMerchantFulfillmentClient.Rate(id,null,carrier,service,cost,"USD",null,null,Instant.parse(delivery),false,List.of("PDF"),null,false,false);
    }
}
