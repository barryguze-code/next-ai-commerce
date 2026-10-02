package com.nextaicommerce.platform.receiving;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Report-based estimate, not continuous telemetry. Never fills gaps beyond 24 hours. */
public final class ReplenishmentAvailability {
 private ReplenishmentAvailability(){}
 public record Observation(Instant at,boolean inStock){}
 public record Coverage(double stocked,double known,double possible){
  public String percent(){return known<=0?"—":String.format(Locale.ROOT,"%.1f%%",100*stocked/known);}
  public String coverage(){return possible<=0?"0%":String.format(Locale.ROOT,"%.0f%%",Math.min(100,100*known/possible));}
 }
 static double activeSeconds(Instant from,Instant to,ZoneId zone){
  if(!to.isAfter(from))return 0;double seconds=0;
  for(LocalDate date=from.atZone(zone).toLocalDate();!date.isAfter(to.atZone(zone).toLocalDate());date=date.plusDays(1)){
   Instant start=date.atTime(4,30).atZone(zone).toInstant(),end=date.atTime(23,30).atZone(zone).toInstant();
   if(start.isBefore(from))start=from;if(end.isAfter(to))end=to;
   if(end.isAfter(start))seconds+=Duration.between(start,end).toMillis()/1000d;
  }return seconds;
 }
 public static Coverage calculate(List<Observation> observations,Instant from,Instant to,ZoneId zone){
  var sorted=observations.stream().sorted(Comparator.comparing(Observation::at)).toList();double known=0,stocked=0;
  for(int i=0;i<sorted.size();i++){
   var o=sorted.get(i);Instant end=o.at().plus(Duration.ofHours(24));
   if(i+1<sorted.size()&&sorted.get(i+1).at().isBefore(end))end=sorted.get(i+1).at();
   if(end.isAfter(to))end=to;Instant start=o.at().isBefore(from)?from:o.at();
   double seconds=activeSeconds(start,end,zone);known+=seconds;if(o.inStock())stocked+=seconds;
  }return new Coverage(stocked,known,activeSeconds(from,to,zone));
 }
 static String clean(String value){String s=value.trim();return s.startsWith("\"")&&s.endsWith("\"")&&s.length()>1?s.substring(1,s.length()-1).replace("\"\"","\""):s;}
 static String stockoutDays(List<Observation> values,Instant now){
  var sorted=values.stream().filter(o->!o.at().isAfter(now)).sorted(Comparator.comparing(Observation::at)).toList();
  if(sorted.isEmpty())return null;var last=sorted.get(sorted.size()-1);
  if(last.inStock()||last.at().plus(Duration.ofHours(24)).isBefore(now))return null;
  Instant start=last.at();for(int i=sorted.size()-2;i>=0;i--){var o=sorted.get(i);if(o.inStock()||o.at().plus(Duration.ofHours(24)).isBefore(start))break;start=o.at();}
  if(start.isBefore(now.minus(Duration.ofDays(28))))start=now.minus(Duration.ofDays(28));
  return String.format(Locale.ROOT,"%.1f",Duration.between(start,now).toSeconds()/86400d);
 }
 public static Map<String,Boolean> parse(String text){
  var result=new HashMap<String,Boolean>();String[] lines=text.split("\\R");if(lines.length==0)return result;
  var header=Arrays.stream(lines[0].replace("\uFEFF","").split("\\t",-1)).map(ReplenishmentAvailability::clean).toList();
  int sku=header.indexOf("seller-sku"),quantity=header.indexOf("quantity"),status=header.indexOf("status"),fulfillment=header.indexOf("fulfillment-channel");
  if(sku<0||quantity<0)return result;
  for(int i=1;i<lines.length;i++){String[] row=lines[i].split("\\t",-1);if(row.length<=Math.max(sku,quantity))continue;
   if(fulfillment>=0&&row.length>fulfillment&&Set.of("AMAZON","AFN","AMAZON_NA").contains(clean(row[fulfillment]).toUpperCase(Locale.ROOT)))continue;
   String state=status>=0&&row.length>status?clean(row[status]):"";
   try{int amount=Integer.parseInt(clean(row[quantity]));if(amount<0)continue;result.put(clean(row[sku]),amount>0&&(state.isEmpty()||state.equalsIgnoreCase("Active")));}
   catch(NumberFormatException ignored){} // Missing quantity is unknown, never zero.
  }return result;
 }
 @SuppressWarnings("unchecked")
 public static void enrich(JdbcTemplate jdbc,UUID tenant,List<Map<String,Object>> rows,Instant now){
  Map<String,List<Observation>> history=new HashMap<>();Map<String,Map<String,Object>> skus=new HashMap<>();
  for(var item:rows)for(var sku:(List<Map<String,Object>>)item.get("skus")){String key=sku.get("connection_id")+"|"+sku.get("sku");skus.put(key,sku);history.putIfAbsent(key,new ArrayList<>());}
  if(history.isEmpty())return;
  jdbc.query("SELECT marketplace_connection_id,received_at,text_payload FROM amazon_source_documents WHERE tenant_id=? AND source_type='LISTINGS_SNAPSHOT' AND received_at>=? AND received_at<=? AND text_payload IS NOT NULL ORDER BY received_at,id",
   (org.springframework.jdbc.core.RowCallbackHandler)rs->{String prefix=rs.getObject(1)+"|";Instant at=rs.getTimestamp(2).toInstant();parse(rs.getString(3)).forEach((sku,state)->{var values=history.get(prefix+sku);if(values!=null)values.add(new Observation(at,state));});},
   tenant,java.sql.Timestamp.from(now.minus(Duration.ofDays(29))),java.sql.Timestamp.from(now));
  Map<String,Coverage> results=new HashMap<>();
  skus.forEach((key,sku)->{ZoneId zone;try{zone=ZoneId.of("ATVPDKIKX0DER".equals(sku.get("marketplace_id"))?"America/Los_Angeles":Objects.toString(sku.get("reporting_timezone"),"UTC"));}catch(Exception e){zone=ZoneId.of("UTC");}
   results.put(key,calculate(history.get(key),now.minus(Duration.ofDays(28)),now,zone));});
  for(var item:rows){double stocked=0,known=0,possible=0;
   for(var sku:(List<Map<String,Object>>)item.get("skus")){String key=sku.get("connection_id")+"|"+sku.get("sku");var c=results.get(key);sku.put("in_stock",c.percent());sku.put("coverage",c.coverage());sku.put("oos_days",stockoutDays(history.get(key),now));stocked+=c.stocked();known+=c.known();possible+=c.possible();}
   var total=new Coverage(stocked,known,possible);item.put("in_stock",total.percent());item.put("coverage",total.coverage());
  }
 }
}
