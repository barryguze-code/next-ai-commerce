package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ProfitPricePublisherTest {
 private final ObjectMapper json=new ObjectMapper();
 @Test void salePatchLeavesBasePriceUntouched(){
  var start=java.time.LocalDate.now().plusDays(1);var end=start.plusDays(7);
  var body=json.readTree(ProfitPricePublisher.patch(json,json.readTree(LISTING),new BigDecimal("15.00"),start,end));
  var offer=body.path("patches").path(0).path("value").path(0);
  assertThat(offer.has("our_price")).isFalse();
  assertThat(offer.path("discounted_price").path(0).path("schedule").path(0).path("start_at").asText()).isEqualTo(start.toString());
  assertThatThrownBy(()->ProfitPricePublisher.validateSale(new BigDecimal("21"),new BigDecimal("20"),start,end)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->ProfitPricePublisher.validateSale(new BigDecimal("15"),new BigDecimal("20"),end,start)).isInstanceOf(IllegalArgumentException.class);
 }
 static final String LISTING="""
  {"productTypes":[{"productType":"GROCERY"}],"attributes":{"purchasable_offer":[
   {"marketplace_id":"ATVPDKIKX0DER","currency":"USD","audience":"ALL",
    "our_price":[{"schedule":[{"value_with_tax":20}]}],
    "minimum_seller_allowed_price":[{"schedule":[{"value_with_tax":10}]}],
    "maximum_seller_allowed_price":[{"schedule":[{"value_with_tax":30}]}]}]}}
  """;
 @Test void patchChangesOnlyBasePriceAndPreservesOtherAttributes(){
  var body=json.readTree(ProfitPricePublisher.patch(json,json.readTree(LISTING),new BigDecimal("18.25")));
  assertThat(body.path("productType").asText()).isEqualTo("GROCERY");
  var patch=body.path("patches").path(0);assertThat(patch.path("op").asText()).isEqualTo("merge");
  var offer=patch.path("value").path(0);assertThat(offer.size()).isEqualTo(4);
  assertThat(offer.path("our_price").path(0).path("schedule").path(0).path("value_with_tax").decimalValue()).isEqualByComparingTo("18.25");
 }
 @Test void rejectsPriceOutsideAmazonLimits(){var offer=ProfitPricePublisher.offer(json.readTree(LISTING));for(String amount:new String[]{"0","9.99","30.01","10.001"})assertThatThrownBy(()->ProfitPricePublisher.validate(new BigDecimal(amount),offer)).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsScheduledPrices(){assertThatThrownBy(()->ProfitPricePublisher.offer(json.readTree(LISTING.replace("\"value_with_tax\":20","\"value_with_tax\":20,\"start_at\":\"2026-01-01\"")))).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsSalesAndAutomatedPricing(){for(String field:new String[]{"discounted_price","automated_pricing_merchandising_rule_plan"})assertThatThrownBy(()->ProfitPricePublisher.offer(json.readTree(LISTING.replace("\"our_price\":","\""+field+"\":[{}],\"our_price\":")))).isInstanceOf(IllegalArgumentException.class);}
 @Test void publishingRequiresProductionWritesFeatureAndConnectionAllowlist(){
  var environment=new MockEnvironment();environment.setActiveProfiles("prod");UUID id=UUID.randomUUID();
  assertThat(new ProfitPricePublisher(null,null,null,json,environment,false,true,true,id.toString()).available(id)).isTrue();
  assertThat(new ProfitPricePublisher(null,null,null,json,environment,true,true,true,id.toString()).available(id)).isFalse();
  assertThat(new ProfitPricePublisher(null,null,null,json,environment,false,false,true,id.toString()).available(id)).isFalse();
  assertThat(new ProfitPricePublisher(null,null,null,json,environment,false,true,false,id.toString()).available(id)).isFalse();
  assertThat(new ProfitPricePublisher(null,null,null,json,environment,false,true,true,"").available(id)).isFalse();
  environment.setActiveProfiles("local");assertThat(new ProfitPricePublisher(null,null,null,json,environment,false,true,true,id.toString()).available(id)).isFalse();
 }
}
