package com.nextaicommerce.platform.fees;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class FeeComparisonTest {
 final Instant boundary=Instant.parse("2026-10-24T10:00:00Z"),purchase=boundary.plusSeconds(1);
 @Test void currencyRoundingInsteadOfFixedTolerance(){
  assertThat(FeeComparison.compare("USD",purchase,boundary,new BigDecimal("2.004"),new BigDecimal("2.003"),true).state()).isEqualTo("MATCHES");
  assertThat(FeeComparison.compare("USD",purchase,boundary,new BigDecimal("2"),new BigDecimal("2.01"),true).state()).isEqualTo("HIGHER");
  assertThat(FeeComparison.compare("JPY",purchase,boundary,new BigDecimal("201"),new BigDecimal("200"),true).state()).isEqualTo("LOWER");
 }
 @Test void absentChargesAreNotZero(){var r=FeeComparison.compare("USD",purchase,boundary,BigDecimal.ONE,null,false);assertThat(r.state()).isEqualTo("AWAITING");assertThat(r.difference()).isNull();}
 @Test void explicitZeroCanBeCompared(){assertThat(FeeComparison.compare("USD",purchase,boundary,BigDecimal.ONE,BigDecimal.ZERO,true).state()).isEqualTo("LOWER");}
 @Test void noRetroactiveOrEqualBoundary(){for(var p:new Instant[]{boundary,boundary.minusSeconds(1)})assertThat(FeeComparison.compare("USD",p,boundary,BigDecimal.ONE,BigDecimal.TEN,true).state()).isEqualTo("NOT_COMPARABLE");}
 @Test void unsupportedCurrencyDoesNotBecomeUsd(){assertThat(FeeComparison.compare("???",purchase,boundary,BigDecimal.ONE,BigDecimal.TEN,true).state()).isEqualTo("NOT_COMPARABLE");}
}
