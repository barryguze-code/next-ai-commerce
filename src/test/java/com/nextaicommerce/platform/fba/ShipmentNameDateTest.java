package com.nextaicommerce.platform.fba;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShipmentNameDateTest {
 @Test void fallbackIsExplicitAndDoesNotInventTimezone(){
  var row=new FbaReadRepository.Shipment("id","FBA STA (10/13/2026 15:33)-RYY2","CLOSED","RYY2","TX",null,1,2,2);
  assertThat(row.createdDisplay()).isEqualTo("10/13/2026 03:33 PM · shipment name (timezone unspecified)");
 }
 @Test void invalidNameDatesRemainUnknown(){
  var row=new FbaReadRepository.Shipment("id","FBA STA (02/30/2026 15:33)-RYY2","CLOSED","RYY2","TX",null,1,2,2);
  assertThat(row.createdDisplay()).isEqualTo("Not supplied");
 }
}
