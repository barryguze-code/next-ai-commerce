package com.nextaicommerce.platform.marketplace;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.State;
/** Local verified disclosure only: never groups, aggregates or modifies main result rows. */
@Component
public class MarketplaceSkuFamilies {
 private final JdbcTemplate jdbc;private final MarketplaceSkuRepository listings;
 public MarketplaceSkuFamilies(JdbcTemplate jdbc,MarketplaceSkuRepository listings){this.jdbc=jdbc;this.listings=listings;}
 public Map<String,List<MarketplaceSkuRepository.SkuView>> load(UUID tenant,UUID connection,List<MarketplaceSkuRepository.SkuView> visible,MarketplaceInventoryEligibility.Batch batch){
  var parents=visible.stream().map(MarketplaceSkuRepository.SkuView::sellerSku).filter(k->batch.get(k)!=null&&batch.get(k).parent()).toList();
  if(parents.isEmpty())return Map.of();
  var args=new ArrayList<Object>();args.add(tenant);args.add(connection);args.addAll(parents);
  var edges=jdbc.query("SELECT subject_sku,related_sku,marketplace_id FROM amazon_variation_claims WHERE tenant_id=? AND marketplace_connection_id=? AND direction='PARENT_OF' AND subject_sku IN ("+String.join(",",Collections.nCopies(parents.size(),"?"))+") ORDER BY subject_sku,related_sku LIMIT 10001",(r,n)->List.of(r.getString(1),r.getString(2),r.getString(3)),args.toArray());
  if(edges.size()>10000)throw new IllegalArgumentException("Family disclosure exceeds evidence budget");
  var accepted=edges.stream().filter(e->batch.get(e.get(0))!=null&&batch.get(e.get(0)).marketplace().equals(e.get(2))&&batch.get(e.get(1))!=null&&batch.get(e.get(1)).role()==State.CHILD&&batch.get(e.get(1)).marketplace().equals(e.get(2))).toList();
  var rows=listings.exactListings(tenant,connection,accepted.stream().map(e->e.get(1)).distinct().toList(),batch);
  var bySku=new HashMap<String,MarketplaceSkuRepository.SkuView>();rows.forEach(r->bySku.put(r.sellerSku(),r));
  var result=new LinkedHashMap<String,List<MarketplaceSkuRepository.SkuView>>();
  for(var e:accepted)if(bySku.containsKey(e.get(1)))result.computeIfAbsent(e.get(0),k->new ArrayList<>()).add(bySku.get(e.get(1)));
  result.replaceAll((k,v)->List.copyOf(v));return Collections.unmodifiableMap(result);
 }
}
