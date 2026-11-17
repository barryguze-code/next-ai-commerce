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
 private static final String MANAGED_SOURCES=SOURCES.replace("UNION ALL SELECT unnest(ARRAY['XSmall','Pak','UPS OMBox','Medium','Large','XLarge'])\n", "");
 private static final Map<String,String> LEGACY=Map.of("FEDEX_XSMALL","XSmall","FEDEX_PAK","Pak","FEDEX_SMALL","Small","FEDEX_MEDIUM","Medium","FEDEX_LARGE","Large","FEDEX_XLARGE","XLarge");
 public record Dimensions(java.math.BigDecimal length,java.math.BigDecimal width,java.math.BigDecimal height,String unit,long revision){}
 public record Choice(String name,java.math.BigDecimal cost,String state,String currency,java.time.LocalDate date,String carrier,java.time.LocalDate effectiveFrom,String rateSource,List<String> legacyKeys,Dimensions dimensions){
  public Choice(String name,java.math.BigDecimal cost,String state,String currency,java.time.LocalDate date,String carrier,java.time.LocalDate effectiveFrom,String rateSource){this(name,cost,state,currency,date,carrier,effectiveFrom,rateSource,List.of(),null);}
 }

 @Transactional(readOnly=true) public List<Choice> choices(UUID tenant,List<com.nextaicommerce.platform.profit.ProfitShippingRates.Rate> rates,java.time.LocalDate date){
  scope(tenant);
  var rows=jdbc.queryForList("SELECT DISTINCT packing_package_display(?,name) display,packing_package_cost(?,name) cost_name FROM (SELECT DISTINCT name FROM ("+SOURCES+") raw WHERE nullif(btrim(name),'') IS NOT NULL) s",tenant,tenant,tenant,tenant,tenant,tenant);
  var profiles=jdbc.queryForList("SELECT name,coalesce(preferred_carrier,'') carrier,length,width,height,dimension_unit FROM shipping_package_profiles WHERE tenant_id=? AND status='ACTIVE'",tenant);
  var independent=new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc).packageRates(tenant,rows.stream().map(r->Objects.toString(r.get("cost_name"),"")).toList());
  var dimensionRows=jdbc.queryForList("SELECT * FROM packing_package_dimensions WHERE tenant_id=?",tenant);
  var aliases=jdbc.queryForList("SELECT source_key,display_name FROM packing_package_aliases WHERE tenant_id=?",tenant);
  var managed=managedNames(tenant);
  var result=new ArrayList<Choice>();
  for(String name:managed){
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
   var legacy=new LinkedHashSet<String>();
   for(var alias:aliases)if(name.equalsIgnoreCase(Objects.toString(alias.get("display_name"),"")))legacy.add(Objects.toString(alias.get("source_key"),""));
   for(var entry:LEGACY.entrySet()){
    String display=aliases.stream().filter(a->Objects.toString(a.get("source_key"),"").equals(entry.getValue().toLowerCase(Locale.ROOT))).map(a->Objects.toString(a.get("display_name"),"")).findFirst().orElse(entry.getValue());
    if(name.equalsIgnoreCase(display))legacy.add(entry.getKey());
   }
   Dimensions dimensions=null;
   if(identities.size()==1){var identity=identities.getFirst();var stored=dimensionRows.stream().filter(d->identity.equals(d.get("package_key"))).findFirst();
    if(stored.isPresent()){var d=stored.get();dimensions=new Dimensions((java.math.BigDecimal)d.get("length"),(java.math.BigDecimal)d.get("width"),(java.math.BigDecimal)d.get("height"),Objects.toString(d.get("dimension_unit")),((Number)d.get("revision")).longValue());}
    else{var matching=profiles.stream().filter(p->identity.equals(Objects.toString(p.get("name"),"").trim().toLowerCase(Locale.ROOT))).map(p->new Dimensions((java.math.BigDecimal)p.get("length"),(java.math.BigDecimal)p.get("width"),(java.math.BigDecimal)p.get("height"),Objects.toString(p.get("dimension_unit")),0)).distinct().toList();if(matching.size()==1)dimensions=matching.getFirst();}
   }
   result.add(new Choice(name,cost,identities.size()>1?"VARIES":cost==null?"UNKNOWN":"KNOWN","USD",date,carriers.size()==1?carriers.iterator().next():"",effective,rateSource,List.copyOf(legacy),dimensions));
  }
  return List.copyOf(result);
 }
 @Transactional public void saveDimensions(UUID tenant,String name,Dimensions dimensions,String actor){
  scope(tenant);if(actor==null||actor.isBlank())throw new IllegalArgumentException("Sign in again.");
  if(dimensions==null||dimensions.revision()<0||!List.of("inches","centimeters").contains(dimensions.unit()))throw new IllegalArgumentException("Choose inches or centimeters.");
  boolean empty=dimensions.length()==null&&dimensions.width()==null&&dimensions.height()==null;
  if(!empty)for(var value:Arrays.asList(dimensions.length(),dimensions.width(),dimensions.height()))if(value==null||value.signum()<=0||value.scale()>3||value.compareTo(new java.math.BigDecimal("999999999.999"))>0)throw new IllegalArgumentException("Enter positive length, width and height with up to three decimals.");
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  String current=existing(tenant,clean(name));if(current==null)throw new IllegalArgumentException("This package changed. Refresh and try again.");String key=key(identity(tenant,current));
  if(empty){int removed=jdbc.update("DELETE FROM packing_package_dimensions WHERE tenant_id=? AND package_key=? AND revision=?",tenant,key,dimensions.revision());if(dimensions.revision()>0&&removed!=1)throw new IllegalArgumentException("These dimensions changed. Reopen the package before editing.");return;}
  int updated=dimensions.revision()==0?jdbc.update("INSERT INTO packing_package_dimensions(tenant_id,package_key,length,width,height,dimension_unit,updated_by) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",tenant,key,dimensions.length(),dimensions.width(),dimensions.height(),dimensions.unit(),actor):jdbc.update("UPDATE packing_package_dimensions SET length=?,width=?,height=?,dimension_unit=?,revision=revision+1,updated_by=?,updated_at=now() WHERE tenant_id=? AND package_key=? AND revision=?",dimensions.length(),dimensions.width(),dimensions.height(),dimensions.unit(),actor,tenant,key,dimensions.revision());
  if(updated!=1)throw new IllegalArgumentException("These dimensions changed. Reopen the package before editing.");
 }
 public record RateCorrection(String oldDate,java.math.BigDecimal oldAmount,String newDate,java.math.BigDecimal newAmount,String actor,java.time.Instant changedAt){}
 public record History(String name,String currency,List<com.nextaicommerce.platform.profit.ProfitCostRepository.PackageRate> rates,Choice baseline,List<RateCorrection> corrections){}
 @Transactional(readOnly=true) public History history(UUID tenant,String name,List<com.nextaicommerce.platform.profit.ProfitShippingRates.Rate> inherited,java.time.LocalDate date){
  scope(tenant);String current=existing(tenant,clean(name));if(current==null)throw new IllegalArgumentException("This package changed. Refresh and try again.");
  String identity=identity(tenant,current);var data=new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc).packageRates(tenant,List.of(identity));
  var baseline=choices(tenant,inherited,date).stream().filter(c->c.name().equals(current)).findFirst().orElseThrow();
  var corrections=jdbc.query("SELECT e.details->>'oldDate',(e.details->>'oldAmount')::numeric,e.details->>'newDate',(e.details->>'newAmount')::numeric,u.display_name,e.created_at FROM audit_events e JOIN app_users u ON u.id=e.actor_user_id WHERE e.tenant_id=? AND e.action='PACKING_PACKAGE_RATE_CHANGED' AND e.target_type='PACKING_PACKAGE' AND e.target_id=? ORDER BY e.created_at DESC LIMIT 100",(rs,n)->new RateCorrection(rs.getString(1),rs.getBigDecimal(2),rs.getString(3),rs.getBigDecimal(4),rs.getString(5),rs.getTimestamp(6).toInstant()),tenant,key(identity));
  return new History(current,"USD",data.entries().getOrDefault(key(identity),List.of()),baseline,corrections);
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
 public record DetailsChange(String name,Dimensions dimensions,boolean rateChanged,java.time.LocalDate originalDate,
  java.math.BigDecimal originalAmount,String originalSource,java.time.LocalDate effectiveFrom,java.math.BigDecimal amount){}
 /** Atomic row edit. Correct package-specific dates without rewriting shared carrier rates or order overrides. */
 @Transactional public void saveDetails(UUID tenant,DetailsChange change,String actor){
  scope(tenant);if(actor==null||actor.isBlank())throw new IllegalArgumentException("Sign in again.");
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  if(change.rateChanged()){
   var date=change.effectiveFrom();var amount=change.amount();
   if(date==null||date.isBefore(com.nextaicommerce.platform.profit.ProfitShippingRates.START)||amount==null||amount.signum()<0||amount.scale()>2||amount.compareTo(new java.math.BigDecimal("9999999999.99"))>0)throw new IllegalArgumentException("Enter a valid effective date and a nonnegative cost with two decimals.");
   var costs=new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc);
   var current=choices(tenant,costs.rates(tenant),java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles"))).stream().filter(c->c.name().equals(change.name())).findFirst().orElseThrow(()->new IllegalArgumentException("This package changed. Reload it before editing."));
   boolean sameAmount=current.cost()==null?change.originalAmount()==null:change.originalAmount()!=null&&current.cost().compareTo(change.originalAmount())==0;
   if(!sameAmount||!Objects.equals(current.effectiveFrom(),change.originalDate())||!Objects.equals(current.rateSource(),change.originalSource()))throw new IllegalArgumentException("This rate changed. Reload the package before editing; your entries are kept.");
   String identity=identity(tenant,current.name());
   int actorCount=jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)",Integer.class,actor);if(actorCount!=1)throw new IllegalArgumentException("Sign in again before changing a rate.");
   if("PACKAGE".equals(current.rateSource())){
    if(!date.equals(change.originalDate())&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM packing_package_rates WHERE tenant_id=? AND package_key=? AND currency='USD' AND effective_from=?)",Boolean.class,tenant,identity,date)))throw new IllegalArgumentException("Another rate already starts on this date. Choose a different effective date.");
    int updated=jdbc.update("UPDATE packing_package_rates SET effective_from=?,amount=?,created_by=?,created_at=clock_timestamp() WHERE tenant_id=? AND package_key=? AND currency='USD' AND effective_from=? AND amount=?",date,amount,actor,tenant,identity,change.originalDate(),change.originalAmount());
    if(updated!=1)throw new IllegalArgumentException("This rate changed. Reload the package before editing.");
   }else saveRate(tenant,current.name(),date,amount,actor);
   jdbc.update("""
    INSERT INTO audit_events(tenant_id,actor_user_id,action,target_type,target_id,details)
    SELECT ?,id,'PACKING_PACKAGE_RATE_CHANGED','PACKING_PACKAGE',?,
     jsonb_build_object('oldDate',?::text,'oldAmount',?::numeric,'oldSource',?::text,'newDate',?::text,'newAmount',?::numeric)
    FROM app_users WHERE lower(email)=lower(?)
    """,tenant,identity,change.originalDate()==null?null:change.originalDate().toString(),change.originalAmount(),change.originalSource(),date.toString(),amount,actor);
  }
  if(change.dimensions()!=null)saveDimensions(tenant,change.name(),change.dimensions(),actor);
 }
 /** Called before saving a new free-format package, in the caller's transaction. */
 @Transactional public void registerCustom(UUID tenant,String name){
  scope(tenant);name=clean(name);jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  if(!managedNames(tenant).stream().map(n->n.toLowerCase(Locale.ROOT)).toList().contains(name.toLowerCase(Locale.ROOT)))jdbc.update("INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name,independent_rate_only) VALUES (?,?,?,?,true) ON CONFLICT DO NOTHING",tenant,key(name),name,name);
 }
 public record PackageUsage(String category,long count){}
 public record Removal(String name,boolean removable,List<PackageUsage> dependencies){}
 @Transactional(readOnly=true) public Removal removal(UUID tenant,String name){scope(tenant);return inspectRemoval(tenant,clean(name));}
 private Removal inspectRemoval(UUID tenant,String name){
  String current=existing(tenant,name);
  // An old merged name is already absent. Never resolve it into deletion of the survivor.
  if(current==null)throw new IllegalArgumentException("This package is no longer in the list. Refresh packages.");
  var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("tenant",tenant).addValue("name",current);
  var usage=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
   SELECT category,links FROM (
    SELECT 'Order packing records' category,count(*) links FROM temporary_order_packaging_lookup WHERE tenant_id=:tenant AND packing_package_key(packing_package_display(tenant_id,packaging))=packing_package_key(:name)
    UNION ALL SELECT 'Shopify orders',count(*) FROM shopify_orders WHERE tenant_id=:tenant AND packing_package_key(packing_package_display(tenant_id,package_name))=packing_package_key(:name)
    UNION ALL SELECT 'Order cost records',count(*) FROM profit_order_packages WHERE tenant_id=:tenant AND packing_package_key(packing_package_display(tenant_id,description))=packing_package_key(:name)
    UNION ALL SELECT 'SKU package assignments',count(*) FROM profit_sku_packages WHERE tenant_id=:tenant AND packing_package_key(packing_package_display(tenant_id,description))=packing_package_key(:name)
    UNION ALL SELECT 'Saved packaging matches',count(*) FROM account_package_recipes r WHERE tenant_id=:tenant AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(r.packages) p(name) WHERE packing_package_key(packing_package_display(r.tenant_id,p.name))=packing_package_key(:name))
    UNION ALL SELECT 'Legacy SKU assignments',count(*) FROM amazon_listings WHERE tenant_id=:tenant AND packing_package_key(packing_package_display(tenant_id,CASE profit_package_type WHEN 'FEDEX_XSMALL' THEN 'XSmall' WHEN 'FEDEX_PAK' THEN 'Pak' WHEN 'FEDEX_SMALL' THEN 'Small' WHEN 'FEDEX_MEDIUM' THEN 'Medium' WHEN 'FEDEX_LARGE' THEN 'Large' WHEN 'FEDEX_XLARGE' THEN 'XLarge' ELSE profit_package_type END))=packing_package_key(:name)
    UNION ALL SELECT 'Shipping profile assignments',count(*) FROM (
     SELECT d.tenant_id,d.package_profile_id FROM marketplace_sku_package_defaults d WHERE d.tenant_id=:tenant
     UNION ALL SELECT d.tenant_id,d.package_profile_id FROM shipping_order_packages d WHERE d.tenant_id=:tenant
     UNION ALL SELECT d.tenant_id,d.package_profile_id FROM buy_shipping_shipments d WHERE d.tenant_id=:tenant
     UNION ALL SELECT d.tenant_id,d.package_profile_id FROM buy_shipping_batch_orders d WHERE d.tenant_id=:tenant
    ) used JOIN shipping_package_profiles p ON p.tenant_id=used.tenant_id AND p.id=used.package_profile_id WHERE packing_package_key(packing_package_display(p.tenant_id,p.name))=packing_package_key(:name)
   ) dependencies WHERE links>0 ORDER BY category
   """,params,(rs,n)->new PackageUsage(rs.getString(1),rs.getLong(2)));
  return new Removal(current,usage.isEmpty(),List.copyOf(usage));
 }
 @Transactional public void remove(UUID tenant,String name,String actor){
  scope(tenant);if(actor==null||actor.isBlank())throw new IllegalArgumentException("Sign in again.");
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,137))",Object.class,tenant.toString());
  // Briefly serialize definition removal with assignment writes; recheck after acquiring locks.
  jdbc.execute("LOCK TABLE temporary_order_packaging_lookup,shopify_orders,profit_order_packages,profit_sku_packages,account_package_recipes,amazon_listings,marketplace_sku_package_defaults,shipping_order_packages,buy_shipping_shipments,buy_shipping_batch_orders,shipping_package_profiles IN SHARE ROW EXCLUSIVE MODE");
  var review=inspectRemoval(tenant,clean(name));
  if(!review.removable())throw new IllegalArgumentException("Package is still used: "+review.dependencies().stream().map(d->d.category()+" ("+d.count()+")").collect(java.util.stream.Collectors.joining(", "))+". Review these linked records before deleting. Nothing was deleted.");
  int actors=jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)",Integer.class,actor);if(actors!=1)throw new IllegalArgumentException("Sign in again.");
  jdbc.update("UPDATE shipping_package_profiles SET status='INACTIVE',updated_at=now() WHERE tenant_id=? AND packing_package_key(packing_package_display(tenant_id,name))=packing_package_key(?)",tenant,review.name());
  jdbc.update("DELETE FROM packing_package_aliases WHERE tenant_id=? AND packing_package_key(display_name)=packing_package_key(?)",tenant,review.name());
  jdbc.update("INSERT INTO audit_events(tenant_id,actor_user_id,action,target_type,target_id,details) SELECT ?,id,'PACKING_PACKAGE_DELETED','PACKING_PACKAGE',?,jsonb_build_object('name',?::text) FROM app_users WHERE lower(email)=lower(?)",tenant,key(review.name()),review.name(),actor);
  // Dated prices and dimension/audit metadata remain available; no order or stock records are removed.
 }
 public record Change(String source,String destination,String operation,int affectedAssignments,String revision){}
 @Transactional(readOnly=true) public List<String> names(UUID tenant){scope(tenant);return managedNames(tenant);}
 private List<String> managedNames(UUID tenant){return jdbc.queryForList("SELECT DISTINCT packing_package_display(?,name) FROM (SELECT DISTINCT name FROM ("+MANAGED_SOURCES+") raw WHERE nullif(btrim(name),'') IS NOT NULL) s ORDER BY 1",String.class,tenant,tenant,tenant,tenant,tenant).stream().filter(n->!LEGACY.containsKey(n)&&!n.equals("UPS")).toList();}
 private List<String> namesInScope(UUID tenant){var unique=new LinkedHashMap<String,String>();for(String name:jdbc.queryForList("SELECT DISTINCT packing_package_display(?,name) FROM (SELECT DISTINCT name FROM ("+SOURCES+") raw WHERE nullif(btrim(name),'') IS NOT NULL) s ORDER BY 1",String.class,tenant,tenant,tenant,tenant,tenant)){String value=clean(name);unique.putIfAbsent(value.toLowerCase(Locale.ROOT),value);}return List.copyOf(unique.values());}
 private void scope(UUID tenant){Objects.requireNonNull(tenant);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 static String clean(String value){String name=value==null?"":value.trim().replaceAll("\\s+"," ");if(name.isEmpty()||name.length()>120)throw new IllegalArgumentException("Enter a package name of up to 120 characters.");return name;}
 private String key(String value){return jdbc.queryForObject("SELECT packing_package_key(?)",String.class,value);}
 private String existing(UUID tenant,String name){String normalized=key(name);return managedNames(tenant).stream().filter(n->clean(n).toLowerCase(Locale.ROOT).equals(normalized)).findFirst().orElse(null);}
 @Transactional(readOnly=true) public Change preview(UUID tenant,String source,String destination){scope(tenant);return inspect(tenant,clean(source),clean(destination));}
 private Change inspect(UUID tenant,String source,String destination){
  String current=existing(tenant,source);if(current==null)throw new IllegalArgumentException("This package changed. Refresh and try again.");
  String target=existing(tenant,destination);
  if(target!=null&&key(target).equals(key(current))){
   if(current.equals(destination))throw new IllegalArgumentException("Choose a different package name.");
   // A capitalization-only edit changes presentation, not the package or its cost identity.
   target=null;
  }
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
  var all=new LinkedHashSet<>(originals);all.addAll(jdbc.queryForList("SELECT source_key FROM packing_package_aliases WHERE tenant_id=? AND packing_package_key(display_name)=packing_package_key(?)",String.class,tenant,change.source()));all.add(change.source());all.add(change.destination());
  String sourceCost=jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,change.source());
  for(String raw:all){String cost="MERGE".equals(change.operation())?targetCost:jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,raw);
   if(key(raw).equals(key(change.destination())))cost="MERGE".equals(change.operation())?targetCost:sourceCost;
   jdbc.update("INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name,cost_routed) VALUES (?,?,?,?,?) ON CONFLICT(tenant_id,source_key) DO UPDATE SET display_name=EXCLUDED.display_name,cost_name=EXCLUDED.cost_name,cost_routed=EXCLUDED.cost_routed,updated_at=now()",tenant,key(raw),change.destination(),cost,"MERGE".equals(change.operation())||Boolean.TRUE.equals(jdbc.queryForObject("SELECT packing_package_merged(?,?)",Boolean.class,tenant,raw)));
  }
  jdbc.update("INSERT INTO packing_package_name_events(tenant_id,source_name,destination_name,operation,affected_assignments,actor) VALUES (?,?,?,?,?,?)",tenant,change.source(),change.destination(),change.operation(),change.affectedAssignments(),actor);
  return change;
 }
}
