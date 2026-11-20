package com.nextaicommerce.platform.marketplace;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SkuConditionTest {
    @Test void preservesConditionRatherThanInferringFromSku() {
        assertThat(SkuCondition.label("11")).isEqualTo("New");
        assertThat(SkuCondition.label("1")).containsIgnoringCase("like new");
        assertThat(SkuCondition.label("3")).containsIgnoringCase("good");
        assertThat(SkuCondition.label(null)).containsIgnoringCase("pending");
    }
}
