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
 @Test void unsupportedPromotionsAreExplicitAndNeverRetryable(){
  var r=result("001");var f=fee(r,"ReferralFee");f.putObject("FeePromotion").put("CurrencyCode","USD").put("Amount",1);((ObjectNode)f.path("FinalFee")).put("Amount",2);((ObjectNode)r.path("FeesEstimate").path("TotalFeesEstimate")).put("Amount",7);
  var checked=AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(r));
  assertThat(checked.accepted()).isEmpty();assertThat(checked.retryable()).isEmpty();assertThat(checked.rejected()).extracting(AmazonProductFeeBatch.Rejected::reason).containsExactly("UNSUPPORTED_FEE_PROMOTION");
 }
 @Test void onlyMissingAndServiceErrorsRetryWhileClientErrorsNeedReview(){
  var service=result("001").put("Status","ServiceError");var client=result("1").put("Status","ClientError");
  var checked=AmazonProductFeeBatch.verify(List.of(request("001"),request("1"),request("MISSING")),json.createArrayNode().add(service).add(client));
  assertThat(checked.retryable()).extracting(r->r.target().sku()).containsExactly("001","MISSING");
  assertThat(checked.rejected()).extracting(AmazonProductFeeBatch.Rejected::reason).containsExactly("AMAZON_CLIENT_ERROR");
 }
 @Test void malformedDuplicateAndConflictingEvidenceDoesNotRepeatUnchangedRequests(){
  for(var body:List.of(json.createObjectNode(),json.createArrayNode().add(result("001")).add(result("001")),json.createArrayNode().add(result("OTHER")))){
   var checked=AmazonProductFeeBatch.verify(List.of(request("001")),body);assertThat(checked.accepted()).isEmpty();assertThat(checked.retryable()).isEmpty();assertThat(checked.rejected()).hasSize(1);
  }
 }
 @Test void legacyHierarchySupportsOnlyAggregateAndReferralNotAnInventedPerUnitFee(){
  var r=result("001");var f=fee(r,"FBAFees");var children=f.putArray("IncludedFeeDetailList");
  for(var part:Map.of("FBAWeightHandling",0,"FBAPickAndPack",5,"FBAOrderHandling",0).entrySet()){
   var c=children.addObject().put("FeeType",part.getKey());c.putObject("FeeAmount").put("CurrencyCode","USD").put("Amount",part.getValue());c.putObject("FinalFee").put("CurrencyCode","USD").put("Amount",part.getValue());c.putObject("FeePromotion").put("CurrencyCode","USD").put("Amount",0);
  }
  var checked=AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(r));
  assertThat(checked.accepted()).hasSize(1);assertThat(checked.accepted().getFirst().amounts().referral()).isEqualByComparingTo("3");assertThat(checked.accepted().getFirst().amounts().fulfillment()).isEqualByComparingTo("5");
  assertThat(AmazonProductFeeBatch.comparableFba(r.path("FeesEstimate"),"USD")).isNull();assertThat(checked.retryable()).isEmpty();
 }

 ObjectNode usBasis(){return request("001").basis().deepCopy().put("MarketplaceId","ATVPDKIKX0DER").put("IsAmazonFulfilled",true);}
 ObjectNode legacyUsEstimate(){
  var r=result("001");var children=fee(r,"FBAFees").putArray("IncludedFeeDetailList");
  for(var entry:Map.of("FBAWeightHandling",0,"FBAPickAndPack",5,"FBAOrderHandling",0).entrySet()){
   var child=children.addObject().put("FeeType",entry.getKey());child.putObject("FeeAmount").put("CurrencyCode","USD").put("Amount",entry.getValue());child.putObject("FinalFee").put("CurrencyCode","USD").put("Amount",entry.getValue());child.putObject("FeePromotion").put("CurrencyCode","USD").put("Amount",0);
  }return r;
 }
 @Test void exactUsStandardPickAndPackWithExplicitZeroSiblingsIsComparable(){
  var r=legacyUsEstimate();assertThat(AmazonProductFeeBatch.comparableFba(r.path("FeesEstimate"),"USD",usBasis())).isEqualByComparingTo("5");
  assertThat(AmazonProductFeeBatch.comparableFba(r.path("FeesEstimate"),"USD",usBasis().put("OptionalFulfillmentProgram","FBA_CORE"))).isEqualByComparingTo("5");
  assertThat(AmazonProductFeeBatch.comparableFba(r.path("FeesEstimate"),"USD")).isNull();
 }
 @Test void usLegacyInterpretationRejectsOtherMarketsProgramsOrUnprovenBreakdowns(){
  for(String variant:List.of("market","fulfilled","program","currency","nonzeroWeight","missingZero","duplicate","extra","tax","promo","mismatch","unknownField","unknownMoneyField")){
   var r=legacyUsEstimate();var basis=usBasis();var f=fee(r,"FBAFees");var children=(tools.jackson.databind.node.ArrayNode)f.path("IncludedFeeDetailList");
   switch(variant){
    case "market"->basis.put("MarketplaceId","A2EUQ1WTGCTBG2");case "fulfilled"->basis.put("IsAmazonFulfilled",false);case "program"->basis.put("OptionalFulfillmentProgram","FBA_EFN");
    case "currency"->((ObjectNode)children.get(0).path("FinalFee")).put("CurrencyCode","CAD");
    case "nonzeroWeight"->{for(var c:children){if("FBAWeightHandling".equals(c.path("FeeType").asText())){((ObjectNode)c.path("FeeAmount")).put("Amount",1);((ObjectNode)c.path("FinalFee")).put("Amount",1);}if("FBAPickAndPack".equals(c.path("FeeType").asText())){((ObjectNode)c.path("FeeAmount")).put("Amount",4);((ObjectNode)c.path("FinalFee")).put("Amount",4);}}}
    case "missingZero"->children.remove(0);case "duplicate"->children.add(children.get(0).deepCopy());case "extra"->children.addObject().put("FeeType","OtherFee");
    case "tax"->f.putObject("TaxAmount").put("CurrencyCode","USD").put("Amount",1);case "promo"->f.putObject("FeePromotion").put("CurrencyCode","USD").put("Amount",1);
    case "mismatch"->((ObjectNode)f.path("FinalFee")).put("Amount",6);case "unknownField"->f.put("OtherAssessment",true);case "unknownMoneyField"->((ObjectNode)f.path("FinalFee")).put("Unknown",1);
   }
   assertThat(AmazonProductFeeBatch.comparableFba(r.path("FeesEstimate"),"USD",basis)).as(variant).isNull();
  }
 }

 @Test void contradictoryEchoedProgramCannotBecomeAQuoteOrPerUnitEvidence(){
  var r=legacyUsEstimate();((ObjectNode)r.path("FeesEstimateIdentifier")).put("OptionalFulfillmentProgram","FBA_EFN");
  var checked=AmazonProductFeeBatch.verify(List.of(request("001")),json.createArrayNode().add(r));
  assertThat(checked.accepted()).isEmpty();assertThat(checked.retryable()).isEmpty();assertThat(checked.rejected()).extracting(AmazonProductFeeBatch.Rejected::reason).containsExactly("RESPONSE_BASIS_CONFLICT");
 }
 @Test void usMarketplaceWithoutUsdCannotUseTheLegacyInterpretation(){
  var r=legacyUsEstimate();assertThat(AmazonProductFeeBatch.comparableFba(r.path("FeesEstimate"),"CAD",usBasis())).isNull();
 }

}
