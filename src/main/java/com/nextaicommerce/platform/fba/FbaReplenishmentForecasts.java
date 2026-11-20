package com.nextaicommerce.platform.fba;

import java.time.*;import java.math.*;import java.util.*;import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.receiving.InventoryRepository;import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.fba.FbaReplenishmentMath.*;

/** Durable last-good account/store summary; page reads never aggregate order history or call Amazon. */
@Service
public class FbaReplenishmentForecasts {
 public record Component(UUID item,String name,String brand,long each,long warehouse,String itemCode,BigDecimal masterCase){public Component(UUID item,String name,String brand,long each,long warehouse){this(item,name,brand,each,warehouse,null,null);}}
 public record WarehouseStock(UUID warehouse,String name,String postal,Map<UUID,Long> items){}
 public record Performance(String weeks,String refunds,com.nextaicommerce.platform.orders.OrderRepository.SoldTotals totals){}
 public record Context(List<WarehouseStock> warehouses,Map<String,Performance> performance){public Context{if(warehouses==null)warehouses=List.of();if(performance==null)performance=Map.of();}public static Context empty(){return new Context(List.of(),Map.of());}}
 public record Row(String sku,String title,String image,String brand,Policy policy,Demand demand,FbaReadRepository.Stock amazon,List<Component> components,Result recommendation,long warehouse,long suggested,List<String> blockers,List<SeasonalComparison> seasons,String condition,String asin){public Row{if(condition==null)condition="UNKNOWN";}public Row(String sku,String title,String image,String brand,Policy policy,Demand demand,FbaReadRepository.Stock amazon,List<Component> components,Result recommendation,long warehouse,long suggested,List<String> blockers,List<SeasonalComparison> seasons,String condition){this(sku,title,image,brand,policy,demand,amazon,components,recommendation,warehouse,suggested,blockers,seasons,condition,null);}}
 public record ChannelSupply(UUID connection,String sku,String channel,double daily,double required,Double available,Double shortage){}
 public record Supply(UUID item,String name,double daily,long warehouse,double amazon,Double shortfall,List<String> warnings,List<ChannelSupply> channels){}
 public record SeasonalComparison(UUID id,String name,LocalDate start,LocalDate end,LocalDate previousStart,LocalDate previousEnd,double units,double baselineUnits,double availabilityCoverage,Double uplift,boolean applied,String message){}
 public record ScopedEvent(Event event,String brand,String sku,UUID connection,boolean disabled,LocalDate previousStart,LocalDate previousEnd){
  public ScopedEvent(Event event,String brand,String sku,UUID connection,boolean disabled){this(event,brand,sku,connection,disabled,null,null);}
  boolean applies(String rowBrand,String rowSku,UUID store){return !disabled&&(connection==null||connection.equals(store))&&(brand.isBlank()||brand.equals(rowBrand))&&(sku.isBlank()||sku.equals(rowSku));}}
 public record Snapshot(Instant generatedAt,List<Row> rows,List<Supply> supply,List<ScopedEvent> events,Integer formatVersion,Context context){public Snapshot{if(formatVersion==null)formatVersion=0;if(context==null)context=Context.empty();}public Snapshot(Instant generatedAt,List<Row> rows,List<Supply> supply,List<ScopedEvent> events,Integer formatVersion){this(generatedAt,rows,supply,events,formatVersion,Context.empty());}public Snapshot(Instant generatedAt,List<Row> rows,List<Supply> supply,List<ScopedEvent> events){this(generatedAt,rows,supply,events,6,Context.empty());}}
 public record Read(Snapshot snapshot,boolean refreshing,String error){}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final InventoryRepository inventory;private final FbaReadRepository fba;private final ObjectMapper json;private final com.nextaicommerce.platform.marketplace.MarketplaceSkuRelationships relationships;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.orders.OrderRepository orders;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.orders.SkuRefundHistory refunds;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"fba-replenishment");t.setDaemon(true);return t;});
 private final Set<String> pending=ConcurrentHashMap.newKeySet();private final Map<String,String> errors=new ConcurrentHashMap<>();
 public FbaReplenishmentForecasts(JdbcTemplate jdbc,TransactionTemplate tx,InventoryRepository inventory,FbaReadRepository fba,ObjectMapper json,com.nextaicommerce.platform.marketplace.MarketplaceSkuRelationships relationships){this.jdbc=jdbc;this.tx=tx;this.inventory=inventory;this.fba=fba;this.json=json;this.relationships=relationships;}
 void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 public Read read(UUID tenant,UUID connection){var dirty=tx.execute(s->{scope(tenant);return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_replenishment_forecasts WHERE tenant_id=? AND marketplace_connection_id=? AND invalidated_at>generated_at)",Boolean.class,tenant,connection));});var snapshot=tx.execute(s->{scope(tenant);return jdbc.query("SELECT payload::text FROM fba_replenishment_forecasts WHERE tenant_id=? AND marketplace_connection_id=?",(r,n)->json.readValue(r.getString(1),Snapshot.class),tenant,connection).stream().findFirst().orElse(null);});String key=tenant+":"+connection;if(dirty||snapshot==null||snapshot.formatVersion()!=6||snapshot.generatedAt().isBefore(Instant.now().minusSeconds(21600)))schedule(tenant,connection,false);var current=snapshot==null||snapshot.formatVersion()!=6?null:withListingConditions(tenant,connection,snapshot);return new Read(current,pending.contains(key),errors.get(key));}
 // Condition metadata is an indexed imported-listing read, independent of a queued demand/inventory rebuild.
 Snapshot withListingConditions(UUID tenant,UUID connection,Snapshot snapshot){return tx.execute(s->{scope(tenant);var conditions=new HashMap<String,String>();var asins=new HashMap<String,String>();jdbc.query("SELECT seller_sku,condition_type,asin FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=?",(org.springframework.jdbc.core.RowCallbackHandler)r->{conditions.put(r.getString(1),com.nextaicommerce.platform.marketplace.SkuCondition.key(r.getString(2)));asins.put(r.getString(1),r.getString(3));},tenant,connection);var view=conditions(snapshot,conditions);return new Snapshot(view.generatedAt(),view.rows().stream().map(r->new Row(r.sku(),r.title(),r.image(),r.brand(),r.policy(),r.demand(),r.amazon(),r.components(),r.recommendation(),r.warehouse(),r.suggested(),r.blockers(),r.seasons(),r.condition(),asins.get(r.sku()))).toList(),view.supply(),view.events(),view.formatVersion(),view.context());});}
 static Snapshot conditions(Snapshot snapshot,Map<String,String> conditions){return new Snapshot(snapshot.generatedAt(),snapshot.rows().stream().map(r->new Row(r.sku(),r.title(),r.image(),r.brand(),r.policy(),r.demand(),r.amazon(),r.components(),displayClassification(r.recommendation(),r.policy()),r.warehouse(),r.suggested(),r.blockers(),r.seasons(),conditions.getOrDefault(r.sku(),"UNKNOWN"),r.asin())).toList(),snapshot.supply(),snapshot.events(),snapshot.formatVersion(),snapshot.context());}
 public void refresh(UUID tenant,UUID connection){schedule(tenant,connection,true);}
 private void schedule(UUID tenant,UUID connection,boolean manual){String key=tenant+":"+connection;synchronized(pending){if(!manual&&errors.containsKey(key)||!pending.add(key))return;errors.remove(key);}worker.execute(()->{try{var reads=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));reads.setReadOnly(true);reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);var snapshot=reads.execute(s->{scope(tenant);return build(tenant,connection);});tx.executeWithoutResult(s->{scope(tenant);jdbc.update("INSERT INTO fba_replenishment_forecasts(tenant_id,marketplace_connection_id,payload,generated_at) VALUES (?,?,?::jsonb,?) ON CONFLICT(tenant_id,marketplace_connection_id) DO UPDATE SET payload=excluded.payload,generated_at=excluded.generated_at",tenant,connection,json.writeValueAsString(snapshot),java.sql.Timestamp.from(snapshot.generatedAt()));});}catch(Exception e){errors.put(key,"Forecast refresh failed. The last prepared plan is retained.");org.slf4j.LoggerFactory.getLogger(getClass()).warn("FBA forecast failed account/store {} / {}: {}",tenant,connection,e.getClass().getSimpleName());}finally{pending.remove(key);}});}
 @jakarta.annotation.PreDestroy public void close(){worker.shutdownNow();}
 @org.springframework.transaction.annotation.Transactional(readOnly=true)
 public String reportingTimeZone(UUID tenant,UUID connection){return tx.execute(s->{scope(tenant);return jdbc.queryForObject("SELECT reporting_timezone FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,connection);});}
 @SuppressWarnings("unchecked") Snapshot build(UUID tenant,UUID connection){
  Instant generationStarted=Instant.now();ZoneId marketZone=ZoneId.of(jdbc.queryForObject("SELECT reporting_timezone FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,connection));LocalDate today=LocalDate.ofInstant(generationStarted,marketZone);
  var listings=jdbc.queryForList("SELECT seller_sku sku,item_name title,image_url image,condition_type FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=? AND (upper(coalesce(fulfillment_channel,'')) LIKE '%AMAZON%' OR upper(fulfillment_channel) IN ('AFN','FBA')) ORDER BY seller_sku",tenant,connection);
  var parents=relationships.load(tenant,connection).parents();listings.removeIf(l->parents.contains(l.get("sku")));var ambiguous=ambiguousStock(tenant,connection);
  var mapped=jdbc.queryForList("""
   SELECT m.marketplace_sku sku,c.account_catalog_item_id item,c.quantity,coalesce(nullif(a.display_name,''),g.canonical_name,a.account_sku) name,g.brand,coalesce(nullif(trim(offer.vendor_item_code),''),nullif(trim(a.account_sku),'')) item_code,coalesce(a.replenishment_units_per_case,offer.units_per_case,g.units_per_case) master_case,(a.status='ACTIVE' AND a.deleted_at IS NULL) eligible
   FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
   JOIN global_catalog_products g ON g.id=a.global_product_id
   LEFT JOIN LATERAL(SELECT offer.vendor_item_code,packaging.units_per_case FROM vendor_catalog_offers offer
    JOIN vendors vendor ON vendor.tenant_id=offer.tenant_id AND vendor.id=offer.vendor_id AND vendor.status='ACTIVE'
    LEFT JOIN global_product_packaging_versions packaging ON packaging.id=offer.packaging_version_id
    WHERE offer.tenant_id=a.tenant_id AND offer.account_catalog_item_id=a.id AND offer.effective_from<=current_date
     AND (offer.effective_to IS NULL OR offer.effective_to>=current_date)
    ORDER BY offer.is_default DESC,offer.updated_at DESC,offer.id LIMIT 1) offer ON true
   WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.status='ACTIVE' ORDER BY m.marketplace_sku,c.sort_order,c.created_at
   """,tenant,connection);
  List<UUID> ids=jdbc.queryForList("SELECT id FROM account_catalog_items WHERE tenant_id=? AND status='ACTIVE' AND deleted_at IS NULL ORDER BY id",UUID.class,tenant);var warehouse=new HashMap<UUID,Long>();var shelf=inventory.shelfLifePolicy(tenant);
  record Location(UUID warehouse,String name,String postal){}var locations=new HashMap<UUID,Location>();
  jdbc.query("SELECT l.id,w.id warehouse,w.name,w.postal_code FROM warehouse_locations l JOIN warehouses w ON w.tenant_id=l.tenant_id AND w.id=l.warehouse_id WHERE l.tenant_id=? AND l.status='ACTIVE' AND w.status='ACTIVE'",(org.springframework.jdbc.core.RowCallbackHandler)r->locations.put(r.getObject(1,UUID.class),new Location(r.getObject(2,UUID.class),r.getString(3),r.getString(4))),tenant);
  var stockByWarehouse=new LinkedHashMap<UUID,Map<UUID,Long>>();
  for(int at=0;at<ids.size();at+=200)for(var p:inventory.inventory(tenant,ids.subList(at,Math.min(ids.size(),at+200))))if(locations.containsKey(p.locationId())&&p.daysRemaining(today)>=0&&!p.marketplaceStoppedByPlan()&&(!shelf.autoZeroMarketplaceSellable()||p.daysRemaining(today)>shelf.minimumSellableDays())){
   long available=p.quantity().subtract(p.reservedQuantity()).max(BigDecimal.ZERO).longValue();warehouse.merge(p.itemId(),available,Long::sum);stockByWarehouse.computeIfAbsent(locations.get(p.locationId()).warehouse(),key->new HashMap<>()).merge(p.itemId(),available,Long::sum);
  }
  var stocks=new HashMap<String,FbaReadRepository.Stock>();for(int at=0;at<listings.size();at+=200)stocks.putAll(fba.stocks(tenant,connection,listings.subList(at,Math.min(listings.size(),at+200)).stream().map(m->(String)m.get("sku")).toList()));
  var fulfilled=new FbaFulfilledDemand(jdbc).read(tenant,connection,generationStarted);var fulfilledByStore=new HashMap<UUID,FbaFulfilledDemand.Evidence>();fulfilledByStore.put(connection,fulfilled);var sales=fulfilled.sales();var history=stockHistory(tenant,connection,generationStarted.minus(Duration.ofDays(450)),generationStarted);adjustVerifiedOutages(sales,history,generationStarted);var historicalSales=fulfilled.historical(marketZone);var policies=new HashMap<String,Policy>();jdbc.query("SELECT scope_key,configuration::text FROM fba_replenishment_policies WHERE tenant_id=?",(org.springframework.jdbc.core.RowCallbackHandler)r->policies.put(r.getString(1),json.readValue(r.getString(2),Policy.class)),tenant);
  List<ScopedEvent> events=jdbc.query("SELECT id,name,start_date,end_date,configuration::text FROM fba_replenishment_events WHERE tenant_id=? AND end_date>=? AND start_date<=? ORDER BY start_date,id",(r,n)->{var c=json.readTree(r.getString(5));var event=new Event(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,LocalDate.class),r.getObject(4,LocalDate.class),c.path("uplift").isNumber()?c.path("uplift").asDouble():null,c.path("reliable").asBoolean(false),c.path("source").asText("User event"));return new ScopedEvent(event,c.path("brand").asText(""),c.path("sku").asText(""),c.path("connection").isTextual()?UUID.fromString(c.path("connection").asText()):null,c.path("disabled").asBoolean(false),c.path("previousStart").isTextual()?LocalDate.parse(c.path("previousStart").asText()):null,c.path("previousEnd").isTextual()?LocalDate.parse(c.path("previousEnd").asText()):null);},tenant,today,today.plusDays(730));
  events=withKnownEvents(tenant,events,today,today.plusDays(730));
  var incoming=inboundSnapshots(tenant,connection,listings.stream().map(l->(String)l.get("sku")).toList());var incompleteDemand=fulfilled.incomplete();var rows=new ArrayList<Row>();var allComponents=new LinkedHashMap<String,Map<UUID,Long>>();var requested=new HashMap<String,Long>();
  for(var listing:listings){String sku=(String)listing.get("sku");var components=new ArrayList<Component>();var blockers=new ArrayList<String>();for(var m:mapped)if(sku.equals(m.get("sku"))){var qty=new BigDecimal(m.get("quantity").toString());if(!Boolean.TRUE.equals(m.get("eligible"))){blockers.add("A mapped product is inactive or removed. Correct the complete mapping.");continue;}if(qty.signum()<=0||qty.stripTrailingZeros().scale()>0){blockers.add("Mapping must resolve to complete product units.");continue;}UUID item=(UUID)m.get("item");components.add(new Component(item,(String)m.get("name"),Objects.toString(m.get("brand"),""),qty.longValueExact(),warehouse.getOrDefault(item,0L),(String)m.get("item_code"),(BigDecimal)m.get("master_case")));}
   if(components.isEmpty())blockers.add("Complete this SKU's product mapping before allocation.");String brand=components.stream().map(Component::brand).distinct().count()==1?components.getFirst().brand():"";
   var policy=selectPolicy(policies,connection,sku,brand);
   var demand=sales.getOrDefault(sku,new Demand(0,0,0,null,null));var stock=stocks.get(sku);if(ambiguous.contains(sku))blockers.add("Stock evidence is incomplete or shares a FNSKU; review before recommendation.");var originComposition=new LinkedHashMap<String,Map<UUID,Long>>();var requiredParts=new LinkedHashMap<UUID,Long>();components.forEach(c->requiredParts.merge(c.item(),c.each(),Long::sum));originComposition.put(sku,requiredParts);long feasible=feasibleByWarehouse(stockByWarehouse,originComposition,Map.of(sku,Long.MAX_VALUE)).getOrDefault(sku,0L);
   var seasonal=seasonalComparisons(sku,events.stream().filter(e->e.applies(brand,sku,connection)).toList(),historicalSales.getOrDefault(sku,List.of()),history.getOrDefault(sku,List.of()),today,marketZone);
   var committed=remainingInbound(stock,incoming.get(sku),generationStarted);if(committed==null)blockers.add("Inbound quantities or their source need review before recommending more stock.");var result=estimate(today,demand,policy,stock==null||stock.available()==null?null:stock.available().doubleValue(),stock==null||stock.stale()||ambiguous.contains(sku)||committed==null,feasible,committed==null?List.of():committed,seasonalEvents(events.stream().filter(e->e.applies(brand,sku,connection)).toList(),seasonal));
   if(incompleteDemand.contains(sku)){var warnings=new ArrayList<>(result.warnings());warnings.add("Some fulfilled sales evidence is missing or conflicting. Pending and cancelled orders are excluded.");if(policy.dailyOverride()==null){blockers.add("Review fulfilled sales evidence before relying on demand.");result=new Result(null,null,0,null,null,result.arrival(),null,null,"Demand needs review",List.copyOf(warnings),0,0);}else result=new Result(result.daily(),result.recommended(),result.feasible(),result.shortfall(),result.cover(),result.arrival(),result.stockout(),result.prepareBy(),result.status(),List.copyOf(warnings),result.demandBeforeArrival(),result.demandAfterArrival());}
   var composition=new LinkedHashMap<UUID,Long>();components.forEach(c->composition.merge(c.item(),c.each(),Long::sum));allComponents.put(sku,composition);requested.put(sku,blockers.isEmpty()&&result.recommended()!=null?result.recommended():0);
   rows.add(new Row(sku,Objects.toString(listing.get("title"),sku),(String)listing.get("image"),brand,policy,demand,stock,List.copyOf(components),result,feasible,0,List.copyOf(blockers),seasonal,com.nextaicommerce.platform.marketplace.SkuCondition.key((String)listing.get("condition_type"))));
  }
  rows.sort((left,right)->priority(left.recommendation(),left.sku(),right.recommendation(),right.sku()));var ordered=new LinkedHashMap<String,Map<UUID,Long>>();rows.forEach(r->ordered.put(r.sku(),allComponents.get(r.sku())));var feasible=feasibleByWarehouse(stockByWarehouse,ordered,requested);
  rows=rows.stream().map(r->new Row(r.sku(),r.title(),r.image(),r.brand(),r.policy(),r.demand(),r.amazon(),r.components(),r.recommendation(),r.warehouse(),feasible.getOrDefault(r.sku(),0L),r.blockers(),r.seasons(),r.condition())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
  var performance=new HashMap<String,Performance>();var skuNames=listings.stream().map(l->(String)l.get("sku")).toList();
  if(orders!=null&&refunds!=null){var weeks=orders.fourWeekSales(tenant,connection,skuNames);var totals=orders.soldTotals(tenant,connection,skuNames);var returned=refunds.fourWeeks(tenant,connection,skuNames);for(String sku:skuNames)performance.put(sku,new Performance(weeks.getOrDefault(sku,"0 | 0 | 0 | 0"),returned.getOrDefault(sku,"[]"),totals.getOrDefault(sku,new com.nextaicommerce.platform.orders.OrderRepository.SoldTotals(0,0))));}
  var originInfo=locations.values().stream().collect(java.util.stream.Collectors.toMap(Location::warehouse,l->l,(a,b)->a));var warehouseView=stockByWarehouse.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->new WarehouseStock(e.getKey(),originInfo.get(e.getKey()).name(),originInfo.get(e.getKey()).postal(),Map.copyOf(e.getValue()))).toList();
  return new Snapshot(generationStarted,List.copyOf(rows),accountSupply(tenant,warehouse,policies,events,today,connection,history,historicalSales,generationStarted,fulfilledByStore),events,6,new Context(warehouseView,performance));
 }
 Map<String,Demand> sales(UUID tenant,UUID connection){return new FbaFulfilledDemand(jdbc).read(tenant,connection,Instant.now()).sales();}
 public record Observation(Instant at,int units){}
 /** Subtract only complete, continuously observed zero-stock days. Never extrapolate across gaps. */
 static int verifiedOutageDays(List<Observation> source,Instant end,int days){
  var observations=source.stream().filter(o->!o.at().isAfter(end)).sorted(Comparator.comparing(Observation::at)).toList();var spans=new ArrayList<Instant[]>();
  for(int n=0;n<observations.size();n++){var o=observations.get(n);if(o.units()!=0)continue;Instant until=o.at().plus(Duration.ofDays(1));if(n+1<observations.size()&&observations.get(n+1).at().isBefore(until))until=observations.get(n+1).at();if(until.isAfter(end))until=end;
   if(!spans.isEmpty()&&!o.at().isAfter(spans.getLast()[1])){if(until.isAfter(spans.getLast()[1]))spans.getLast()[1]=until;}else spans.add(new Instant[]{o.at(),until});}
  int count=0;for(int n=1;n<=days;n++){Instant from=end.minus(Duration.ofDays(n)),to=end.minus(Duration.ofDays(n-1));if(spans.stream().anyMatch(s->!s[0].isAfter(from)&&!s[1].isBefore(to)))count++;}return count;
 }
 Map<String,List<Observation>> stockHistory(UUID tenant,UUID connection,Instant from,Instant until){
  var history=new HashMap<String,List<Observation>>();jdbc.query("""
   SELECT seller_sku,snapshot_at,fulfillable_quantity FROM amazon_inventory_snapshots
   WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=(SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?)
    AND snapshot_at BETWEEN ? AND ? AND CASE WHEN coalesce(nullif(raw_payload->>'afn-fulfillable-quantity',''),raw_payload->>'fulfillable-quantity','') ~ '^[0-9]{1,9}$'
    THEN coalesce(nullif(raw_payload->>'afn-fulfillable-quantity',''),raw_payload->>'fulfillable-quantity')::integer=fulfillable_quantity ELSE false END
   ORDER BY seller_sku,snapshot_at
   """,(org.springframework.jdbc.core.RowCallbackHandler)r->history.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(new Observation(r.getTimestamp(2).toInstant(),r.getInt(3))),tenant,connection,tenant,connection,java.sql.Timestamp.from(from),java.sql.Timestamp.from(until));return history;
 }
 static void adjustVerifiedOutages(Map<String,Demand> sales,Map<String,List<Observation>> history,Instant now){
  sales.replaceAll((sku,d)->{var observations=history.getOrDefault(sku,List.of());int out30=verifiedOutageDays(observations,now,30),out90=verifiedOutageDays(observations,now,90);return new Demand(d.units30(),d.units90(),d.historyDays(),out30>0&&out30<30?30-out30:null,out90>0&&out90<90?90-out90:null);});
 }
 public record HistoricalDay(LocalDate day,double units,boolean promotionKnown,boolean promotion){}
 Map<String,List<HistoricalDay>> historicalSales(UUID tenant,UUID connection,LocalDate from,LocalDate until){ZoneId zone=ZoneId.of(reportingTimeZone(tenant,connection));Instant asOf=until.plusDays(1).atStartOfDay(zone).toInstant();if(asOf.isAfter(Instant.now()))asOf=Instant.now();return new FbaFulfilledDemand(jdbc).read(tenant,connection,asOf).historical(zone);}
 static List<ScopedEvent> knownUsEvents(UUID store,LocalDate from,LocalDate until){
  var out=new ArrayList<ScopedEvent>();
  // Verified OPM schedules, checked 2026-11-17. These are actual holiday dates,
  // not weekend payroll-observance dates. Unpublished promotions are never guessed.
  for(int year=2026;year<=2028;year++){
   var dates=Map.of("Thanksgiving",LocalDate.of(year,11,1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(4,DayOfWeek.THURSDAY)),"Christmas",LocalDate.of(year,12,25),"New Year's Day",LocalDate.of(year,1,1));
   for(var e:dates.entrySet()){if(e.getValue().isBefore(from)||e.getValue().isAfter(until))continue;LocalDate previous=e.getKey().equals("Thanksgiving")?LocalDate.of(year-1,11,1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(4,DayOfWeek.THURSDAY)):e.getValue().minusYears(1);UUID id=UUID.nameUUIDFromBytes(("FBA:US:"+store+":"+e.getKey()+":"+year).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.add(new ScopedEvent(new Event(id,e.getKey(),e.getValue(),e.getValue(),null,false,"OPM holiday calendar · https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/"),"","",store,false,previous,previous));}
  }
  out.sort(Comparator.comparing(e->e.event().start()));return List.copyOf(out);
 }
 List<ScopedEvent> withKnownEvents(UUID tenant,List<ScopedEvent> configured,LocalDate from,LocalDate until){
  var out=new ArrayList<>(configured);var ids=new HashSet<>(jdbc.queryForList("SELECT id FROM fba_replenishment_events WHERE tenant_id=?",UUID.class,tenant));
  for(UUID store:jdbc.queryForList("SELECT c.id FROM marketplace_connections c JOIN marketplace_definitions d ON d.channel=c.channel AND d.marketplace_identifier=c.marketplace_identifier WHERE c.tenant_id=? AND c.channel='AMAZON' AND d.country_code='US'",UUID.class,tenant))for(var event:knownUsEvents(store,from,until))if(!ids.contains(event.event().id()))out.add(event);
  out.sort(Comparator.comparing(e->e.event().start()));return List.copyOf(out);
 }
 static double availabilityCoverage(List<Observation> source,Instant start,Instant end){
  var rows=source.stream().sorted(Comparator.comparing(Observation::at)).toList();double known=0;
  for(int n=0;n<rows.size();n++){var o=rows.get(n);Instant until=o.at().plus(Duration.ofDays(1));if(n+1<rows.size()&&rows.get(n+1).at().isBefore(until))until=rows.get(n+1).at();Instant from=o.at().isBefore(start)?start:o.at();if(until.isAfter(end))until=end;if(until.isAfter(from)&&o.units()>0)known+=Duration.between(from,until).toSeconds();}
  return end.isAfter(start)?Math.min(1,known/Duration.between(start,end).toSeconds()):0;
 }
 static List<SeasonalComparison> seasonalComparisons(String sku,List<ScopedEvent> events,List<HistoricalDay> sales,List<Observation> history,LocalDate today,ZoneId zone){
  var out=new ArrayList<SeasonalComparison>();for(var scoped:events){var e=scoped.event();LocalDate previous=scoped.previousStart(),previousEnd=scoped.previousEnd();
   if(previous==null||previousEnd==null){out.add(new SeasonalComparison(e.id(),e.name(),e.start(),e.end(),null,null,0,0,0,null,false,"Corresponding prior event dates need review."));continue;}
   LocalDate baselineStart=previous.minusDays(28);var eventSales=sales.stream().filter(d->!d.day().isBefore(previous)&&!d.day().isAfter(previousEnd)).toList();var baseline=sales.stream().filter(d->!d.day().isBefore(baselineStart)&&d.day().isBefore(previous)).toList();double units=eventSales.stream().mapToDouble(HistoricalDay::units).sum(),baselineUnits=baseline.stream().mapToDouble(HistoricalDay::units).sum();double coverage=Math.min(availabilityCoverage(history,baselineStart.atStartOfDay(zone).toInstant(),previous.atStartOfDay(zone).toInstant()),availabilityCoverage(history,previous.atStartOfDay(zone).toInstant(),previousEnd.plusDays(1).atStartOfDay(zone).toInstant()));long days=java.time.temporal.ChronoUnit.DAYS.between(previous,previousEnd)+1;
   Double uplift=baselineUnits>0?Math.max(0,(units/days)/(baselineUnits/28)-1):null;boolean promotion=java.util.stream.Stream.concat(eventSales.stream(),baseline.stream()).anyMatch(d->!d.promotionKnown()||d.promotion());
   boolean reliable=previousEnd.isBefore(today)&&baselineUnits>=56&&units>=5&&baseline.size()>=14&&coverage>=.95&&!promotion&&uplift!=null&&uplift<=10;
   String message=promotion?"Prior promotion evidence or missing discount evidence · recurrence needs review.":reliable?"Prior event compared with 28-day baseline; at least 95% observed sellable coverage.":"Insufficient prior-event sales or availability evidence; no automatic uplift.";
   out.add(new SeasonalComparison(e.id(),e.name(),e.start(),e.end(),previous,previousEnd,units,baselineUnits,coverage,uplift,reliable,message));
  }return List.copyOf(out);
 }
 static List<Event> seasonalEvents(List<ScopedEvent> events,List<SeasonalComparison> comparisons){
  var byId=new HashMap<UUID,SeasonalComparison>();comparisons.forEach(c->byId.put(c.id(),c));return events.stream().map(scoped->{var e=scoped.event();var c=byId.get(e.id());if(!e.reliable()&&c!=null&&c.applied())return new Event(e.id(),e.name(),e.start(),e.end(),c.uplift(),true,e.source()+" · verified prior-event comparison");return e;}).toList();
 }
 /** Repeated FNSKU aliases are uncertain physical identity, not additive inventory. */
 record InboundSnapshot(String sku,Integer available,Integer shipped,Integer receiving,String rawAvailable,String rawShipped,String rawReceiving,Instant at,boolean fullSource,boolean identityKnown,long copies,Instant sourceAt){}
 Map<String,InboundSnapshot> inboundSnapshots(UUID tenant,UUID connection,List<String> skus){var result=new HashMap<String,InboundSnapshot>();var unique=new ArrayList<>(new LinkedHashSet<>(skus));for(int start=0;start<unique.size();start+=200)result.putAll(inboundSnapshotChunk(tenant,connection,unique.subList(start,Math.min(start+200,unique.size()))));return result;}
 Map<String,InboundSnapshot> inboundSnapshotChunk(UUID tenant,UUID connection,List<String> skus){if(skus.isEmpty())return Map.of();if(skus.size()>200)throw new IllegalArgumentException("Inbound snapshot batch exceeds 200 SKUs.");var result=new HashMap<String,InboundSnapshot>();jdbc.query("""
  WITH candidates AS(SELECT s.*,count(*) OVER(PARTITION BY s.seller_sku,s.snapshot_at) copies FROM amazon_inventory_snapshots s
    JOIN marketplace_connections c ON c.tenant_id=s.tenant_id AND c.id=s.marketplace_connection_id AND c.marketplace_identifier=s.marketplace_id
    WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.seller_sku=ANY(?::text[])),
  latest AS(SELECT DISTINCT ON(seller_sku) * FROM candidates ORDER BY seller_sku,snapshot_at DESC,id DESC)
  SELECT s.*,s.raw_payload->>'afn-fulfillable-quantity' raw_available,s.raw_payload->>'afn-inbound-shipped-quantity' raw_shipped,s.raw_payload->>'afn-inbound-receiving-quantity' raw_receiving,
    d.normalized_at IS NOT NULL AND d.source_type='INVENTORY_SNAPSHOT' AND (d.source_key='FBA_INVENTORY_API' OR (r.report_type='GET_FBA_MYI_ALL_INVENTORY_DATA' AND r.status='DONE')) full_source,
    CASE WHEN d.source_key='FBA_INVENTORY_API' THEN s.snapshot_at ELSE r.processing_completed_at END source_at,
    nullif(trim(s.fnsku),'') IS NOT NULL AND (s.asin IS NULL OR l.asin IS NULL OR s.asin=l.asin) identity_known
  FROM latest s JOIN amazon_listings l ON l.tenant_id=s.tenant_id AND l.marketplace_connection_id=s.marketplace_connection_id AND l.marketplace_id=s.marketplace_id AND l.seller_sku=s.seller_sku
  LEFT JOIN amazon_source_documents d ON d.tenant_id=s.tenant_id AND d.marketplace_connection_id=s.marketplace_connection_id AND d.id=s.source_document_id
  LEFT JOIN amazon_report_requests r ON r.tenant_id=d.tenant_id AND r.marketplace_connection_id=d.marketplace_connection_id AND r.id=d.report_request_id
  """,rs->{var v=new InboundSnapshot(rs.getString("seller_sku"),(Integer)rs.getObject("fulfillable_quantity"),(Integer)rs.getObject("inbound_shipped_quantity"),(Integer)rs.getObject("inbound_receiving_quantity"),rs.getString("raw_available"),rs.getString("raw_shipped"),rs.getString("raw_receiving"),rs.getTimestamp("snapshot_at").toInstant(),rs.getBoolean("full_source"),rs.getBoolean("identity_known"),rs.getLong("copies"),rs.getTimestamp("source_at")==null?null:rs.getTimestamp("source_at").toInstant());result.put(v.sku(),v);},tenant,connection,skus.toArray(String[]::new));return result;}
 static List<FutureSupply> remainingInbound(FbaReadRepository.Stock stock,InboundSnapshot source,Instant asOf){if(stock==null||source==null||!source.fullSource()||!source.identityKnown()||source.sourceAt()==null||source.sourceAt().isAfter(asOf)||source.sourceAt().isBefore(asOf.minusSeconds(86400))||source.copies()!=1||!Objects.equals(stock.sku(),source.sku())||!Objects.equals(stock.observedAt(),source.at())||source.at()==null||source.at().isAfter(asOf)||source.at().isBefore(asOf.minusSeconds(86400))||!rawQuantity(source.rawAvailable(),source.available())||!rawQuantity(source.rawShipped(),source.shipped())||!rawQuantity(source.rawReceiving(),source.receiving())||!Objects.equals(stock.available(),source.available())||!Objects.equals(stock.shipped(),source.shipped())||!Objects.equals(stock.receiving(),source.receiving()))return null;return List.of(new FutureSupply((long)source.shipped()+source.receiving(),null,false,true));}
 private static boolean rawQuantity(String raw,Integer value){return value!=null&&value>=0&&raw!=null&&raw.matches("[0-9]{1,9}")&&Long.parseLong(raw)==value;}
 Set<String> ambiguousStock(UUID tenant,UUID connection){return new HashSet<>(jdbc.queryForList("""
  WITH latest AS (SELECT DISTINCT ON(marketplace_id,seller_sku) marketplace_id,seller_sku,fnsku,condition_type,coalesce(nullif(raw_payload->>'afn-fulfillable-quantity',''),raw_payload->>'fulfillable-quantity','') raw_quantity,fulfillable_quantity
   FROM amazon_inventory_snapshots WHERE tenant_id=? AND marketplace_connection_id=? ORDER BY marketplace_id,seller_sku,snapshot_at DESC),
  aliases AS (SELECT marketplace_id,fnsku,coalesce(condition_type,'') condition_type FROM latest WHERE nullif(trim(fnsku),'') IS NOT NULL GROUP BY marketplace_id,fnsku,coalesce(condition_type,'') HAVING count(*)>1)
  SELECT s.seller_sku FROM latest s JOIN aliases a ON a.marketplace_id=s.marketplace_id AND a.fnsku=s.fnsku AND a.condition_type=coalesce(s.condition_type,'')
  UNION SELECT seller_sku FROM latest WHERE NOT(raw_quantity ~ '^[0-9]{1,9}$') OR CASE WHEN raw_quantity ~ '^[0-9]{1,9}$' THEN raw_quantity::integer<>fulfillable_quantity ELSE false END
  """,String.class,tenant,connection));}
 List<Supply> accountSupply(UUID tenant,Map<UUID,Long> warehouse,Map<String,Policy> policies,List<ScopedEvent> events,LocalDate today,UUID selectedStore,Map<String,List<Observation>> selectedHistory,Map<String,List<HistoricalDay>> selectedSales,Instant generationStarted,Map<UUID,FbaFulfilledDemand.Evidence> fulfilledByStore){
  // Aggregate each marketplace/fulfillment channel before expanding into product units.
  var demand=jdbc.queryForList("""
   SELECT i.marketplace_connection_id connection,i.seller_sku sku,
    CASE WHEN upper(coalesce(o.fulfillment_channel,'')) IN ('AFN','AMAZON','FBA') THEN 'FBA' ELSE 'FBM' END channel,
    coalesce(sum(CASE WHEN upper(o.order_status) IN ('SHIPPED','PARTIALLYSHIPPED') THEN i.quantity_shipped ELSE i.quantity_ordered END) FILTER(WHERE o.purchase_date>=now()-interval '30 days'),0) units30,
    coalesce(sum(CASE WHEN upper(o.order_status) IN ('SHIPPED','PARTIALLYSHIPPED') THEN i.quantity_shipped ELSE i.quantity_ordered END),0) units90
   FROM amazon_order_items i JOIN amazon_orders o ON o.tenant_id=i.tenant_id AND o.marketplace_connection_id=i.marketplace_connection_id AND o.amazon_order_id=i.amazon_order_id
   WHERE i.tenant_id=? AND o.purchase_date BETWEEN now()-interval '90 days' AND now() AND upper(coalesce(o.order_status,'')) NOT IN ('CANCELED','CANCELLED','PENDING','PENDINGAVAILABILITY')
   GROUP BY i.marketplace_connection_id,i.seller_sku,3
   UNION ALL
   SELECT l.marketplace_connection_id,l.seller_sku,'SHOPIFY',
    coalesce(sum(CASE WHEN upper(o.fulfillment_status) IN ('FULFILLED','PARTIALLY_FULFILLED') THEN greatest(0,l.ordered_quantity-l.unfulfilled_quantity) ELSE l.ordered_quantity END) FILTER(WHERE o.source_created_at>=now()-interval '30 days'),0),
    coalesce(sum(CASE WHEN upper(o.fulfillment_status) IN ('FULFILLED','PARTIALLY_FULFILLED') THEN greatest(0,l.ordered_quantity-l.unfulfilled_quantity) ELSE l.ordered_quantity END),0)
   FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id
   WHERE l.tenant_id=? AND o.cancelled_at IS NULL AND NOT o.is_test AND o.evidence_complete AND l.requires_shipping AND o.source_created_at BETWEEN now()-interval '90 days' AND now()
   GROUP BY l.marketplace_connection_id,l.seller_sku
   """,tenant,tenant);
  var mapped=jdbc.queryForList("""
   SELECT m.marketplace_connection_id connection,m.marketplace_sku sku,c.account_catalog_item_id item,c.quantity,
    coalesce(nullif(a.display_name,''),g.canonical_name,a.account_sku) name,coalesce(g.brand,'') brand,(a.status='ACTIVE' AND a.deleted_at IS NULL) eligible
   FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id JOIN global_catalog_products g ON g.id=a.global_product_id
   WHERE m.tenant_id=? AND m.status='ACTIVE' ORDER BY m.marketplace_connection_id,m.marketplace_sku,c.account_catalog_item_id
   """,tenant);
  var parentByStore=new HashMap<UUID,Set<String>>();var ambiguousByStore=new HashMap<UUID,Set<String>>();
  for(UUID store:jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND channel='AMAZON'",UUID.class,tenant)){parentByStore.put(store,relationships.load(tenant,store).parents());ambiguousByStore.put(store,ambiguousStock(tenant,store));}
  var inventory=new HashMap<String,Map<String,Object>>();for(var row:jdbc.queryForList("""
   SELECT DISTINCT ON(s.marketplace_connection_id,s.seller_sku) s.marketplace_connection_id connection,s.seller_sku sku,s.fulfillable_quantity,s.snapshot_at,coalesce(nullif(s.raw_payload->>'afn-fulfillable-quantity',''),s.raw_payload->>'fulfillable-quantity','') raw_quantity
   FROM amazon_inventory_snapshots s JOIN marketplace_connections c ON c.tenant_id=s.tenant_id AND c.id=s.marketplace_connection_id AND c.marketplace_identifier=s.marketplace_id
   WHERE s.tenant_id=? ORDER BY s.marketplace_connection_id,s.seller_sku,s.snapshot_at DESC
   """,tenant))inventory.put(row.get("connection")+":"+row.get("sku"),row);
  var histories=new HashMap<UUID,Map<String,List<Observation>>>();histories.put(selectedStore,selectedHistory);var pastSales=new HashMap<UUID,Map<String,List<HistoricalDay>>>();pastSales.put(selectedStore,selectedSales);var zones=new HashMap<UUID,ZoneId>();
  var fulfilledSales=new HashMap<UUID,Map<String,Demand>>();var incompleteSales=new HashMap<UUID,Set<String>>();var groupedMappings=new HashMap<String,List<Map<String,Object>>>();mapped.forEach(c->groupedMappings.computeIfAbsent(c.get("connection")+":"+c.get("sku"),k->new ArrayList<>()).add(c));
  var byProduct=new LinkedHashMap<UUID,List<ChannelSupply>>();var names=new HashMap<UUID,String>();var warnings=new HashMap<UUID,LinkedHashSet<String>>();
  for(var row:demand){UUID store=(UUID)row.get("connection");String sku=(String)row.get("sku"),channel=(String)row.get("channel");if(parentByStore.getOrDefault(store,Set.of()).contains(sku))continue;
   var components=groupedMappings.getOrDefault(store+":"+sku,List.of());if(components.isEmpty())continue;
   boolean complete=components.stream().allMatch(c->Boolean.TRUE.equals(c.get("eligible"))&&new BigDecimal(c.get("quantity").toString()).signum()>0&&new BigDecimal(c.get("quantity").toString()).stripTrailingZeros().scale()<=0);
   String brand=components.stream().map(c->(String)c.get("brand")).distinct().count()==1?(String)components.getFirst().get("brand"):"";
   double units30=((Number)row.get("units30")).doubleValue(),units90=((Number)row.get("units90")).doubleValue();var evidence=new Demand(units30,units90,0,null,null);
   boolean demandKnown=true;if(channel.equals("FBA")){var fulfilled=fulfilledByStore.computeIfAbsent(store,k->new FbaFulfilledDemand(jdbc).read(tenant,k,generationStarted));evidence=fulfilledSales.computeIfAbsent(store,k->fulfilled.sales()).getOrDefault(sku,new Demand(0,0,0,null,null));demandKnown=!incompleteSales.computeIfAbsent(store,k->fulfilled.incomplete()).contains(sku);}
   if(channel.equals("FBA")){var evidenceBySku=new HashMap<String,Demand>();evidenceBySku.put(sku,evidence);var observations=histories.computeIfAbsent(store,k->stockHistory(tenant,k,generationStarted.minus(Duration.ofDays(450)),generationStarted));adjustVerifiedOutages(evidenceBySku,observations,generationStarted);evidence=evidenceBySku.get(sku);}double daily=evidence.daily();
   var policy=selectPolicy(policies,store,sku,brand);if(policy.dailyOverride()!=null)daily=policy.dailyOverride();
   var scopedEvents=events.stream().filter(e->e.applies(brand,sku,store)).toList();var relevant=scopedEvents.stream().map(ScopedEvent::event).toList();
   if(channel.equals("FBA")){ZoneId zone=zones.computeIfAbsent(store,k->ZoneId.of(jdbc.queryForObject("SELECT reporting_timezone FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,k)));var historical=pastSales.computeIfAbsent(store,k->fulfilledByStore.get(k).historical(zone));var comparisons=seasonalComparisons(sku,scopedEvents,historical.getOrDefault(sku,List.of()),histories.get(store).getOrDefault(sku,List.of()),today,zone);relevant=seasonalEvents(scopedEvents,comparisons);}double required=demand(daily,today,90,relevant);var stock=inventory.get(store+":"+sku);
   boolean known=!channel.equals("FBA")||demandKnown&&stock!=null&&stock.get("snapshot_at") instanceof java.sql.Timestamp at&&!at.toInstant().isBefore(Instant.now().minusSeconds(86400))&&!ambiguousByStore.getOrDefault(store,Set.of()).contains(sku)&&Objects.toString(stock.get("raw_quantity"),"").matches("[0-9]{1,9}");
   for(var c:components){UUID id=(UUID)c.get("item");names.put(id,(String)c.get("name"));var warn=warnings.computeIfAbsent(id,k->new LinkedHashSet<>());if(!complete){warn.add("Incomplete active product mapping · supply needs review.");byProduct.computeIfAbsent(id,k->new ArrayList<>()).add(new ChannelSupply(store,sku,channel,0,0,null,null));continue;}
    double count=new BigDecimal(c.get("quantity").toString()).doubleValue();Double available=known?(channel.equals("FBA")?((Number)stock.get("fulfillable_quantity")).doubleValue()*count:0d):null;
    if(!demandKnown)warn.add("Fulfilled sales evidence is incomplete; supply demand needs review.");else if(!known)warn.add("Amazon stock is missing, stale or shared by FNSKU aliases; coverage needs review.");
    if(relevant.stream().anyMatch(e->!e.reliable()||e.uplift()==null))warn.add("Seasonal impact needs review.");
    byProduct.computeIfAbsent(id,k->new ArrayList<>()).add(new ChannelSupply(store,sku,channel,daily*count,required*count,available,available==null?null:Math.max(0,Math.ceil(required*count-available))));
   }
  }
  var out=new ArrayList<Supply>();for(var e:byProduct.entrySet()){UUID id=e.getKey();out.add(summarize(id,names.get(id),warehouse.getOrDefault(id,0L),e.getValue(),warnings.getOrDefault(id,new LinkedHashSet<>())));}
  out.sort(Comparator.comparing(Supply::name).thenComparing(Supply::item));return List.copyOf(out);
 }
 static Supply summarize(UUID id,String name,long local,List<ChannelSupply> channels,Set<String> warnings){
  double daily=channels.stream().mapToDouble(ChannelSupply::daily).sum();
  double amazon=channels.stream().filter(c->c.channel().equals("FBA")&&c.available()!=null).mapToDouble(ChannelSupply::available).sum();
  boolean known=channels.stream().allMatch(c->c.shortage()!=null);
  Double shortfall=known?Math.max(0,Math.ceil(channels.stream().mapToDouble(ChannelSupply::shortage).sum()-local)):null;
  var warn=new LinkedHashSet<>(warnings);warn.add("Warehouse stock counted once; FBA stock serves only its own SKU/channel.");
  return new Supply(id,name,daily,local,amazon,shortfall,List.copyOf(warn),List.copyOf(channels));
 }

}
