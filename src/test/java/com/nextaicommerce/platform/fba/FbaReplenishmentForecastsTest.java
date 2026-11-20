package com.nextaicommerce.platform.fba;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;
import static com.nextaicommerce.platform.fba.FbaReplenishmentForecasts.*;
class FbaReplenishmentForecastsTest {
 final Instant end=Instant.parse("2026-11-17T00:00:00Z");
 @Test void continuousVerifiedOutageAdjustsOnlyCompleteDays(){
  assertEquals(2,verifiedOutageDays(List.of(new Observation(end.minus(Duration.ofDays(2)),0),new Observation(end.minus(Duration.ofDays(1)),0)),end,30));
 }
 @Test void sparseReportsNeverInventStockoutAcrossUnknownGaps(){
  assertEquals(1,verifiedOutageDays(List.of(new Observation(end.minus(Duration.ofDays(3)),0)),end,30));
  assertEquals(0,verifiedOutageDays(List.of(new Observation(end.minus(Duration.ofHours(23)),0)),end,30));
 }
 @Test void positiveObservationEndsOutage(){
  assertEquals(0,verifiedOutageDays(List.of(new Observation(end.minus(Duration.ofDays(1)),0),new Observation(end.minus(Duration.ofHours(12)),5)),end,30));
 }
 @Test void fbaSurplusDoesNotCoverWarehouseOnlyDemand(){
  UUID store=UUID.randomUUID();var channels=List.of(new ChannelSupply(store,"A","FBA",1,90,200d,0d),new ChannelSupply(store,"B","SHOPIFY",1,90,0d,90d));
  var value=summarize(UUID.randomUUID(),"Product",20,channels,Set.of());
  assertEquals(70,value.shortfall());assertEquals(200,value.amazon());
 }
 @Test void warehouseCountedOnceForMultipleFbaAndMerchantSkus(){
  UUID store=UUID.randomUUID();var channels=List.of(new ChannelSupply(store,"A","FBA",1,90,50d,40d),new ChannelSupply(store,"B","FBM",1,90,0d,90d));
  assertEquals(30,summarize(UUID.randomUUID(),"Product",100,channels,Set.of()).shortfall());
 }
 @Test void missingFbaEvidenceIsUnknownInsteadOfZeroOrHealthy(){
  UUID store=UUID.randomUUID();var channels=List.of(new ChannelSupply(store,"A","FBA",1,90,null,null),new ChannelSupply(store,"B","FBM",1,90,0d,90d));
  var value=summarize(UUID.randomUUID(),"Product",100,channels,Set.of("Needs review"));assertNull(value.shortfall());assertNull(value.channels().getFirst().available());
 }
 @Test void brandAndStoreSeasonalScopesRemainSeparate(){
  UUID a=UUID.randomUUID(),b=UUID.randomUUID();var event=new FbaReplenishmentMath.Event(UUID.randomUUID(),"Peak",LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),1d,true,"verified");
  var scoped=new ScopedEvent(event,"Karaca","A",a,false);assertTrue(scoped.applies("Karaca","A",a));assertFalse(scoped.applies("Karaca","A",b));assertFalse(scoped.applies("Other","A",a));assertFalse(new ScopedEvent(event,"","",null,true).applies("Karaca","A",a));
 }

 @Test void knownCalendarUsesCorrespondingThanksgivingRatherThanSubtracting365Days(){
  UUID store=UUID.randomUUID();var events=knownUsEvents(store,LocalDate.of(2026,11,17),LocalDate.of(2027,2,1));
  var thanksgiving=events.stream().filter(e->e.event().name().equals("Thanksgiving")).findFirst().orElseThrow();
  assertEquals(LocalDate.of(2026,11,26),thanksgiving.event().start());assertEquals(LocalDate.of(2025,11,27),thanksgiving.previousStart());assertNull(thanksgiving.event().uplift());
  assertEquals(thanksgiving.event().id(),knownUsEvents(store,LocalDate.of(2026,11,1),LocalDate.of(2026,12,1)).getFirst().event().id());
  assertTrue(events.stream().noneMatch(e->e.event().name().contains("Prime")));
 }
 List<HistoricalDay> reliableSales(LocalDate previous,boolean promo){var sales=new ArrayList<HistoricalDay>();for(int n=28;n>0;n--)sales.add(new HistoricalDay(previous.minusDays(n),2,true,false));sales.add(new HistoricalDay(previous,6,true,promo));return sales;}
 List<Observation> reliableAvailability(LocalDate previous){var rows=new ArrayList<Observation>();for(int n=28;n>=0;n--)rows.add(new Observation(previous.minusDays(n).atStartOfDay(ZoneId.of("America/Los_Angeles")).toInstant(),100));return rows;}
 @Test void strongPriorYearEvidenceAppliesUpliftAndExposesItsBasis(){
  var event=knownUsEvents(UUID.randomUUID(),LocalDate.of(2026,11,17),LocalDate.of(2026,12,1)).getFirst();LocalDate previous=event.previousStart();
  var comparison=seasonalComparisons("A",List.of(event),reliableSales(previous,false),reliableAvailability(previous),LocalDate.of(2026,11,17),ZoneId.of("America/Los_Angeles")).getFirst();
  assertTrue(comparison.applied());assertEquals(6,comparison.units());assertEquals(56,comparison.baselineUnits());assertTrue(comparison.availabilityCoverage()>=.95&&comparison.availabilityCoverage()<=1);assertEquals(2,comparison.uplift());
  assertEquals(2,seasonalEvents(List.of(event),List.of(comparison)).getFirst().uplift());
 }
 @Test void priorPromotionWithoutKnownRecurrenceNeverAutomaticallyRaisesForecast(){
  var event=knownUsEvents(UUID.randomUUID(),LocalDate.of(2026,11,17),LocalDate.of(2026,12,1)).getFirst();LocalDate previous=event.previousStart();
  var comparison=seasonalComparisons("A",List.of(event),reliableSales(previous,true),reliableAvailability(previous),LocalDate.of(2026,11,17),ZoneId.of("America/Los_Angeles")).getFirst();
  assertFalse(comparison.applied());assertTrue(comparison.message().contains("recurrence"));assertNull(seasonalEvents(List.of(event),List.of(comparison)).getFirst().uplift());
 }
 @Test void historicalUpliftRequiresPriorEventAvailabilityNotOnlySales(){
  var event=knownUsEvents(UUID.randomUUID(),LocalDate.of(2026,11,17),LocalDate.of(2026,12,1)).getFirst();LocalDate previous=event.previousStart();var observations=reliableAvailability(previous);observations.removeLast();
  var comparison=seasonalComparisons("A",List.of(event),reliableSales(previous,false),observations,LocalDate.of(2026,11,17),ZoneId.of("America/Los_Angeles")).getFirst();
  assertFalse(comparison.applied());assertEquals(0,comparison.availabilityCoverage());
 }
 @Test void accountFbaDemandAlsoAdjustsOnlyVerifiedFullZeroDays(){
  var values=new HashMap<String,FbaReplenishmentMath.Demand>();values.put("A",new FbaReplenishmentMath.Demand(300,900,90,null,null));
  adjustVerifiedOutages(values,Map.of("A",List.of(new Observation(end.minus(Duration.ofDays(1)),0))),end);
  assertEquals(29,values.get("A").verifiedSellingDays30());assertEquals(89,values.get("A").verifiedSellingDays90());
 }

 @Test void failedRefreshDoesNotLoopFromPollingAndManualRefreshCanRetry()throws Exception {
  var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var manager=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
  org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenAnswer(i->new org.springframework.transaction.support.SimpleTransactionStatus());
  var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
  var forecasts=org.mockito.Mockito.spy(new FbaReplenishmentForecasts(jdbc,tx,org.mockito.Mockito.mock(com.nextaicommerce.platform.receiving.InventoryRepository.class),org.mockito.Mockito.mock(FbaReadRepository.class),new tools.jackson.databind.ObjectMapper(),org.mockito.Mockito.mock(com.nextaicommerce.platform.marketplace.MarketplaceSkuRelationships.class)));
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();var calls=new java.util.concurrent.atomic.AtomicInteger();
  org.mockito.Mockito.doAnswer(i->{calls.incrementAndGet();throw new IllegalStateException("Fixture refresh failed");}).when(forecasts).build(tenant,store);
  try{
   forecasts.read(tenant,store);awaitFailure(forecasts,tenant,store);
   for(int n=0;n<8;n++)assertNotNull(forecasts.read(tenant,store).error());assertEquals(1,calls.get());
   forecasts.refresh(tenant,store);awaitFailure(forecasts,tenant,store);assertEquals(2,calls.get());
  }finally{forecasts.close();}
 }
 void awaitFailure(FbaReplenishmentForecasts forecasts,UUID tenant,UUID store)throws Exception {
  long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
  while(System.nanoTime()<until){var state=forecasts.read(tenant,store);if(!state.refreshing()&&state.error()!=null)return;Thread.sleep(5);}
  fail("Expected a visible completed refresh failure");
 }
 @Test void legacyCachedSnapshotWithoutFormatVersionIsReadableAndRequestsRefresh(){var value=new tools.jackson.databind.ObjectMapper().readValue("{\"generatedAt\":\"2026-11-17T00:00:00Z\",\"rows\":[],\"supply\":[],\"events\":[]}",Snapshot.class);assertEquals(0,value.formatVersion());}
 @Test void listingConditionUpgradeDoesNotDependOnOrReplaceQueuedForecast(){var policy=FbaReplenishmentMath.Policy.defaults();var demand=new FbaReplenishmentMath.Demand(0,0,0,null,null);var recommendation=FbaReplenishmentMath.estimate(LocalDate.of(2026,11,18),demand,policy,null,true,300,List.of(),List.of());var row=new Row("NEW-looking-sku","Product",null,"Brand",policy,demand,null,List.of(),recommendation,300,0,List.of(),List.of(),null);var old=new Snapshot(end,List.of(row),List.of(),List.of(),2);var fresh=conditions(old,Map.of(row.sku(),"USED_GOOD"));assertEquals("USED_GOOD",fresh.rows().getFirst().condition());assertSame(recommendation,fresh.rows().getFirst().recommendation());assertEquals(300,fresh.rows().getFirst().warehouse());assertEquals(old.generatedAt(),fresh.generatedAt());assertEquals("UNKNOWN",conditions(old,Map.of()).rows().getFirst().condition());}
}
