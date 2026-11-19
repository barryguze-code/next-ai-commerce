package com.nextaicommerce.platform.fba;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.time.*;import java.util.*;import static com.nextaicommerce.platform.fba.FbaReplenishmentMath.*;
class FbaReplenishmentMathTest {
 final LocalDate now=LocalDate.of(2026,11,17);final Demand demand=new Demand(300,900,90,null,null);
 @Test void weightedWindowsAndStockoutEvidence(){assertEquals(10,demand.daily());assertEquals(12,new Demand(300,900,90,25,75).daily());}
 @Test void targetAtArrivalAndExistingCommitmentsStaySeparate(){var r=estimate(now,demand,new Policy(28,60,2,5,3,null),200d,false,90,List.of(),List.of());assertEquals(500,r.recommended());assertEquals(90,r.feasible());assertEquals(410,r.shortfall());assertEquals(now.plusDays(10),r.arrival());}
 @Test void unknownNeverBecomesZero(){assertNull(estimate(now,demand,Policy.defaults(),null,false,100,List.of(),List.of()).recommended());assertNull(estimate(now,demand,Policy.defaults(),100d,true,100,List.of(),List.of()).recommended());}
 @Test void noAutomaticFbmBufferAndSharedStockCountedOnce(){UUID item=UUID.randomUUID();var lines=new LinkedHashMap<String,Map<UUID,Long>>();lines.put("pack4",Map.of(item,4L));lines.put("pack2",Map.of(item,2L));assertEquals(Map.of("pack4",2L,"pack2",1L),feasible(Map.of(item,10L),lines,Map.of("pack4",2L,"pack2",4L)));}
 @Test void seasonalOverlapDoesNotStackAndUncertainInboundExcluded(){var events=List.of(new Event(UUID.randomUUID(),"A",now,now.plusDays(1),1d,true,"verified"),new Event(UUID.randomUUID(),"B",now,now.plusDays(1),2d,true,"verified"));assertEquals(60,demand(10,now,2,events));var r=estimate(now,demand,Policy.defaults(),10d,false,100,List.of(new FutureSupply(1000,null,false)),events);assertTrue(r.warnings().stream().anyMatch(s->s.contains("Overlapping")));assertTrue(r.warnings().stream().anyMatch(s->s.contains("Uncertain")));}
 @Test void thresholdIsStrictlyBelow28Days(){assertEquals(0,estimate(now,demand,Policy.defaults(),280d,false,100,List.of(),List.of()).recommended());assertTrue(estimate(now,demand,Policy.defaults(),279d,false,100,List.of(),List.of()).recommended()>0);}
 @Test void userOverrideAndLimitedHistory(){var r=estimate(now,new Demand(0,0,3,null,null),new Policy(28,60,0,0,0,2d),0d,false,10,List.of(),List.of());assertEquals(120,r.recommended());assertFalse(r.warnings().stream().anyMatch(s->s.startsWith("Limited")));}

 @Test void seasonalDemandMovesProjectedStockoutInsteadOfUsingFlatCover(){
  var peak=new Event(UUID.randomUUID(),"Peak",now,now.plusDays(4),1d,true,"verified");
  var r=estimate(now,demand,Policy.defaults(),100d,false,100,List.of(),List.of(peak));
  assertEquals(now.plusDays(5),r.stockout());assertEquals(5d,r.cover());
 }
 @Test void lateInboundDoesNotConcealEarlierStockoutOrCreateLostSalesDebt(){
  var policy=new Policy(28,60,10,0,0,null);
  var r=estimate(now,demand,policy,20d,false,100,List.of(new FutureSupply(100,now.plusDays(5),true)),List.of());
  assertEquals(now.plusDays(2),r.stockout());assertEquals(550,r.recommended());
 }
 @Test void verifiedArrivalProtectsStockOnlyFromItsOwnDate(){
  var r=estimate(now,demand,Policy.defaults(),20d,false,100,List.of(new FutureSupply(100,now.plusDays(2),true)),List.of());
  assertEquals(now.plusDays(12),r.stockout());
  var previous=estimate(now,demand,Policy.defaults(),20d,false,100,List.of(new FutureSupply(100,now.minusDays(1),true)),List.of());
  assertEquals(now.plusDays(2),previous.stockout());
 }
 @Test void ownerPriorityIsEarliestStockoutThenEffectiveSkuDemandThenStableSku(){
  var slow=estimate(now,new Demand(30,90,90,null,null),Policy.defaults(),2d,false,50,List.of(),List.of());
  var fast=estimate(now,demand,Policy.defaults(),20d,false,50,List.of(),List.of());
  var later=estimate(now,demand,Policy.defaults(),30d,false,50,List.of(),List.of());
  var unknown=estimate(now,demand,Policy.defaults(),null,false,50,List.of(),List.of());
  assertTrue(priority(fast,"fast",slow,"slow")<0);assertTrue(priority(slow,"slow",later,"later")<0);
  assertTrue(priority(later,"later",unknown,"unknown")<0);assertTrue(priority(fast,"A",fast,"B")<0);
 }
 @Test void unknownInboundCannotPostponeStockout(){
  assertEquals(now.plusDays(2),estimate(now,demand,Policy.defaults(),20d,false,100,List.of(new FutureSupply(100,now.plusDays(1),false)),List.of()).stockout());
 }

 @Test void fractionalDayCoverageRetainsThresholdAccuracy(){var r=estimate(now,demand,Policy.defaults(),609d,false,100,List.of(),List.of());assertEquals(60.9,r.cover());assertEquals("Healthy",r.status());assertEquals(now.plusDays(60),r.stockout());}
 @Test void missingLeadTimesNeverClaimSameDayArrival(){var provisional=estimate(now,demand,Policy.defaults(),100d,false,100,List.of(),List.of());assertNull(provisional.arrival());assertNull(provisional.prepareBy());assertNotNull(provisional.recommended());assertTrue(provisional.warnings().stream().anyMatch(w->w.contains("provisional")));var explicit=estimate(now,demand,new Policy(28,60,0,0,0,null,true),100d,false,100,List.of(),List.of());assertEquals(now,explicit.arrival());}
 @org.junit.jupiter.api.Test void olderSavedPolicyKeepsMissingLeadTimesUnconfirmed(){var policy=new tools.jackson.databind.ObjectMapper().readValue("{\"lowDays\":28,\"targetDays\":60,\"preparationDays\":0,\"transitDays\":0,\"receivingDays\":0,\"dailyOverride\":null}",FbaReplenishmentMath.Policy.class);org.junit.jupiter.api.Assertions.assertFalse(policy.leadTimesConfirmed());}
}
