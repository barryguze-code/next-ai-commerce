package com.nextaicommerce.platform.fba;

import java.time.*;
import java.util.*;

/** Pure planning in complete marketplace SKU units. No inventory writes or invented availability. */
public final class FbaReplenishmentMath {
 private FbaReplenishmentMath(){}
 public record Policy(int lowDays,int targetDays,int preparationDays,int transitDays,int receivingDays,Double dailyOverride,Boolean leadTimesConfirmed,Integer overstockDays){
  public Policy(int lowDays,int targetDays,int preparationDays,int transitDays,int receivingDays,Double dailyOverride,Boolean leadTimesConfirmed){this(lowDays,targetDays,preparationDays,transitDays,receivingDays,dailyOverride,leadTimesConfirmed,null);}
  public Policy(int lowDays,int targetDays,int preparationDays,int transitDays,int receivingDays,Double dailyOverride){this(lowDays,targetDays,preparationDays,transitDays,receivingDays,dailyOverride,true);}
  public Policy {if(overstockDays!=null&&(overstockDays<targetDays||overstockDays>365))throw new IllegalArgumentException("Overstock days must be at least Target and no more than 365.");if(leadTimesConfirmed==null)leadTimesConfirmed=false;if(lowDays<1||targetDays<lowDays||targetDays>365||preparationDays<0||transitDays<0||receivingDays<0||preparationDays+transitDays+receivingDays>365||dailyOverride!=null&&(!Double.isFinite(dailyOverride)||dailyOverride<0||dailyOverride>1000000))throw new IllegalArgumentException("Use ordered stock-day thresholds and nonnegative lead times/demand.");}
  public Integer overstockDays(){return overstockDays==null?Math.max(120,targetDays):overstockDays;}
  public int lead(){return preparationDays+transitDays+receivingDays;}
  public static Policy defaults(){return new Policy(28,60,0,0,0,null,false);}
 }
 public static Policy selectPolicy(Map<String,Policy> policies,UUID store,String sku,String brand){var account=policies.get("ACCOUNT");var brandPolicy=policies.get("BRAND:"+brand);var skuPolicy=policies.get("SKU:"+store+":"+sku);var chosen=skuPolicy!=null?skuPolicy:brandPolicy!=null?brandPolicy:account!=null?account:Policy.defaults();Integer configured=skuPolicy==null?null:skuPolicy.overstockDays;if(configured==null&&brandPolicy!=null)configured=brandPolicy.overstockDays;if(configured==null&&account!=null)configured=account.overstockDays;int resolved=Math.max(chosen.targetDays(),configured==null?120:configured);return chosen.overstockDays!=null?chosen:new Policy(chosen.lowDays(),chosen.targetDays(),chosen.preparationDays(),chosen.transitDays(),chosen.receivingDays(),chosen.dailyOverride(),chosen.leadTimesConfirmed(),resolved);}

 /** Legacy cache reclassification uses only its embedded policy/evidence; quantities never change. */
 public static Result displayClassification(Result r,Policy p){if(!"Healthy".equals(r.status())&&!"Overstock".equals(r.status()))return r;String status=r.cover()==null||r.cover()>p.overstockDays()?"Overstock":"Healthy";if(status.equals(r.status()))return r;return new Result(r.daily(),r.recommended(),r.feasible(),r.shortfall(),r.cover(),r.arrival(),r.stockout(),r.prepareBy(),status,r.warnings(),r.demandBeforeArrival(),r.demandAfterArrival());}
 public record Demand(double units30,double units90,int historyDays,Integer verifiedSellingDays30,Integer verifiedSellingDays90){
  public Demand {if(!Double.isFinite(units30)||!Double.isFinite(units90)||units30<0||units90<units30||historyDays<0||verifiedSellingDays30!=null&&(verifiedSellingDays30<1||verifiedSellingDays30>30)||verifiedSellingDays90!=null&&(verifiedSellingDays90<1||verifiedSellingDays90>90))throw new IllegalArgumentException("Invalid demand evidence.");}
  public double daily(){return .6*units30/(verifiedSellingDays30==null?30:verifiedSellingDays30)+.4*units90/(verifiedSellingDays90==null?90:verifiedSellingDays90);}
 }
 public record Event(UUID id,String name,LocalDate start,LocalDate end,Double uplift,boolean reliable,String source){
  public Event {if(name==null||name.isBlank()||start==null||end==null||end.isBefore(start)||end.isAfter(start.plusDays(366))||uplift!=null&&(!Double.isFinite(uplift)||uplift<0||uplift>10))throw new IllegalArgumentException("Enter verified event dates and uplift between 0 and 1000%.");}
  public boolean includes(LocalDate day){return !day.isBefore(start)&&!day.isAfter(end);}
 }
 public record FutureSupply(double units,LocalDate availableDate,boolean verified,boolean quantityCommitmentVerified){public FutureSupply(double units,LocalDate availableDate,boolean verified){this(units,availableDate,verified,false);}public FutureSupply{if(!Double.isFinite(units)||units<0)throw new IllegalArgumentException("Invalid future supply.");}}
 public record Result(Double daily,Long recommended,long feasible,Long shortfall,Double cover,LocalDate arrival,LocalDate stockout,LocalDate prepareBy,String status,List<String> warnings,double demandBeforeArrival,double demandAfterArrival){}
 /** Overlapping events use the largest verified uplift, once; conflict remains visible. */
 public static double demand(double daily,LocalDate start,int days,List<Event> events){double total=0;for(int n=0;n<days;n++){LocalDate day=start.plusDays(n);double uplift=events.stream().filter(e->e.reliable()&&e.uplift()!=null&&e.includes(day)).mapToDouble(Event::uplift).max().orElse(0);total+=daily*(1+uplift);}return total;}
 public static Result estimate(LocalDate today,Demand evidence,Policy policy,Double fulfillable,boolean stale,long warehouse,List<FutureSupply> inbound,List<Event> events){
  if(fulfillable!=null&&(!Double.isFinite(fulfillable)||fulfillable<0))throw new IllegalArgumentException("Invalid fulfillable stock evidence.");
  var warnings=new LinkedHashSet<String>();if(!policy.leadTimesConfirmed())warnings.add("Set and confirm arrival lead times. Quantity is provisional; arrival and preparation deadline are not established.");LocalDate arrival=today.plusDays(policy.lead());double daily=policy.dailyOverride()==null?evidence.daily():policy.dailyOverride();
  if(evidence.historyDays()<90&&policy.dailyOverride()==null)warnings.add("Limited history · expected daily demand can be entered.");
  LocalDate end=arrival.plusDays(policy.targetDays());
  if(events.stream().anyMatch(e->!e.end().isBefore(today)&&e.start().isBefore(end)&&(!e.reliable()||e.uplift()==null)))warnings.add("Seasonal impact needs review.");
  for(LocalDate day=today;day.isBefore(end);day=day.plusDays(1)){LocalDate at=day;if(events.stream().filter(e->e.reliable()&&e.uplift()!=null&&e.includes(at)).count()>1){warnings.add("Overlapping events · largest verified uplift applied once.");break;}}
  if(inbound.stream().anyMatch(s->!s.verified()||s.availableDate()==null))warnings.add("Uncertain inbound excluded from confirmed coverage.");
  if(stale||fulfillable==null){warnings.add("Refresh Amazon inventory before relying on this recommendation.");return new Result(daily,null,0,null,null,policy.leadTimesConfirmed()?arrival:null,null,null,"Inventory needs review",List.copyOf(warnings),0,0);}
  if(daily<=0){warnings.add("No supported demand · enter an expected daily demand.");return new Result(daily,0L,0,0L,null,policy.leadTimesConfirmed()?arrival:null,null,null,"Demand needs review",List.copyOf(warnings),0,0);}
  double before=demand(daily,today,policy.lead(),events),after=demand(daily,arrival,policy.targetDays(),events);
  // Supply is usable only on its verified date. Demand lost before a late arrival is
  // not a stock debt, and a past receipt must not be added to the observed stock again.
  double atArrival=projectedStock(fulfillable,daily,today,arrival,inbound,events);
  Projection projection=projection(fulfillable,daily,today,inbound,events);LocalDate stockout=projection.stockout();
  boolean reorder=stockout!=null&&stockout.isBefore(arrival.plusDays(policy.lowDays()));
  // Dated arrivals already included at our arrival are not deducted a second time.
  // Snapshot-reported undated commitments affect additional quantity, never today's buyability/date coverage.
  double committed=inbound.stream().filter(s->s.quantityCommitmentVerified()&&(s.availableDate()==null||s.availableDate().isAfter(today)&&s.availableDate().isBefore(end)&&(!s.verified()||s.availableDate().isAfter(arrival)))).mapToDouble(FutureSupply::units).sum();
  if(committed>0)warnings.add(java.math.BigDecimal.valueOf(committed).stripTrailingZeros().toPlainString()+" incoming SKU units counted toward additional quantity. Arrival timing remains unconfirmed; stockout risk is unchanged.");
  long recommended=reorder?Math.max(0,(long)Math.ceil(after-atArrival-committed)):0;
  long feasible=Math.min(Math.max(0,warehouse),recommended);
  Double cover=projection.coveredDays();
  LocalDate deadline=stockout==null?null:stockout.minusDays(policy.lead());
  if(deadline!=null&&deadline.isBefore(today))warnings.add("Action needed: arrival may miss stockout.");
  if(stockout==null)warnings.add("No projected stockout within the ten-year projection horizon.");
  for(var event:events)if(!event.start().isBefore(today)&&event.start().isBefore(arrival))warnings.add("Arrival may miss "+event.name()+".");
  String status=fulfillable<=0?"Out of stock":cover==null?"Overstock":cover<policy.lowDays()?"Low inventory":cover>policy.overstockDays()?"Overstock":"Healthy";
  return new Result(daily,recommended,feasible,recommended-feasible,cover,policy.leadTimesConfirmed()?arrival:null,stockout,policy.leadTimesConfirmed()?deadline:null,status,List.copyOf(warnings),before,after);
 }
 private static double arriving(List<FutureSupply> inbound,LocalDate day,LocalDate today){return inbound.stream().filter(s->s.verified()&&day.isAfter(today)&&day.equals(s.availableDate())).mapToDouble(FutureSupply::units).sum();}
 static double projectedStock(double stock,double daily,LocalDate today,LocalDate arrival,List<FutureSupply> inbound,List<Event> events){
  double remaining=Math.max(0,stock);
  for(LocalDate day=today;day.isBefore(arrival);day=day.plusDays(1))remaining=Math.max(0,remaining+arriving(inbound,day,today)-demand(daily,day,1,events));
  return remaining+arriving(inbound,arrival,today);
 }
 private record Projection(LocalDate stockout,Double coveredDays){}
 static LocalDate projectedStockout(double stock,double daily,LocalDate today,List<FutureSupply> inbound,List<Event> events){return projection(stock,daily,today,inbound,events).stockout();}
 private static Projection projection(double stock,double daily,LocalDate today,List<FutureSupply> inbound,List<Event> events){
  if(daily<=0)return new Projection(null,null);double remaining=Math.max(0,stock);
  for(int n=0;n<3660;n++){LocalDate day=today.plusDays(n);remaining+=arriving(inbound,day,today);double needed=demand(daily,day,1,events);if(remaining+1e-9<needed)return new Projection(day,n+remaining/needed);remaining=Math.max(0,remaining-needed);}
  return new Projection(null,null);
 }
 /** Same stockout date: higher effective daily sellable-SKU demand first. Unknown stays unknown. */
 public static int priority(Result left,String leftSku,Result right,String rightSku){
  int date=Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()).compare(left.stockout(),right.stockout());if(date!=0)return date;
  int units=Double.compare(right.daily()==null?0:right.daily(),left.daily()==null?0:left.daily());return units!=0?units:leftSku.compareTo(rightSku);
 }

 /** Allocate common components once, deterministically in the visible priority order. */
 public static Map<String,Long> feasible(Map<UUID,Long> stock,LinkedHashMap<String,Map<UUID,Long>> components,Map<String,Long> requested){
  var remaining=new HashMap<>(stock);var result=new LinkedHashMap<String,Long>();for(var row:components.entrySet()){long units=Math.max(0,requested.getOrDefault(row.getKey(),0L));if(row.getValue().isEmpty())units=0;for(var c:row.getValue().entrySet()){if(c.getValue()<=0)throw new IllegalArgumentException("Complete positive whole-unit mappings required.");units=Math.min(units,Math.max(0,remaining.getOrDefault(c.getKey(),0L))/c.getValue());}result.put(row.getKey(),units);long allocated=units;row.getValue().forEach((item,count)->remaining.merge(item,-Math.multiplyExact(allocated,count),Long::sum));}return result;
 }
 /** Complete SKU units must exist at an eligible origin; shared component stock is consumed once. */
 public static Map<String,Long> feasibleByWarehouse(Map<UUID,Map<UUID,Long>> stock,LinkedHashMap<String,Map<UUID,Long>> components,Map<String,Long> requested){
  var remaining=new TreeMap<UUID,Map<UUID,Long>>();stock.forEach((warehouse,items)->remaining.put(warehouse,new HashMap<>(items)));
  var result=new LinkedHashMap<String,Long>();
  for(var row:components.entrySet()){
   long needed=Math.max(0,requested.getOrDefault(row.getKey(),0L)),allocated=0;
   if(row.getValue().isEmpty()){result.put(row.getKey(),0L);continue;}
   for(var origin:remaining.values()){
    long units=needed;for(var c:row.getValue().entrySet()){if(c.getValue()<=0)throw new IllegalArgumentException("Complete positive whole-unit mappings required.");units=Math.min(units,Math.max(0,origin.getOrDefault(c.getKey(),0L))/c.getValue());}
    long take=units;row.getValue().forEach((item,each)->origin.merge(item,-Math.multiplyExact(take,each),Long::sum));allocated+=take;needed-=take;if(needed==0)break;
   }result.put(row.getKey(),allocated);
  }return result;
 }

}
