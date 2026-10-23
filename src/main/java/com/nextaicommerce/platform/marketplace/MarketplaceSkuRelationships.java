package com.nextaicommerce.platform.marketplace;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;

/** Local presentation context only. Never changes inventory eligibility or acquires evidence. */
@Component
public class MarketplaceSkuRelationships {
    public record Parent(String sku,String asin,Integer verifiedTotal) {
        public Parent(String sku,String asin){this(sku,asin,null);}
    }
    public record Batch(Map<String,Parent> children,Set<String> parents,String json) {
        public Parent exactParent(String query){
            if(query==null||query.isEmpty())return null;
            var matches=children.values().stream().distinct().filter(p->query.equals(p.sku())||query.equals(p.asin())).toList();
            return matches.size()==1?matches.getFirst():null;
        }
        public static Batch empty(){return new Batch(Map.of(),Set.of(),null);}
        String cte(){return json==null?"listing_relationships AS (SELECT null::text sku,null::text marketplace,null::text parent_sku,null::text parent_asin,false is_parent WHERE false), ":
            "listing_relationships AS (SELECT * FROM jsonb_to_recordset(?::jsonb) AS r(sku text,marketplace text,parent_sku text,parent_asin text,is_parent boolean)), ";}
    }
    private final JdbcTemplate jdbc;private final AmazonVariationRelationshipProvider provider;private final ObjectMapper json;
    public MarketplaceSkuRelationships(JdbcTemplate jdbc,AmazonVariationRelationshipProvider provider,ObjectMapper json){this.jdbc=jdbc;this.provider=provider;this.json=json;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Batch load(UUID tenant,UUID connection){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        Instant now=jdbc.queryForObject("SELECT transaction_timestamp()",java.sql.Timestamp.class).toInstant();
        var rows=jdbc.queryForList("""
            SELECT v.seller_sku,v.marketplace_id,v.observed_asin FROM amazon_variation_classifications v
            JOIN marketplace_connections c ON c.tenant_id=v.tenant_id AND c.id=v.marketplace_connection_id
                AND c.marketplace_identifier=v.marketplace_id AND c.channel='AMAZON'
            WHERE v.tenant_id=? AND v.marketplace_connection_id=? ORDER BY v.seller_sku COLLATE "C" LIMIT 10001
            """,tenant,connection);
        if(rows.size()>10000)throw new IllegalArgumentException("Relationship presentation exceeds evidence budget");
        if(rows.isEmpty())return Batch.empty();
        Map<String,Classification> verified=new HashMap<>();Map<String,String> asins=new HashMap<>(),marketplaces=new HashMap<>();
        Map<String,List<String>> markets=new LinkedHashMap<>();
        for(var row:rows){String sku=(String)row.get("seller_sku");asins.put(sku,(String)row.get("observed_asin"));marketplaces.put(sku,(String)row.get("marketplace_id"));markets.computeIfAbsent((String)row.get("marketplace_id"),k->new ArrayList<>()).add(sku);}
        markets.forEach((market,keys)->{for(int i=0;i<keys.size();i+=100)verified.putAll(provider.classifyAt(new Scope(tenant,connection,market),keys.subList(i,Math.min(i+100,keys.size())),now));});
        var edges=jdbc.queryForList("SELECT subject_sku,related_sku,marketplace_id FROM amazon_variation_claims WHERE tenant_id=? AND marketplace_connection_id=? AND direction='CHILD_OF' ORDER BY subject_sku LIMIT 10001",tenant,connection);
        if(edges.size()>10000)throw new IllegalArgumentException("Relationship presentation exceeds claim budget");
        Map<String,Parent> children=new LinkedHashMap<>();Set<String> parents=new LinkedHashSet<>();List<Map<String,Object>> projection=new ArrayList<>();
        for(var row:rows){String sku=(String)row.get("seller_sku");var c=verified.get(sku);if(c!=null&&c.quality()==Quality.VERIFIED&&c.state()==State.PARENT){parents.add(sku);projection.add(Map.of("sku",sku,"marketplace",row.get("marketplace_id"),"is_parent",true));}}
        for(var edge:edges){String child=(String)edge.get("subject_sku"),parent=(String)edge.get("related_sku");var c=verified.get(child);
            if(c!=null&&c.quality()==Quality.VERIFIED&&c.state()==State.CHILD&&parents.contains(parent)&&asins.get(parent)!=null
                &&Objects.equals(edge.get("marketplace_id"),marketplaces.get(child))&&Objects.equals(edge.get("marketplace_id"),marketplaces.get(parent))){
                Parent context=new Parent(parent,asins.get(parent));if(children.putIfAbsent(child,context)!=null)throw new IllegalArgumentException("Ambiguous verified parent context");
                projection.add(Map.of("sku",child,"marketplace",edge.get("marketplace_id"),"is_parent",false,"parent_sku",parent,"parent_asin",context.asin()));
            }
        }
        // VERIFIED parent classification validates the complete reciprocal family at this
        // transaction's instant; count the distinct verified child identities, not page rows.
        Map<String,Integer> totals=new HashMap<>();
        children.values().forEach(parent->totals.merge(parent.sku(),1,Integer::sum));
        children.replaceAll((child,parent)->new Parent(parent.sku(),parent.asin(),totals.get(parent.sku())));
        return projection.isEmpty()?Batch.empty():new Batch(Collections.unmodifiableMap(children),Collections.unmodifiableSet(parents),json.writeValueAsString(projection));
    }
}
