package com.nextaicommerce.platform.sync;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class AmazonProductFeeSyncTest {
 @Test void aggregateFbaFeeIsNotDoubleCountedAndCurrencyMustMatch(){
  var estimate=new ObjectMapper().readTree("""
   {"TotalFeesEstimate":{"Amount":8,"CurrencyCode":"USD"},"FeeDetailList":[
    {"FeeType":"ReferralFee","FinalFee":{"Amount":3,"CurrencyCode":"USD"}},
    {"FeeType":"FBAFees","FinalFee":{"Amount":5,"CurrencyCode":"USD"},"IncludedFeeDetailList":[{"FeeType":"FBAPickAndPack","FinalFee":{"Amount":5,"CurrencyCode":"USD"}}]}]}
   """);
  var fees=AmazonProductFeeSync.amounts(estimate,"USD");assertThat(fees.referral()).isEqualByComparingTo("3");assertThat(fees.fulfillment()).isEqualByComparingTo("5");
  assertThat(AmazonProductFeeSync.amounts(estimate,"CAD")).isNull();
 }
 @Test void missingFeesAreNotZero(){assertThat(AmazonProductFeeSync.amounts(new ObjectMapper().readTree("{}"),"USD")).isNull();}
 @Test void shipmentDateFallbackNeverUsesImportTime(){
  var json=new ObjectMapper();
  assertThat(FbaInboundSync.shipmentDate(json.readTree("{\"CreationDate\":\"bad\",\"ShipmentCreatedDate\":\"2026-10-01T12:00:00Z\",\"LastUpdatedDate\":\"2026-10-02T12:00:00Z\"}")).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-01T12:00:00Z"));
  assertThat(FbaInboundSync.shipmentDate(json.readTree("{\"LastUpdatedDate\":\"2026-10-02T12:00:00Z\"}")).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-02T12:00:00Z"));
  assertThat(FbaInboundSync.shipmentDate(json.readTree("{}"))).isNull();
 }
}
