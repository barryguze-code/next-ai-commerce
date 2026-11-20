package com.nextaicommerce.platform.receiving;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReplenishmentAvailabilityTest {
 @Test void stockoutDurationStopsAtUnknownGaps(){
  var now=Instant.parse("2026-09-27T12:00:00Z");
  var recent=java.util.List.of(new ReplenishmentAvailability.Observation(now.minusSeconds(36*3600),false),new ReplenishmentAvailability.Observation(now.minusSeconds(12*3600),false));
  assertThat(ReplenishmentAvailability.stockoutDays(recent,now)).isEqualTo("1.5");
  assertThat(ReplenishmentAvailability.stockoutDays(java.util.List.of(new ReplenishmentAvailability.Observation(now.minusSeconds(30*3600),false)),now)).isNull();
 }
 @Test void missingAndFbaQuantitiesAreNotAssumedOutOfStock(){
  var parsed=ReplenishmentAvailability.parse("\uFEFFseller-sku\tquantity\tstatus\tfulfillment-channel\nA\t5\tActive\tDEFAULT\nB\t0\tActive\tDEFAULT\nC\t5\tInactive\tDEFAULT\nD\t\tActive\tDEFAULT\nE\t5\tActive\tAMAZON_NA\n");
  assertThat(parsed).containsExactlyInAnyOrderEntriesOf(Map.of("A",true,"B",false,"C",false));
 }
 @Test void gapsAreUnknownAndPercentUsesOnlyCoveredBusinessHours(){
  var start=Instant.parse("2026-09-01T00:00:00Z");
  var c=ReplenishmentAvailability.calculate(List.of(new ReplenishmentAvailability.Observation(start,true),new ReplenishmentAvailability.Observation(start.plusSeconds(86400),false)),start,start.plusSeconds(4*86400),ZoneId.of("UTC"));
  assertThat(c.percent()).isEqualTo("50.0%");assertThat(c.coverage()).isEqualTo("50%");
  assertThat(c.known()).isEqualTo(38*3600d);
 }
 @Test void overnightIsExcludedAndDstUsesLocalClock(){
  var zone=ZoneId.of("America/Los_Angeles");
  var start=LocalDate.of(2026,3,8).atStartOfDay(zone).toInstant();
  var end=LocalDate.of(2026,3,9).atStartOfDay(zone).toInstant();
  assertThat(ReplenishmentAvailability.activeSeconds(start,end,zone)).isEqualTo(19*3600d);
  assertThat(ReplenishmentAvailability.activeSeconds(start,start.plusSeconds(3*3600),zone)).isZero();
 }
 @Test void observationsAreClippedAndDuplicatesDoNotDoubleCount(){
  var start=Instant.parse("2026-09-01T00:00:00Z");
  var c=ReplenishmentAvailability.calculate(List.of(new ReplenishmentAvailability.Observation(start,true),new ReplenishmentAvailability.Observation(start,false)),start,start.plusSeconds(86400),ZoneId.of("UTC"));
  assertThat(c.percent()).isEqualTo("0.0%");assertThat(c.coverage()).isEqualTo("100%");
  assertThat(ReplenishmentAvailability.calculate(List.of(),start,start.plusSeconds(86400),ZoneId.of("UTC")).percent()).isEqualTo("—");
 }
}
