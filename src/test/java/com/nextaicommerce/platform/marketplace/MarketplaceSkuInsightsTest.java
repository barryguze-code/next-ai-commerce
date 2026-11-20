package com.nextaicommerce.platform.marketplace;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarketplaceSkuInsightsTest {
    @Test void separatesPriceMatchFromCoverage(){
        var result=new MarketplaceSkuRepository.SkuInsights(100,40,10,30,75,100);
        assertThat(result.matchPercent()).isEqualTo("25.0%");
        assertThat(result.coveragePercent()).isEqualTo("40.0%");
        assertThat(result.weeklyChange()).isEqualTo("-25.0%");
    }
    @Test void missingDataIsNotZeroPercent(){
        var empty=new MarketplaceSkuRepository.SkuInsights(0,0,0,0,0,0);
        assertThat(empty.matchPercent()).isEqualTo("—");
        assertThat(empty.coveragePercent()).isEqualTo("—");
        assertThat(empty.weeklyChange()).isEqualTo("—");
        assertThat(new MarketplaceSkuRepository.SkuInsights(1,0,0,0,2,0).weeklyChange()).isEqualTo("New sales");
    }
}
