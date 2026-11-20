package com.nextaicommerce.platform.fba;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class FbaShipmentDateTest {
 private FbaReadRepository.Shipment shipment(String name,Instant created){return new FbaReadRepository.Shipment("FBA1",name,"CLOSED",null,null,null,1,12,12,created);}
 @Test void authoritativeTimestampDisplaysOnlyItsExistingUtcDate(){var row=shipment("Other",Instant.parse("2026-11-06T00:30:00Z"));assertThat(row.createdPrimaryDisplay()).isEqualTo("11/06/2026");assertThat(row.createdDisplay()).contains("12:30 AM").contains("UTC");}
 @Test void nameDatePreservesCalendarDayAndTooltipProvenance(){var row=shipment("FBA STA (01/02/2024 19:48)-MIT2",null);assertThat(row.createdPrimaryDisplay()).isEqualTo("01/02/2024");assertThat(row.createdDisplay()).contains("07:48 PM").contains("shipment name (timezone unspecified)");}
 @Test void missingOrInvalidDateStaysUnknown(){assertThat(shipment("No date",null).createdPrimaryDisplay()).isEqualTo("Not supplied");assertThat(shipment("FBA (02/30/2024 19:48)",null).createdPrimaryDisplay()).isEqualTo("Not supplied");}
}
