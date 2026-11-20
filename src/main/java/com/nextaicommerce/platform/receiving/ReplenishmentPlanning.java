package com.nextaicommerce.platform.receiving;

/** First, deliberately simple stock-days model. Quantities are component eaches. */
public final class ReplenishmentPlanning {
 private ReplenishmentPlanning() {}
 public record Policy(int lead,int low,int target,int overstock) {
  public Policy {
   if(lead<0||lead>180||low<1||low>365||target<low||target>365||overstock<target||overstock>730)
    throw new IllegalArgumentException("Use lead time 0–180 days and ordered thresholds: low ≤ healthy target ≤ overstock.");
  }
 }
 public record Estimate(double daily,double target,long cases,String status) {}
 public static Estimate estimate(double available,double demand,double pack,Policy policy) {
  return estimate(available,demand,pack,policy,2);
 }
 public static Estimate estimate(double available,double demand,double pack,Policy policy,double minimum) {
  if(!Double.isFinite(available)||!Double.isFinite(demand)||!Double.isFinite(pack)||demand<=0||pack<=0)
   return new Estimate(0,0,0,"unknown");
  double daily=demand/28d,stock=Math.max(0,available);
  double target=Math.max(minimum,Math.ceil(daily*(policy.lead()+policy.target())));
  double low=Math.max(minimum,daily*(policy.lead()+policy.low()));
  // Only reorder at/below the low threshold, rather than topping up every healthy item.
  long cases=stock<=low?(long)Math.ceil(Math.max(0,target-stock)/pack):0;
  String status=stock==0?"oos":stock<minimum||stock<=daily*policy.low()?"low":stock>Math.max(minimum,daily*policy.overstock())?"overstock":"healthy";
  return new Estimate(daily,target,cases,status);
 }
}
