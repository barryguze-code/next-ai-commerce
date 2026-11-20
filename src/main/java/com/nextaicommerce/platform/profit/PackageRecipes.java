package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.orders.PackingPackageNames;

/** Exact account-product composition matches. Never infers multiples or changes order/stock evidence. */
@Repository
public class PackageRecipes {
 private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate named;
 private final ObjectMapper json;private final PackingPackageNames names;private final ProfitCostRepository costs;
 public PackageRecipes(JdbcTemplate jdbc,ObjectMapper json,PackingPackageNames names,ProfitCostRepository costs){this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.json=json;this.names=names;this.costs=costs;}
 public record Contents(UUID item,String name,BigDecimal eaches){}
 public record Match(String compositionKey,List<Contents> contents,List<String> packages,long revision,String message){}
 private record Basis(SortedMap<String,String> quantities,List<Contents> contents,boolean complete){
  String encoded(ObjectMapper json){return json.writeValueAsString(quantities);}
  String key(ObjectMapper json){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded(json).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 }
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 /** One mapping query for a page; Shopify uses frozen order composition when present. */
 private Map<String,Basis> bases(UUID tenant,UUID store,String kind,Collection<String> keys,int units){
  if(!Set.of("SKU","ORDER","SHOPIFY_SKU","SHOPIFY_ORDER").contains(kind)||units<1||units>10000||keys.size()>100||keys.stream().anyMatch(k->k==null||k.length()>240))throw new IllegalArgumentException("Choose valid records and 1–10000 SKU units.");
  if(keys.isEmpty())return Map.of();
  String source=switch(kind){
   case "SKU" -> "SELECT l.seller_sku key,l.seller_sku line,l.seller_sku sku,NULL::uuid variant,NULL::uuid frozen_line,:units::numeric units FROM amazon_listings l JOIN marketplace_connections mc ON mc.tenant_id=l.tenant_id AND mc.id=l.marketplace_connection_id AND mc.channel='AMAZON' WHERE l.tenant_id=:tenant AND l.marketplace_connection_id=:store AND l.seller_sku IN (:keys)";
   case "ORDER" -> "SELECT o.amazon_order_id key,i.id::text line,i.seller_sku sku,NULL::uuid variant,NULL::uuid frozen_line,i.quantity_ordered::numeric units FROM amazon_orders o JOIN amazon_order_items i ON i.tenant_id=o.tenant_id AND i.marketplace_connection_id=o.marketplace_connection_id AND i.amazon_order_id=o.amazon_order_id WHERE o.tenant_id=:tenant AND o.marketplace_connection_id=:store AND o.amazon_order_id IN (:keys) AND i.quantity_ordered>0 AND o.items_shipped IS NOT NULL AND o.items_unshipped IS NOT NULL AND (SELECT sum(all_items.quantity_ordered) FROM amazon_order_items all_items WHERE all_items.tenant_id=o.tenant_id AND all_items.marketplace_connection_id=o.marketplace_connection_id AND all_items.amazon_order_id=o.amazon_order_id)=o.items_shipped+o.items_unshipped";
   case "SHOPIFY_SKU" -> "SELECT v.id::text key,v.id::text line,v.seller_sku sku,v.id variant,NULL::uuid frozen_line,:units::numeric units FROM shopify_variants v WHERE v.tenant_id=:tenant AND v.marketplace_connection_id=:store AND v.id::text IN (:keys)";
   default -> "SELECT o.id::text key,l.id::text line,l.seller_sku sku,l.variant_id variant,l.id frozen_line,l.ordered_quantity::numeric units FROM shopify_orders o JOIN shopify_order_lines l ON l.tenant_id=o.tenant_id AND l.marketplace_connection_id=o.marketplace_connection_id AND l.order_id=o.id WHERE o.tenant_id=:tenant AND o.marketplace_connection_id=:store AND o.id::text IN (:keys) AND l.ordered_quantity>0 AND o.evidence_complete AND NOT o.is_test AND o.cancelled_at IS NULL";
  };
  var rows=named.queryForList("WITH requested AS ("+source+"""
   ) SELECT r.key,r.line,r.units,part.item,part.quantity,i.display_name,g.canonical_name,i.status
   FROM requested r LEFT JOIN LATERAL (
    SELECT c.account_catalog_item_id item,c.quantity FROM shopify_order_components c
     WHERE c.tenant_id=:tenant AND c.marketplace_connection_id=:store AND c.order_line_id=r.frozen_line
    UNION ALL
    SELECT c.account_catalog_item_id,c.quantity FROM marketplace_sku_mappings m
    CROSS JOIN LATERAL (
     SELECT c.account_catalog_item_id,c.quantity FROM marketplace_sku_mapping_components c
      WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
     UNION ALL SELECT m.account_catalog_item_id,greatest(m.quantity_per_marketplace_unit,1)
      WHERE m.account_catalog_item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id)
    ) c WHERE m.tenant_id=:tenant AND m.marketplace_connection_id=:store AND m.status='ACTIVE'
     AND ((r.variant IS NULL AND m.shopify_variant_id IS NULL AND m.marketplace_sku=r.sku)
      OR (r.variant IS NOT NULL AND m.shopify_variant_id=r.variant AND EXISTS(SELECT 1 FROM shopify_variants v WHERE v.tenant_id=m.tenant_id AND v.marketplace_connection_id=m.marketplace_connection_id AND v.id=m.shopify_variant_id AND v.mapping_hash=m.shopify_mapping_hash)))
     AND NOT EXISTS(SELECT 1 FROM shopify_order_components c WHERE c.tenant_id=:tenant AND c.marketplace_connection_id=:store AND c.order_line_id=r.frozen_line)
   ) part ON true
   LEFT JOIN account_catalog_items i ON i.tenant_id=:tenant AND i.id=part.item
   LEFT JOIN global_catalog_products g ON g.id=i.global_product_id
   ORDER BY r.key,r.line,part.item
   """,Map.of("tenant",tenant,"store",store,"keys",keys,"units",units));
  var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();for(var row:rows)grouped.computeIfAbsent(row.get("key").toString(),k->new ArrayList<>()).add(row);
  var splitOrigins=new HashSet<String>();
  if(kind.equals("ORDER"))splitOrigins.addAll(named.queryForList("SELECT amazon_order_id FROM order_inventory_reservations WHERE tenant_id=:tenant AND marketplace_connection_id=:store AND amazon_order_id IN (:keys) AND status IN ('ACTIVE','SHIPPED') GROUP BY amazon_order_id HAVING count(DISTINCT location_id)>1",Map.of("tenant",tenant,"store",store,"keys",keys),String.class));
  if(kind.equals("SHOPIFY_ORDER"))splitOrigins.addAll(named.queryForList("SELECT l.order_id::text FROM order_inventory_reservations r JOIN shopify_order_lines l ON l.tenant_id=r.tenant_id AND l.marketplace_connection_id=r.marketplace_connection_id AND l.id=r.shopify_order_line_id WHERE r.tenant_id=:tenant AND r.marketplace_connection_id=:store AND l.order_id::text IN (:keys) AND r.status IN ('ACTIVE','SHIPPED') GROUP BY l.order_id HAVING count(DISTINCT r.location_id)>1",Map.of("tenant",tenant,"store",store,"keys",keys),String.class));
  var result=new LinkedHashMap<String,Basis>();
  for(var entry:grouped.entrySet()){
   var quantities=new TreeMap<String,BigDecimal>();var labels=new HashMap<String,String>();boolean complete=!splitOrigins.contains(entry.getKey());var seen=new HashSet<String>();
   for(var row:entry.getValue()){
    if(row.get("item")==null||row.get("quantity")==null||!"ACTIVE".equals(row.get("status"))){complete=false;continue;}
    String item=row.get("item").toString();if(!seen.add(row.get("line")+":"+item)){complete=false;continue;}
    BigDecimal q=((BigDecimal)row.get("units")).multiply((BigDecimal)row.get("quantity"));if(q.signum()<=0){complete=false;continue;}
    quantities.merge(item,q,BigDecimal::add);labels.put(item,Objects.toString(row.get("display_name"),Objects.toString(row.get("canonical_name"),"Product")));
   }
   var encoded=new TreeMap<String,String>();quantities.forEach((id,q)->encoded.put(id,q.stripTrailingZeros().toPlainString()));
   var contents=quantities.entrySet().stream().map(e->new Contents(UUID.fromString(e.getKey()),labels.get(e.getKey()),e.getValue())).toList();
   result.put(entry.getKey(),new Basis(encoded,contents,complete&&!encoded.isEmpty()));
  }return result;
 }
 private Map<String,Match> matches(UUID tenant,Map<String,Basis> bases){
  var valid=bases.values().stream().filter(Basis::complete).map(b->b.key(json)).distinct().toList();if(valid.isEmpty())return Map.of();
  var rows=named.queryForList("SELECT composition_key,composition::text,(SELECT jsonb_agg(packing_package_display(tenant_id,p.value) ORDER BY p.ordinality)::text FROM jsonb_array_elements_text(packages) WITH ORDINALITY p(value,ordinality)) packages,revision FROM account_package_recipes WHERE tenant_id=:tenant AND composition_key IN (:keys)",Map.of("tenant",tenant,"keys",valid));
  var result=new HashMap<String,Match>();
  for(var entry:bases.entrySet())for(var row:rows)if(entry.getValue().complete()&&entry.getValue().key(json).equals(row.get("composition_key"))){
   var saved=json.readTree(row.get("composition").toString());if(!saved.equals(json.readTree(entry.getValue().encoded(json))))continue;
   var packages=new ArrayList<String>();json.readTree(row.get("packages").toString()).forEach(p->packages.add(p.asText()));
   result.put(entry.getKey(),new Match(row.get("composition_key").toString(),entry.getValue().contents(),packages,((Number)row.get("revision")).longValue(),"Saved packaging match"));
  }return result;
 }
 @Transactional(readOnly=true)
 public Match preview(UUID tenant,UUID store,String kind,String key,int units){
  scope(tenant);var bases=bases(tenant,store,kind,List.of(key),units);var basis=bases.get(key);
  if(basis==null||!basis.complete())throw new IllegalArgumentException("Complete every product mapping and order contents, and review orders split across warehouse origins before saving a packaging match.");
  return matches(tenant,bases).getOrDefault(key,new Match(basis.key(json),basis.contents(),List.of(),0,"No saved match for these exact quantities"));
 }
 @Transactional
 public Match save(UUID tenant,UUID store,String kind,String key,int units,String expectedComposition,long revision,List<String> packages,String actor){
  scope(tenant);var basis=bases(tenant,store,kind,List.of(key),units).get(key);
  if(basis==null||!basis.complete()||!basis.key(json).equals(expectedComposition))throw new IllegalArgumentException("Product mapping changed. Review the contents and retry.");
  if(packages==null||packages.isEmpty()||packages.size()>20||packages.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Choose 1–20 account packages.");
  var choices=names.choices(tenant,costs.rates(tenant),LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")));
  var identities=new ArrayList<String>();for(String selected:packages){if(choices.stream().noneMatch(c->c.name().equals(selected)))throw new IllegalArgumentException("Choose an existing account package. Refresh if it was renamed.");identities.add(jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,selected));}
  if(actor==null||actor.isBlank()||revision<0)throw new IllegalArgumentException("Sign in again and reload the packaging match.");
  int changed=revision==0?jdbc.update("INSERT INTO account_package_recipes(tenant_id,composition_key,composition,packages,updated_by) VALUES (?,?,?::jsonb,?::jsonb,?) ON CONFLICT DO NOTHING",tenant,basis.key(json),basis.encoded(json),json.writeValueAsString(identities),actor):
   jdbc.update("UPDATE account_package_recipes SET packages=?::jsonb,revision=revision+1,updated_by=?,updated_at=clock_timestamp() WHERE tenant_id=? AND composition_key=? AND revision=?",json.writeValueAsString(identities),actor,tenant,basis.key(json),revision);
  if(changed!=1)throw new IllegalArgumentException("This packaging match changed in another window. Reload it before saving; your selections are kept.");
  var result=preview(tenant,store,kind,key,units);
  if(!result.compositionKey().equals(basis.key(json)))throw new IllegalArgumentException("Product mapping changed while saving. Review the contents and retry.");
  return result;
 }
 @Transactional(readOnly=true)
 public List<String> suggestedNames(UUID tenant,UUID store,String kind,String key){
  scope(tenant);var found=matches(tenant,bases(tenant,store,kind,List.of(key),1)).get(key);return found==null?List.of():found.packages();
 }
 /** A user-confirmed packing-slip choice can teach an exact match even before fulfillment. */
 @Transactional
 public void recordChoice(UUID tenant,UUID store,String kind,String key,List<String> packages,String actor){
  scope(tenant);var all=bases(tenant,store,kind,List.of(key),1);var basis=all.get(key);if(basis==null||!basis.complete())return;
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+":"+basis.key(json));
  var existing=matches(tenant,all).get(key);var display=packages.stream().map(p->jdbc.queryForObject("SELECT packing_package_display(?,?)",String.class,tenant,p)).toList();
  save(tenant,store,kind,key,1,basis.key(json),existing==null?0:existing.revision(),display,actor);
 }
 @Transactional(readOnly=true)
 public Map<String,List<ProfitRepository.PackageCost>> packages(UUID tenant,UUID store,String kind,Collection<String> keys,LocalDate date){
  scope(tenant);var found=matches(tenant,bases(tenant,store,kind,keys,1));if(found.isEmpty())return Map.of();
  var choices=names.choices(tenant,costs.rates(tenant),date==null?LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")):date);
  var result=new HashMap<String,List<ProfitRepository.PackageCost>>();
  for(var entry:found.entrySet()){
   var resolved=new ArrayList<ProfitRepository.PackageCost>();boolean complete=true;
   for(String identity:entry.getValue().packages()){
    String display=identity;
    var choice=choices.stream().filter(c->c.name().equals(display)).findFirst();
    if(choice.isEmpty()||choice.get().cost()==null){complete=false;break;}
    resolved.add(new ProfitRepository.PackageCost(display,choice.get().cost()));
   }result.put(entry.getKey(),complete?List.copyOf(resolved):List.of());
  }return result;
 }
}
