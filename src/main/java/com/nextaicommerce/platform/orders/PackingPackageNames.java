package com.nextaicommerce.platform.orders;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Account-owned aliases; never rewrites source orders, purchases or printed label snapshots. */
@Repository
public class PackingPackageNames {
 private final JdbcTemplate jdbc;
 public PackingPackageNames(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static final String SOURCES="""
 SELECT packaging name FROM temporary_order_packaging_lookup WHERE tenant_id=?
 UNION ALL SELECT package_name FROM shopify_orders WHERE tenant_id=? AND package_name IS NOT NULL
 UNION ALL SELECT name FROM shipping_package_profiles WHERE tenant_id=? AND status='ACTIVE'
 UNION ALL SELECT unnest(ARRAY['XSmall','Pak','UPS OMBox','Medium','Large','XLarge'])
 UNION ALL SELECT display_name FROM packing_package_aliases WHERE tenant_id=?
 """;
 public record Choice(String name,java.math.BigDecimal cost,String state,String currency,java.time.LocalDate date,String carrier,java.time.LocalDate effectiveFrom,String rateSource){}
 @Transactional(readOnly=true) public List<Choice> choices(UUID tenant,List<com.nextaicommerce.platform.profit.ProfitShippingRates.Rate> rates,java.time.LocalDate date){
  scope(tenant);
  var rows=jdbc.queryForList("SELECT DISTINCT packing_package_display(?,name) display,packing_package_cost(?,name) cost_name FROM ("+SOURCES+") s WHERE nullif(btrim(name),'') IS NOT NULL",tenant,tenant,tenant,tenant,tenant,tenant);
  var profiles=jdbc.queryForList("SELECT name,coalesce(preferred_carrier,'') carrier FROM shipping_package_profiles WHERE tenant_id=? AND status='ACTIVE'",tenant);
  var independent=new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc).packageRates(tenant,rows.stream().map(r->Objects.toString(r.get("cost_name"),"")).toList());
  var result=new ArrayList<Choice>();
  for(String name:namesInScope(tenant)){
   var types=new LinkedHashSet<com.nextaicommerce.platform.profit.ProfitShippingRates.PackageType>();var carriers=new LinkedHashSet<String>();boolean unknown=false;
   for(var row:rows)if(name.equalsIgnoreCase(clean(Objects.toString(row.get("display"),"")))){String identity=Objects.toString(row.get("cost_name"),"");
    var matched=profiles.stream().filter(p->Objects.toString(p.get("name"),"").equalsIgnoreCase(identity)).toList();
    if(matched.isEmpty()){var type=com.nextaicommerce.platform.profit.ProfitRepository.packageType("",identity,"");if(type==null)unknown=true;else types.add(type);}
    else for(var profile:matched){String carrier=Objects.toString(profile.get("carrier"),"");var type=com.nextaicommerce.platform.profit.ProfitRepository.packageType("",identity,carrier);if(type==null)unknown=true;else types.add(type);if(!carrier.isBlank())carriers.add(carrier);}
   }
   var cost=types.size()==1&&!unknown?com.nextaicommerce.platform.profit.ProfitShippingRates.cost(rates,types.iterator().next(),date):null;
   var identities=rows.stream().filter(r->name.equalsIgnoreCase(clean(Objects.toString(r.get("display"),"")))).map(r->Objects.toString(r.get("cost_name"),"")).map(v->v.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT)).distinct().toList();
   java.time.LocalDate effective=null;String rateSource="";
   if(identities.size()==1){String identity=identities.getFirst();var rate=independent.applicable(identity,date);cost=independent.cost(identity,date,cost);
    if(rate!=null){effective=rate.effectiveFrom();rateSource="PACKAGE";}
    else if(cost!=null){effective=rates.stream().filter(r->types.contains(r.packageType())&&!r.effectiveFrom().isAfter(date)).map(r->r.effectiveFrom()).max(java.util.Comparator.naturalOrder()).orElse(null);rateSource="INHERITED";}
   }else cost=null;
   result.add(new Choice(name,cost,identities.size()>1?"VARIES":cost==null?"UNKNOWN":"KNOWN","USD",date,carriers.size()==1?carriers.iterator().next():"",effective,rateSource));
  }
  return List.copyOf(result);
 }
 public record History(String name,String currency,List<com.nextaicommerce.platform.profit.ProfitCostRepository.PackageRate> rates,Choice baseline){}
 @Transactional(readOnly=true) public History history(UUID tenant,String name,List<com.nextaicommerce.platform.profit.ProfitShippingRates.Rate> inherited,java.time.LocalDate date){
  scope(tenant);String current=existing(tenant,clean(name));if(current==null)throw new IllegalArgumentException("This package changed. Refresh and try again.");
  String identity=identity(tenant,current);var data=new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc).packageRates(tenant,List.of(identity));
  var baseline=choices(tenant,inherited,date).stream().filter(c->c.name().equals(current)).findFirst().orElseThrow();
  return new History(current,"USD",data.entries().getOrDefault(key(identity),List.of()),baseline);
 }
 private String identity(UUID tenant,String name){
  var identities=jdbc.queryForList("SELECT DISTINCT packing_package_key(packing_package_cost(?,name)) FROM ("+SOURCES+") s WHERE packing_package_key(packing_package_display(?,name))=packing_package_key(?)",String.class,tenant,tenant,tenant,tenant,tenant,tenant,name);
  if(identities.size()!=1)throw new IllegalArgumentException("This package has conflicting cost identities. Resolve its assignments before changing the rate.");return identities.getFirst();
 }
 @Transactional public void saveRate(UUID tenant,String name,java.time.LocalDate date,java.math.BigDecimal amount,String actor){
  scope(tenant);if(date==null||date.isBefore(com.nextaicommerce.platform.profit.ProfitShippingRates.START)||amount==null||amount.signum()<0||amount.scale()>2||amount.compareTo(new java.math.BigDecimal("9999999999.99"))>0)throw new IllegalArgumentException("Enter a nonnegative cost with two decimals and an effective date on or after 01/01/25.");
  if(actor==null||actor.isBlank())throw new IllegalArgumentException("Sign in again.");
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  String current=existing(tenant,clean(name));if(current==null)throw new IllegalArgumentException("This package changed. Refresh and try again.");
  int inserted=jdbc.update("INSERT INTO packing_package_rates(tenant_id,package_key,currency,effective_from,amount,created_by) VALUES (?,?,'USD',?,?,?) ON CONFLICT DO NOTHING",tenant,identity(tenant,current),date,amount,actor);
  if(inserted!=1)throw new IllegalArgumentException("A rate already exists for this package and date. Choose another effective date.");
 }
 /** Called before saving a new free-format package, in the caller's transaction. */
 @Transactional public void registerCustom(UUID tenant,String name){
  scope(tenant);name=clean(name);jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  if(existing(tenant,name)==null)jdbc.update("INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name,independent_rate_only) VALUES (?,?,?,?,true) ON CONFLICT DO NOTHING",tenant,key(name),name,name);
 }
 public record Change(String source,String destination,String operation,int affectedAssignments,String revision){}
 @Transactional(readOnly=true) public List<String> names(UUID tenant){scope(tenant);return namesInScope(tenant);}
 private List<String> namesInScope(UUID tenant){var unique=new LinkedHashMap<String,String>();for(String name:jdbc.queryForList("SELECT DISTINCT packing_package_display(?,name) FROM ("+SOURCES+") s WHERE nullif(btrim(name),'') IS NOT NULL ORDER BY 1",String.class,tenant,tenant,tenant,tenant,tenant)){String value=clean(name);unique.putIfAbsent(value.toLowerCase(Locale.ROOT),value);}return List.copyOf(unique.values());}
 private void scope(UUID tenant){Objects.requireNonNull(tenant);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 static String clean(String value){String name=value==null?"":value.trim().replaceAll("\\s+"," ");if(name.isEmpty()||name.length()>120)throw new IllegalArgumentException("Enter a package name of up to 120 characters.");return name;}
 private String key(String value){return jdbc.queryForObject("SELECT packing_package_key(?)",String.class,value);}
 private String existing(UUID tenant,String name){String normalized=key(name);return namesInScope(tenant).stream().filter(n->clean(n).toLowerCase(Locale.ROOT).equals(normalized)).findFirst().orElse(null);}
 @Transactional(readOnly=true) public Change preview(UUID tenant,String source,String destination){scope(tenant);return inspect(tenant,clean(source),clean(destination));}
 private Change inspect(UUID tenant,String source,String destination){
  String current=existing(tenant,source);if(current==null)throw new IllegalArgumentException("This package changed. Refresh and try again.");
  String target=existing(tenant,destination);if(target!=null&&key(target).equals(key(current)))throw new IllegalArgumentException("Choose a different package name.");
  String operation=target==null?"RENAME":"MERGE",name=target==null?destination:target;
  int count=jdbc.queryForObject("""
   SELECT (SELECT count(*) FROM temporary_order_packaging_lookup WHERE tenant_id=? AND packing_package_key(packing_package_display(tenant_id,packaging))=packing_package_key(?))
    +(SELECT count(*) FROM shopify_orders WHERE tenant_id=? AND packing_package_key(packing_package_display(tenant_id,package_name))=packing_package_key(?))
    +(SELECT count(*) FROM marketplace_sku_package_defaults d JOIN shipping_package_profiles p ON p.tenant_id=d.tenant_id AND p.id=d.package_profile_id WHERE d.tenant_id=? AND packing_package_key(packing_package_display(p.tenant_id,p.name))=packing_package_key(?))
   """,Integer.class,tenant,current,tenant,current,tenant,current);
  String revision=jdbc.queryForObject("SELECT md5(coalesce(string_agg(source_key||':'||display_name||':'||cost_name||':'||updated_at::text,'|' ORDER BY source_key),'')) FROM packing_package_aliases WHERE tenant_id=?",String.class,tenant);
  return new Change(current,name,operation,count,revision);
 }
 @Transactional public Change apply(UUID tenant,String source,String destination,String revision,int expected,String actor){
  scope(tenant);if(actor==null||actor.isBlank())throw new IllegalArgumentException("Sign in again.");
  // Serialize management within the account, including first-use aliases.
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  Change change=inspect(tenant,clean(source),clean(destination));
  if(!Objects.equals(change.revision(),revision)||change.affectedAssignments()!=expected)throw new IllegalArgumentException("Package assignments changed. Review again before saving.");
  String targetCost=jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,change.destination());
  var originals=jdbc.queryForList("SELECT DISTINCT name FROM ("+SOURCES+") s WHERE packing_package_key(packing_package_display(?,name))=packing_package_key(?)",String.class,tenant,tenant,tenant,tenant,tenant,change.source());
  // Include the current display name so later Save operations retain the original cost identity.
  var all=new LinkedHashSet<>(originals);all.add(change.source());all.add(change.destination());
  String sourceCost=jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,change.source());
  for(String raw:all){String cost="MERGE".equals(change.operation())?targetCost:jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,raw);
   if(key(raw).equals(key(change.destination())))cost="MERGE".equals(change.operation())?targetCost:sourceCost;
   jdbc.update("INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name,cost_routed) VALUES (?,?,?,?,?) ON CONFLICT(tenant_id,source_key) DO UPDATE SET display_name=EXCLUDED.display_name,cost_name=EXCLUDED.cost_name,cost_routed=EXCLUDED.cost_routed,updated_at=now()",tenant,key(raw),change.destination(),cost,"MERGE".equals(change.operation())||Boolean.TRUE.equals(jdbc.queryForObject("SELECT packing_package_merged(?,?)",Boolean.class,tenant,raw)));
  }
  jdbc.update("INSERT INTO packing_package_name_events(tenant_id,source_name,destination_name,operation,affected_assignments,actor) VALUES (?,?,?,?,?,?)",tenant,change.source(),change.destination(),change.operation(),change.affectedAssignments(),actor);
  return change;
 }
}
