package com.nextaicommerce.platform.receiving;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class ReplenishmentPlanningTest {
 @Test void downloadPreservesIdentifiersAndEscapesSpreadsheetFormulas(){
  var row=Map.<String,Object>of("code","00123","vendor_code","=BAD,\"vendor\"","upc","000123456789","cases",new java.math.BigDecimal("2.0000"),"suggested_each",24);
  String csv=ReplenishmentDraftController.csv(java.util.List.of(row));
  assertThat(csv).contains("Case qty (suggested)","\"00123\"","\"000123456789\"","\"'=BAD,\"\"vendor\"\"\"","\"2\",\"24\"");
 }
 @Test void forecastFiltersAndSortsBestSellersWithoutNoHistoryStockouts(){
  var a=Map.<String,Object>of("id","a","demand",28,"cases",2,"status","oos","name","A","code","1","vendor_code","KEHE");
  var b=Map.<String,Object>of("id","b","demand",56,"cases",3,"status","low","name","B","code","2","vendor_code","KEHE");
  var c=Map.<String,Object>of("id","c","demand",84,"cases",0,"status","healthy","name","C","code","3","vendor_code","KEHE");
  var d=Map.<String,Object>of("id","d","demand",0,"cases",2,"status","oos","name","D","code","4","vendor_code","KEHE");
  var rows=java.util.List.of(a,b,c,d);
  assertThat(ReplenishmentDraftController.select(rows,"forecast","")).containsExactly(b,a);
  assertThat(ReplenishmentDraftController.select(rows,"oos","")).containsExactly(a);
  assertThat(ReplenishmentDraftController.select(rows,"all","")).containsExactly(c,b,a);
 }
 private final ReplenishmentPlanning.Policy policy=new ReplenishmentPlanning.Policy(2,7,14,35);
 @Test void combinesComponentDemandAndRoundsUpToCases(){var e=ReplenishmentPlanning.estimate(60,672,12,policy);assertThat(e.daily()).isEqualTo(24);assertThat(e.target()).isEqualTo(384);assertThat(e.cases()).isEqualTo(27);assertThat(e.status()).isEqualTo("low");}
 @Test void slowSellerKeepsAtLeastTwoEaches(){var e=ReplenishmentPlanning.estimate(1,2,1,new ReplenishmentPlanning.Policy(0,1,7,35));assertThat(e.target()).isEqualTo(2);assertThat(e.cases()).isEqualTo(1);assertThat(e.status()).isEqualTo("low");}
 @Test void unknownHistoryNeverCreatesSuggestion(){assertThat(ReplenishmentPlanning.estimate(0,0,12,policy).cases()).isZero();}
 @Test void minimumCoversTwoSellingSkuPacks(){var e=ReplenishmentPlanning.estimate(0,6,8,new ReplenishmentPlanning.Policy(2,2,12,20),12);assertThat(e.target()).isEqualTo(12);assertThat(e.cases()).isEqualTo(2);}
 @Test void barStatusUsesVendorDaysWhileLeadTimeCanTriggerAnOrder(){var e=ReplenishmentPlanning.estimate(3,28,1,new ReplenishmentPlanning.Policy(2,2,12,20));assertThat(e.status()).isEqualTo("healthy");assertThat(e.cases()).isPositive();}
 @Test void healthyAndOverstockDoNotOrder(){assertThat(ReplenishmentPlanning.estimate(200,280,12,policy).cases()).isZero();assertThat(ReplenishmentPlanning.estimate(999,280,12,policy).status()).isEqualTo("overstock");}
 @Test void outOfStockPreservesDemand(){var e=ReplenishmentPlanning.estimate(0,28,12,policy);assertThat(e.status()).isEqualTo("oos");assertThat(e.cases()).isEqualTo(2);}
 @Test void longLeadIncreasesTarget(){assertThat(ReplenishmentPlanning.estimate(0,280,12,new ReplenishmentPlanning.Policy(12,7,14,35)).target()).isGreaterThan(ReplenishmentPlanning.estimate(0,280,12,policy).target());}
 @Test void invalidThresholdsAreRejected(){assertThatThrownBy(()->new ReplenishmentPlanning.Policy(2,20,10,30)).isInstanceOf(IllegalArgumentException.class);}
 @Test void vendorOverridesAreScopedAndBlankInherits(){var id=UUID.randomUUID();var config=Map.<String,Object>of("low",7,"target",14,"overstock",35,"vendors",Map.of(id.toString(),Map.of("lead","12","low","10","target","")));var p=ReplenishmentSuggestions.policy(config,id,"KEHE");assertThat(p.lead()).isEqualTo(12);assertThat(p.low()).isEqualTo(10);assertThat(p.target()).isEqualTo(14);assertThat(ReplenishmentSuggestions.policy(config,UUID.randomUUID(),"KEHE").lead()).isEqualTo(2);}
}
