package com.nextaicommerce.platform.fba;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Local warehouse operations never rewrite Amazon observations or order reservations. */
@Repository
public class FbaDepletionRepository {
 private final JdbcTemplate jdbc;
 private final tools.jackson.databind.ObjectMapper json;
 public FbaDepletionRepository(JdbcTemplate jdbc,tools.jackson.databind.ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private UUID actor(String email){return jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)",UUID.class,email);}
 public record Warehouse(UUID id,String name){}
 public record Line(String sku,int amazonShipped,int unitsToDeduct,String title,boolean modified,long correctionRevision,String reason){}
 public record Stock(UUID layer,UUID item,UUID location,String product,String locationName,LocalDate expiration,BigDecimal available,BigDecimal unitCost,String currency,Instant received){}
 public record Allocation(UUID layer,BigDecimal quantity){}
 public record Preview(String shipment,UUID warehouse,String warehouseName,String postal,String revision,List<Line> lines,List<Stock> stock,List<Allocation> allocations,List<String> blockers,boolean depleted,boolean automatic,boolean itemsVerified){public boolean ready(){return !depleted&&blockers.isEmpty();}}
 public record ProductPresentation(UUID item,String name,BigDecimal eachPerSku,String itemCode,BigDecimal masterCase){}
 public record LinePresentation(String sku,String imageUrl,List<ProductPresentation> products){}
 /** Read-only display metadata, deliberately excluded from stock revision and immutable approval. */
 @Transactional(readOnly=true) public List<LinePresentation> presentation(UUID tenant,UUID connection,List<String> skus){
  scope(tenant);if(skus.isEmpty())return List.of();
  record Display(String sku,String image,UUID item,String name,BigDecimal quantity,String itemCode,BigDecimal masterCase){}
  var rows=jdbc.query("""
   SELECT names.sku,listing.image_url,c.account_catalog_item_id,coalesce(nullif(a.display_name,''),g.canonical_name,a.account_sku,'Component details unavailable'),c.quantity,a.account_sku,g.units_per_case
   FROM unnest(?::text[]) names(sku)
   LEFT JOIN LATERAL(SELECT image_url FROM amazon_listings l WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.seller_sku=names.sku AND image_url IS NOT NULL ORDER BY l.updated_at DESC LIMIT 1) listing ON true
   LEFT JOIN marketplace_sku_mappings m ON m.tenant_id=? AND m.marketplace_connection_id=? AND m.marketplace_sku=names.sku AND m.status='ACTIVE'
   LEFT JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   LEFT JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
   LEFT JOIN global_catalog_products g ON g.id=a.global_product_id
   ORDER BY names.sku,c.account_catalog_item_id
   """,(r,n)->new Display(r.getString(1),r.getString(2),r.getObject(3,UUID.class),r.getString(4),r.getBigDecimal(5),r.getString(6),r.getBigDecimal(7)),skus.toArray(String[]::new),tenant,connection,tenant,connection);
  var grouped=new LinkedHashMap<String,List<Display>>();rows.forEach(r->grouped.computeIfAbsent(r.sku(),k->new ArrayList<>()).add(r));
  return grouped.entrySet().stream().map(e->new LinePresentation(e.getKey(),e.getValue().getFirst().image(),e.getValue().stream().filter(r->r.item()!=null).map(r->new ProductPresentation(r.item(),r.name(),r.quantity(),r.itemCode(),r.masterCase())).toList())).toList();
 }
 public record Composition(String sku,UUID item,BigDecimal quantity,boolean active){}
 List<Composition> composition(UUID tenant,UUID connection,List<String> skus){
  if(skus.isEmpty())return List.of();
  return jdbc.query("""
   SELECT m.marketplace_sku,c.account_catalog_item_id,c.quantity,a.status='ACTIVE'
   FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
   JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
   WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.status='ACTIVE' AND m.marketplace_sku=ANY(?::text[])
   ORDER BY m.marketplace_sku,c.account_catalog_item_id
   """,(r,n)->new Composition(r.getString(1),r.getObject(2,UUID.class),r.getBigDecimal(3).stripTrailingZeros(),r.getBoolean(4)),tenant,connection,skus.toArray(String[]::new));
 }
 private record Source(UUID id,String status,String postal,String country,String revision,boolean complete){}
 private Source source(UUID tenant,UUID connection,String shipment){
  var rows=jdbc.query("""
   SELECT id,shipment_status,raw_payload#>>'{ShipFromAddress,PostalCode}' postal,
    raw_payload#>>'{ShipFromAddress,CountryCode}' country,md5(raw_payload::text) revision,
    (raw_payload->>'itemEvidenceVersion'='1' AND jsonb_typeof(raw_payload->'itemEvidence')='array') complete FROM amazon_fba_shipments
   WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?
   """,(r,n)->new Source(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBoolean(6)),tenant,connection,shipment);
  if(rows.isEmpty())throw new IllegalArgumentException("Shipment is unavailable in this store.");return rows.getFirst();
 }
 private List<Warehouse> matches(UUID tenant,Source source){
  if(source.postal()==null||source.postal().isBlank()||source.country()==null||source.country().isBlank())return List.of();
  return jdbc.query("""
   SELECT id,name FROM warehouses WHERE tenant_id=? AND status='ACTIVE'
    AND upper(country_code)=upper(?) AND upper(replace(btrim(postal_code),' ',''))=upper(replace(btrim(?),' ','')) ORDER BY id
   """,(r,n)->new Warehouse(r.getObject(1,UUID.class),r.getString(2)),tenant,source.country(),source.postal());
 }
 @Transactional public void removed(UUID tenant,UUID connection,String shipment,String email,boolean removed){scope(tenant);source(tenant,connection,shipment);UUID user=actor(email);
  if(removed)jdbc.update("INSERT INTO fba_shipment_user_preferences(tenant_id,user_id,marketplace_connection_id,shipment_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",tenant,user,connection,shipment);
  else jdbc.update("DELETE FROM fba_shipment_user_preferences WHERE tenant_id=? AND user_id=? AND marketplace_connection_id=? AND shipment_id=?",tenant,user,connection,shipment);
 }
 @Transactional public void correct(UUID tenant,UUID connection,String shipment,String email,String sku,int units,String reason,String expectedRevision){
  scope(tenant);lock(tenant);jdbc.queryForList("SELECT id FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=? FOR UPDATE",tenant,connection,shipment);jdbc.queryForList("SELECT id FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=? FOR SHARE",tenant,connection,shipment);var current=preview(tenant,connection,shipment);if(current.depleted())throw new IllegalArgumentException("Already depleted. A separate audited adjustment is required.");
  if(!current.revision().equals(expectedRevision))throw new IllegalArgumentException("Shipment changed. Review it again.");
  if(units<0||units>10000000||reason==null||reason.isBlank()||reason.length()>1000)throw new IllegalArgumentException("Enter whole units and a reason for change.");
  var line=current.lines().stream().filter(l->l.sku().equals(sku)).findFirst().orElseThrow(()->new IllegalArgumentException("SKU is unavailable."));
  jdbc.update("INSERT INTO fba_shipment_depletion_overrides(tenant_id,marketplace_connection_id,shipment_id,seller_sku,source_quantity,depletion_quantity,source_revision,reason,created_by) VALUES (?,?,?,?,?,?,?,?,?)",tenant,connection,shipment,sku,line.amazonShipped(),units,source(tenant,connection,shipment).revision(),reason.trim(),actor(email));
 }
 @Transactional(readOnly=true) public Preview preview(UUID tenant,UUID connection,String shipment){
  scope(tenant);Source source=source(tenant,connection,shipment);List<String> blockers=new ArrayList<>();var warehouses=matches(tenant,source);
  UUID warehouse=warehouses.size()==1?warehouses.getFirst().id():null;String name=warehouse==null?"No unique ZIP match":warehouses.getFirst().name();
  if(warehouse==null)blockers.add("A unique active warehouse with a matching country and ZIP is required.");
  if(!source.complete())blockers.add("Shipment items are not fully imported.");
  if(!Set.of("SHIPPED","IN_TRANSIT","DELIVERED","CHECKED_IN","RECEIVING","CLOSED").contains(Objects.toString(source.status(),"")))blockers.add("Shipment is not in a shipped state.");
  boolean depleted=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_shipment_depletions WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?)",Boolean.class,tenant,connection,shipment));
  var lines=jdbc.query("""
   SELECT i.seller_sku,i.quantity_shipped,coalesce(o.depletion_quantity,i.quantity_shipped) deduct,
    o.id IS NOT NULL modified,coalesce(o.revision,0) revision,coalesce(o.source_revision,'') override_source,coalesce(o.reason,'') reason,o.source_quantity,
    coalesce((SELECT max(item_name) FROM amazon_listings l WHERE l.tenant_id=i.tenant_id AND l.marketplace_connection_id=i.marketplace_connection_id AND l.seller_sku=i.seller_sku),'') title
   FROM amazon_fba_shipment_items i LEFT JOIN LATERAL(SELECT * FROM fba_shipment_depletion_overrides o
    WHERE o.tenant_id=i.tenant_id AND o.marketplace_connection_id=i.marketplace_connection_id AND o.shipment_id=i.shipment_id AND o.seller_sku=i.seller_sku ORDER BY revision DESC LIMIT 1) o ON true
   WHERE i.tenant_id=? AND i.marketplace_connection_id=? AND i.shipment_id=?
    AND (NOT ? OR EXISTS(SELECT 1 FROM amazon_fba_shipments s CROSS JOIN LATERAL jsonb_array_elements(coalesce(s.raw_payload->'itemEvidence','[]'::jsonb)) e WHERE s.tenant_id=i.tenant_id AND s.marketplace_connection_id=i.marketplace_connection_id AND s.shipment_id=i.shipment_id AND e->>'SellerSKU'=i.seller_sku AND (e->>'QuantityShipped')::integer=i.quantity_shipped AND (e->>'QuantityReceived')::integer=i.quantity_received AND coalesce(e->>'FulfillmentNetworkSKU','')=coalesce(i.fnsku,''))) ORDER BY i.seller_sku
   """,(r,n)->{if(r.getBoolean("modified")&&(!r.getString("override_source").equals(source.revision()) || r.getInt("source_quantity")!=r.getInt("quantity_shipped")))blockers.add("Amazon shipment changed since the quantity correction; review the correction.");return new Line(r.getString(1),r.getInt(2),r.getInt(3),r.getString("title"),r.getBoolean("modified"),r.getLong("revision"),r.getString("reason"));},tenant,connection,shipment,source.complete());
  if(lines.isEmpty())blockers.add("No shipment items available. Refresh shipment items to continue.");
  Integer memberCount=jdbc.queryForObject("SELECT jsonb_array_length(coalesce(raw_payload->'itemEvidence','[]'::jsonb)) FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?",Integer.class,tenant,connection,shipment);
  if(source.complete()&&(memberCount==null || memberCount!=lines.size()))blockers.add("Shipment item evidence changed or is incomplete. Sync and review again.");
  var requirements=new LinkedHashMap<UUID,BigDecimal>();var evidence=new ArrayList<String>();evidence.add(source.revision());evidence.add(lines.toString());
  record Component(String sku,UUID item,BigDecimal quantity,boolean active,String revision){}
  var components=jdbc.query("""
    SELECT m.marketplace_sku,c.account_catalog_item_id,c.quantity,a.status='ACTIVE' active,
      concat(m.id,'|',m.updated_at,'|',c.id,'|',c.updated_at,'|',a.status) revision
    FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
    LEFT JOIN account_catalog_items a ON a.tenant_id=c.tenant_id AND a.id=c.account_catalog_item_id
    WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.status='ACTIVE'
      AND m.marketplace_sku=ANY(?::text[]) ORDER BY m.marketplace_sku,c.account_catalog_item_id
    """,(r,n)->new Component(r.getString(1),r.getObject(2,UUID.class),r.getBigDecimal(3),r.getBoolean(4),r.getString(5)),tenant,connection,lines.stream().map(Line::sku).toArray(String[]::new));
  evidence.add(components.toString());
  for(var line:lines){
   var mapped=components.stream().filter(c->c.sku().equals(line.sku())).toList();
   if(line.unitsToDeduct()>0 && (mapped.isEmpty() || mapped.stream().anyMatch(c->!c.active())))blockers.add("Complete the mapping for SKU "+line.sku()+" before depletion.");
   for(var component:mapped){if(!component.active())continue;var quantity=component.quantity().multiply(BigDecimal.valueOf(line.unitsToDeduct()));if(quantity.stripTrailingZeros().scale()>0)blockers.add("Mapping must resolve to whole eaches: "+line.sku());requirements.merge(component.item(),quantity,BigDecimal::add);}
  }
  List<Stock> stock=warehouse==null||requirements.isEmpty()?List.of():stock(tenant,warehouse,requirements.keySet());
  evidence.add(Objects.toString(warehouse));evidence.add(stock.toString());
  var allocations=new ArrayList<Allocation>();
  for(var needed:requirements.entrySet()){
   var remaining=needed.getValue();for(var lot:stock){if(!lot.item().equals(needed.getKey())||remaining.signum()<=0)continue;var take=lot.available().min(remaining);if(take.signum()>0){allocations.add(new Allocation(lot.layer(),take));remaining=remaining.subtract(take);}}
   if(remaining.signum()>0)blockers.add("Insufficient eligible unreserved stock for item "+needed.getKey()+" (short "+remaining.stripTrailingZeros().toPlainString()+").");
  }
  if(lines.stream().mapToLong(Line::unitsToDeduct).sum()<=0)blockers.add("No units to deduct.");
  boolean automatic=warehouse!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fba_warehouse_depletion_settings WHERE tenant_id=? AND marketplace_connection_id=? AND warehouse_id=? AND automatic_enabled)",Boolean.class,tenant,connection,warehouse));
  return new Preview(shipment,warehouse,name,source.postal(),hash(evidence.toString()),lines,stock,allocations,List.copyOf(new LinkedHashSet<>(blockers)),depleted,automatic,source.complete()&&memberCount!=null&&memberCount==lines.size());
 }
 List<Stock> stock(UUID tenant,UUID warehouse,Set<UUID> items){
  // Existing consumers attribute batch debits FIFO. Never skip a reserved receipt layer.
  return jdbc.query("""
   WITH layers AS (
    SELECT e.*,coalesce(sum(quantity) FILTER(WHERE quantity>0) OVER(PARTITION BY e.account_catalog_item_id,e.location_id,e.expiration_date ORDER BY occurred_at,e.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior_positive,
     sum(-quantity) FILTER(WHERE quantity<0) OVER(PARTITION BY e.account_catalog_item_id,e.location_id,e.expiration_date) debits,
     coalesce(nullif(a.display_name,''),g.canonical_name,a.account_sku,'Component details unavailable') display_name,loc.code,loc.name location_name
    FROM inventory_ledger_entries e JOIN warehouse_locations loc ON loc.tenant_id=e.tenant_id AND loc.id=e.location_id AND loc.status='ACTIVE'
    JOIN account_catalog_items a ON a.tenant_id=e.tenant_id AND a.id=e.account_catalog_item_id AND a.status='ACTIVE'
    LEFT JOIN global_catalog_products g ON g.id=a.global_product_id
    WHERE e.tenant_id=? AND loc.warehouse_id=? AND e.account_catalog_item_id=ANY(?::uuid[])
     AND (e.expiration_date IS NULL OR e.expiration_date>=current_date)
     AND NOT EXISTS(SELECT 1 FROM order_inventory_reservations r WHERE r.tenant_id=e.tenant_id AND r.account_catalog_item_id=e.account_catalog_item_id AND r.location_id=e.location_id AND r.expiration_date IS NOT DISTINCT FROM e.expiration_date AND r.status='ACTIVE' AND r.quantity>0)
   ) SELECT *,greatest(0,quantity-greatest(0,coalesce(debits,0)-prior_positive)) residual FROM layers WHERE quantity>0
   ORDER BY expiration_date ASC NULLS LAST,occurred_at,id
   """,(r,n)->new Stock(r.getObject("id",UUID.class),r.getObject("account_catalog_item_id",UUID.class),r.getObject("location_id",UUID.class),r.getString("display_name"),r.getString("code")+" · "+r.getString("location_name"),r.getObject("expiration_date",LocalDate.class),r.getBigDecimal("residual"),r.getBigDecimal("unit_cost"),r.getString("currency"),r.getTimestamp("occurred_at").toInstant()),tenant,warehouse,items.stream().map(UUID::toString).toArray(String[]::new)).stream().filter(s->s.available().signum()>0).toList();
 }
 private String hash(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 void lockShipment(UUID tenant,UUID connection,String shipment){scope(tenant);lock(tenant);
  jdbc.queryForList("SELECT id FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=? FOR UPDATE",tenant,connection,shipment);
  jdbc.queryForList("SELECT id FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=? FOR SHARE",tenant,connection,shipment);
  jdbc.queryForList("SELECT id FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_sku IN(SELECT seller_sku FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?) FOR UPDATE",tenant,connection,tenant,connection,shipment);
  jdbc.queryForList("SELECT c.id FROM marketplace_sku_mapping_components c JOIN marketplace_sku_mappings m ON m.tenant_id=c.tenant_id AND m.id=c.marketplace_sku_mapping_id WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.marketplace_sku IN (SELECT seller_sku FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?) FOR SHARE OF c",tenant,connection,tenant,connection,shipment);
  jdbc.queryForList("SELECT a.id FROM account_catalog_items a WHERE a.tenant_id=? AND EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c JOIN marketplace_sku_mappings m ON m.tenant_id=c.tenant_id AND m.id=c.marketplace_sku_mapping_id WHERE c.tenant_id=a.tenant_id AND c.account_catalog_item_id=a.id AND m.marketplace_connection_id=? AND m.marketplace_sku IN(SELECT seller_sku FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?)) FOR SHARE",tenant,connection,tenant,connection,shipment);
  jdbc.queryForList("SELECT id FROM warehouses WHERE tenant_id=? FOR SHARE",tenant);
  jdbc.queryForList("SELECT id FROM warehouse_locations WHERE tenant_id=? FOR SHARE",tenant);
 }
 @Transactional public UUID deplete(UUID tenant,UUID connection,String shipment,String email,UUID requestId,String revision,List<Allocation> chosen){return deplete(tenant,connection,shipment,email,requestId,revision,chosen,Map.of());}
 @Transactional public UUID deplete(UUID tenant,UUID connection,String shipment,String email,UUID requestId,String revision,List<Allocation> chosen,Map<String,Object> batch){
  scope(tenant);lock(tenant);
  var prior=jdbc.query("SELECT id,shipment_id,marketplace_connection_id,source_revision,approval::text FROM fba_shipment_depletions WHERE tenant_id=? AND request_id=?",(r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getString(4),r.getString(5)},tenant,requestId);
  if(!prior.isEmpty()){var p=prior.getFirst();if(!shipment.equals(p[1])||!connection.equals(p[2]))throw new IllegalArgumentException("Request belongs to another shipment.");if(!(batch.isEmpty()&&json.readTree((String)p[4]).path("batch").isMissingNode())&&!json.readTree((String)p[4]).path("batch").equals(json.valueToTree(batch)))throw new IllegalArgumentException("Request belongs to a different batch.");if(!Objects.equals(revision,p[3]) || !canonical(chosen).equals(canonicalStored((String)p[4])))throw new IllegalArgumentException("Request was already used with different allocations. Review the saved depletion.");return (UUID)p[0];}
  lockShipment(tenant,connection,shipment);
  Preview current=preview(tenant,connection,shipment);
  if(!current.ready())throw new IllegalArgumentException(current.depleted()?"Already depleted.":String.join(" ",current.blockers()));
  if(!current.revision().equals(revision))throw new IllegalArgumentException("Stock, shipment or mapping changed. Review again.");
  if(chosen==null||chosen.isEmpty()||chosen.size()>2000)throw new IllegalArgumentException("Choose stock allocations.");
  Map<UUID,BigDecimal> desired=new HashMap<>();for(var choice:chosen){if(choice.layer()==null||choice.quantity()==null||choice.quantity().signum()<=0||choice.quantity().stripTrailingZeros().scale()>0||desired.put(choice.layer(),choice.quantity())!=null)throw new IllegalArgumentException("Enter unique positive whole-unit allocations.");}
  Map<UUID,BigDecimal> required=new HashMap<>(),actual=new HashMap<>();Map<UUID,Stock> lots=new HashMap<>();current.stock().forEach(s->lots.put(s.layer(),s));
  for(var a:current.allocations())required.merge(lots.get(a.layer()).item(),a.quantity(),BigDecimal::add);
  Map<String,BigDecimal> totals=new HashMap<>();
  for(var a:chosen){var lot=lots.get(a.layer());if(lot==null||a.quantity().compareTo(lot.available())>0)throw new IllegalArgumentException("Allocation exceeds eligible stock.");actual.merge(lot.item(),a.quantity(),BigDecimal::add);totals.merge(position(lot),a.quantity(),BigDecimal::add);}
  if(required.size()!=actual.size()||required.entrySet().stream().anyMatch(e->e.getValue().compareTo(actual.getOrDefault(e.getKey(),BigDecimal.ZERO))!=0))throw new IllegalArgumentException("Allocations must cover every mapped item exactly.");
  // Location overrides are allowed, but never skip a receipt layer inside the chosen position.
  for(var lot:current.stock()){var key=position(lot);var remaining=totals.getOrDefault(key,BigDecimal.ZERO);var expected=remaining.min(lot.available());if(desired.getOrDefault(lot.layer(),BigDecimal.ZERO).compareTo(expected)!=0)throw new IllegalArgumentException("Use the oldest receipt first within each location and expiration batch.");totals.put(key,remaining.subtract(expected));}
  UUID id=UUID.randomUUID(),actor=actor(email);
  String approval=json.writeValueAsString(Map.of("lines",current.lines(),"allocations",chosen,"policy","FEFO_FIFO_UNRESERVED_POSITION_V1","batch",batch,"composition",composition(tenant,connection,current.lines().stream().map(Line::sku).toList())));
  jdbc.update("INSERT INTO fba_shipment_depletions(id,tenant_id,marketplace_connection_id,shipment_id,warehouse_id,request_id,source_revision,approval,created_by) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",id,tenant,connection,shipment,current.warehouse(),requestId,revision,approval,actor);
  for(var a:chosen){var lot=lots.get(a.layer());UUID entry=UUID.randomUUID();
   jdbc.update("""
    INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,marketplace_connection_id,entry_type,quantity,location_id,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
    VALUES (?,?,?,?,'FBA_DEPLETION',?,?,?,?,?,'FBA_SHIPMENT',?,now(),?,?,?,?)
    """,entry,tenant,lot.item(),connection,a.quantity().negate(),lot.location(),lot.expiration(),lot.unitCost(),lot.currency(),id,"fba-depletion:"+id+":"+lot.layer(),"FBA shipment "+shipment+" · warehouse depletion",actor,lot.unitCost()==null?"PROVISIONAL":"FINAL");
   jdbc.update("INSERT INTO fba_shipment_depletion_allocations(tenant_id,depletion_id,account_catalog_item_id,location_id,expiration_date,quantity,ledger_entry_id,receipt_layer_id,unit_cost,currency) VALUES (?,?,?,?,?,?,?,?,?,?)",tenant,id,lot.item(),lot.location(),lot.expiration(),a.quantity(),entry,lot.layer(),lot.unitCost(),lot.currency());
  }
  return id;
 }
 private Map<UUID,BigDecimal> canonical(List<Allocation> allocations){var result=new TreeMap<UUID,BigDecimal>();if(allocations==null)throw new IllegalArgumentException("Choose allocations.");for(var a:allocations){if(a.layer()==null||a.quantity()==null||result.put(a.layer(),a.quantity().stripTrailingZeros())!=null)throw new IllegalArgumentException("Invalid allocations.");}return result;}
 private Map<UUID,BigDecimal> canonicalStored(String approval){var values=new ArrayList<Allocation>();for(var a:json.readTree(approval).path("allocations"))values.add(new Allocation(UUID.fromString(a.path("layer").asText()),a.path("quantity").decimalValue()));return canonical(values);}
 private String position(Stock s){return s.item()+"|"+s.location()+"|"+s.expiration();}
 private void lock(UUID tenant){jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,tenant.toString());}
}
