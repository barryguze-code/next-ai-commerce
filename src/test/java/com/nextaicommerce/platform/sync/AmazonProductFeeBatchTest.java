package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
class AmazonProductFeeBatchTest {
 final ObjectMapper json=new ObjectMapper();
 AmazonProductFeeBatch.Request request(String sku){var b=json.createObjectNode().put("MarketplaceId","MARKET").put("_platformSellerId","SELLER");var p=b.putObject("PriceToEstimateFees");p.putObject("ListingPrice").put("CurrencyCode","USD").put("Amount",30);p.putObject("Shipping").put("CurrencyCode","USD").put("Amount",0);return new AmazonProductFeeBatch.Request(new AmazonProductFeeJobs.Target(sku,BigDecimal.valueOf(30),"USD"),b,"ID-"+sku);}
 ObjectNode result(String sku){var r=json.createObjectNode().put("Status","Success");var id=r.putObject("FeesEstimateIdentifier").put("IdType","SellerSKU").put("IdValue",sku).put("MarketplaceId","MARKET").put("SellerId","SELLER").put("IsAmazonFulfilled",true).put("SellerInputIdentifier","ID-"+sku);id.set("PriceToEstimateFees",request(sku).basis().path("PriceToEstimateFees"));var e=r.putObject("FeesEstimate");e.putObject("TotalFeesEstimate").put("CurrencyCode","USD").put("Amount",8);var fees=e.putArray("FeeDetailList");for(var entry:Map.of("ReferralFee",3,"FBAFees",5).entrySet()){var fee=fees.addObject().put("FeeType",entry.getKey());fee.putObject("FeeAmount").put("CurrencyCode","USD").put("Amount",entry.getValue());fee.putObject("FinalFee").put("CurrencyCode","USD").put("Amount",entry.getValue());}return r;}
 ObjectNode fee(ObjectNode result,String type){for(var n:result.path("FeesEstimate").path("FeeDetailList"))if(type.equals(n.path("FeeType").asText()))return (ObjectNode)n;throw new AssertionError(type);}
 @Test void missingOrInconsistentFinalFeeCannotUseTheLegacyFallback(){
  for(String variant:List.of("missing","different","currency","tax","promo","total")){
   var r=result("001");var fee=fee(r,"ReferralFee");
   switch(variant){case "missing"->fee.remove("FinalFee");case "different"->((ObjectNode)fee.path("FinalFee")).put("Amount",4);case "currency"->((ObjectNode)fee.path("FinalFee")).put("CurrencyCode","CAD");case "tax"->fee.putObject("TaxAmount").put("CurrencyCode","USD").put("Amount",1);case "promo"->fee.putObject("FeePromotion").put("CurrencyCode","USD").put("Amount",1);case "total"->((ObjectNode)r.path("FeesEstimate").path("TotalFeesEstimate")).put("Amount",9);}
   assertThat(AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(r)).accepted()).as(variant).isEmpty();
  }
 }
 @Test void nestedFinalFeeMustBePresentAndAgreeWithItsAggregate(){
  var r=result("001");var f=fee(r,"FBAFees");var child=f.putArray("IncludedFeeDetailList").addObject().put("FeeType","FBAPerUnitFulfillmentFee");child.putObject("FeeAmount").put("CurrencyCode","USD").put("Amount",5);
  assertThat(AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(r)).accepted()).isEmpty();
  child.putObject("FinalFee").put("CurrencyCode","USD").put("Amount",5);
  assertThat(AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(r)).accepted()).hasSize(1);
 }

 @Test void unorderedResponsesKeepExactLeadingZeroIdentity(){var out=AmazonProductFeeBatch.verify(List.of(request("001"),request("1")),json.createArrayNode().add(result("1")).add(result("001")));assertThat(out.accepted()).hasSize(2);assertThat(out.unresolved()).isEmpty();}
 @Test void duplicateAndMissingItemsStayUnresolved(){var out=AmazonProductFeeBatch.verify(List.of(request("001"),request("1")),json.createArrayNode().add(result("001")).add(result("001")));assertThat(out.accepted()).isEmpty();assertThat(out.unresolved()).hasSize(2);}
 @Test void partialSuccessDoesNotInventZeroForSibling(){var bad=result("1");bad.put("Status","ClientError");var out=AmazonProductFeeBatch.verify(List.of(request("001"),request("1")),json.createArrayNode().add(result("001")).add(bad));assertThat(out.accepted()).hasSize(1);assertThat(out.unresolved()).hasSize(1);}
 @Test void differentSellerCurrencyOrBasisCannotPublish(){var bad=result("001");((ObjectNode)bad.path("FeesEstimateIdentifier")).put("SellerId","OTHER");assertThat(AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(bad)).accepted()).isEmpty();}
 @Test void duplicateFeeTypesAreRejected(){var r=result("001");var fees=(tools.jackson.databind.node.ArrayNode)r.path("FeesEstimate").path("FeeDetailList");fees.add(fees.get(0));assertThat(AmazonProductFeeSync.amounts(r.path("FeesEstimate"),"USD")).isNull();}
 @Test void aggregateAloneOrExtraComponentsAreNotComparable(){var e=result("001").path("FeesEstimate");assertThat(AmazonProductFeeSync.comparableFba(e,"USD")).isNull();var f=(ObjectNode)java.util.stream.StreamSupport.stream(e.path("FeeDetailList").spliterator(),false).filter(n->"FBAFees".equals(n.path("FeeType").asText())).findFirst().orElseThrow();var child=f.putArray("IncludedFeeDetailList").addObject().put("FeeType","FBAPerUnitFulfillmentFee");child.putObject("FeeAmount").put("CurrencyCode","USD").put("Amount",4);assertThat(AmazonProductFeeSync.comparableFba(e,"USD")).isNull();((ObjectNode)child.path("FeeAmount")).put("Amount",5);assertThat(AmazonProductFeeSync.comparableFba(e,"USD")).isEqualByComparingTo("5");}
}
