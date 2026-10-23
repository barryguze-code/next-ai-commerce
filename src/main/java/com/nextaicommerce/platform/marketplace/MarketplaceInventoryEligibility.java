package com.nextaicommerce.platform.marketplace;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;

/** Marketplace-only read policy. Never mutates the shared FBA projection or source inventory. */
@Component
public class MarketplaceInventoryEligibility {
    public record Decision(String sku,String marketplace,String kind,boolean applicable,String reason,State role) {
        public boolean parent(){return kind.equals("PARENT");}
        public boolean inventoryConflict(){return reason.equals("PARENT_INVENTORY_IDENTITY");}
        public String label(){return parent()?"Not applicable":applicable?"Verified offer":switch(reason){
            case "STALE_EVIDENCE"->"Relationship evidence expired";
            case "INVENTORY_STALE"->"Inventory snapshot outdated";
            case "INVENTORY_MISSING"->"Inventory unavailable";
            case "INVENTORY_IDENTITY","DUPLICATE_FNSKU"->"Inventory identity needs review";
            default->kind.equals("CONFLICT")?"Relationship conflict":"Relationship unverified";};}
    }
    record Source(String sku,String marketplace,String asin,String inventoryAsin,String fnsku,Instant observed,boolean fba,java.math.BigDecimal quantity,Integer available) {}
    public record Batch(Map<String,Decision> decisions,String json,Instant asOf) {
        public Decision get(String sku){return decisions.get(sku);}
        public String guard(String sql){return json==null?sql:sql.replace("coalesce(eligibility.applicable,true)","coalesce(eligibility.applicable,false)").replace("coalesce(eligibility.kind,'LEGACY')","coalesce(eligibility.kind,'UNRESOLVED')");}
        public String cte(){return json==null?" inventory_eligibility AS (SELECT null::text sku,null::text marketplace,null::text kind,null::boolean applicable WHERE false), ":
            " inventory_eligibility AS (SELECT * FROM jsonb_to_recordset(?::jsonb) AS e(sku text,marketplace text,kind text,applicable boolean)), ";}
    }
    private final JdbcTemplate jdbc;private final AmazonVariationRelationshipProvider provider;private final ObjectMapper json;private final boolean enabled;
    public MarketplaceInventoryEligibility(JdbcTemplate jdbc,AmazonVariationRelationshipProvider provider,ObjectMapper json,
            @Value("${app.amazon.variation.inventory-enforcement-enabled:false}") boolean enabled){this.jdbc=jdbc;this.provider=provider;this.json=json;this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    public static Batch disabled(){return new Batch(Map.of(),null,null);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Batch load(UUID tenant,UUID connection){
        if(!enabled)return disabled();
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        Instant now=jdbc.queryForObject("SELECT transaction_timestamp()",java.sql.Timestamp.class).toInstant();
        var source=jdbc.query("""
            SELECT l.seller_sku,l.marketplace_id,l.asin,s.asin inventory_asin,s.fnsku,s.snapshot_at,
                l.fulfillment_channel,coalesce(local_inventory.available,l.quantity) merchant_available,s.fulfillable_quantity
            FROM amazon_listings l JOIN marketplace_connections c ON c.tenant_id=l.tenant_id
                AND c.id=l.marketplace_connection_id AND c.channel='AMAZON'
            LEFT JOIN LATERAL (SELECT asin,fnsku,snapshot_at,fulfillable_quantity FROM amazon_inventory_snapshots s
                WHERE s.tenant_id=l.tenant_id AND s.marketplace_connection_id=l.marketplace_connection_id
                  AND s.marketplace_id=l.marketplace_id AND s.seller_sku=l.seller_sku
                ORDER BY snapshot_at DESC LIMIT 1) s ON true
            LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=l.tenant_id AND mapping.marketplace_connection_id=l.marketplace_connection_id
                AND mapping.marketplace_sku=l.seller_sku AND mapping.status='ACTIVE'
            """+MarketplaceSkuRepository.LOCAL_MAPPING_AVAILABILITY+"""
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? ORDER BY l.marketplace_id,l.seller_sku LIMIT 10001
            """,(r,n)->new Source(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                r.getTimestamp(6)==null?null:r.getTimestamp(6).toInstant(),r.getString(7)!=null&&r.getString(7).toUpperCase(Locale.ROOT).contains("AMAZON"),r.getBigDecimal(8),(Integer)r.getObject(9)),tenant,connection);
        if(source.size()>10000)throw new IllegalArgumentException("Marketplace eligibility exceeds 10,000 listings; no partial certification.");
        if(source.stream().map(Source::sku).distinct().count()!=source.size())throw new IllegalArgumentException("Ambiguous seller SKU across marketplaces; no partial certification");
        Map<String,Classification> roles=new LinkedHashMap<>();
        for(String market:source.stream().map(Source::marketplace).filter(Objects::nonNull).filter(m->!m.isBlank()).distinct().toList()){
            var keys=source.stream().filter(s->market.equals(s.marketplace())).map(Source::sku).toList();
            for(int i=0;i<keys.size();i+=100)roles.putAll(provider.classifyAt(new Scope(tenant,connection,market),keys.subList(i,Math.min(i+100,keys.size())),now));
        }
        var decisions=evaluate(source,roles,now);var rows=new ArrayList<Map<String,Object>>();
        for(var d:decisions.values())rows.add(Map.of("sku",d.sku(),"marketplace",d.marketplace(),"kind",d.kind(),"applicable",d.applicable()));
        return new Batch(Collections.unmodifiableMap(decisions),json.writeValueAsString(rows),now);
    }
    static Map<String,Decision> evaluate(List<Source> rows,Map<String,Classification> roles,Instant now){
        var result=new LinkedHashMap<String,Decision>();var identities=new HashMap<String,List<String>>();
        for(var s:rows){var c=roles.get(s.sku());State role=c==null?State.UNKNOWN:c.state();
            boolean verified=c!=null&&c.quality()==Quality.VERIFIED;String reason=c==null?"MISSING_EVIDENCE":c.reason();
            String kind=role==State.CONFLICT?"CONFLICT":"UNRESOLVED";boolean applicable=false;
            if(verified&&role==State.PARENT){kind="PARENT";reason=s.inventoryAsin()!=null&&!Objects.equals(s.asin(),s.inventoryAsin())?"PARENT_INVENTORY_IDENTITY":"NON_SELLABLE_PARENT";}
            else if(verified&&(role==State.CHILD||role==State.STANDALONE)){
                if(s.fba()&&(s.observed()==null||s.available()==null))reason="INVENTORY_MISSING";
                else if(s.fba()&&(s.asin()==null||!s.asin().equals(s.inventoryAsin())||s.fnsku()==null||s.fnsku().isBlank()))reason="INVENTORY_IDENTITY";
                else if(s.fba()&&s.observed()==null)reason="INVENTORY_MISSING";
                else if(s.fba()&&(s.observed().isAfter(now)||!s.observed().plus(Duration.ofHours(24)).isAfter(now)))reason="INVENTORY_STALE";
                else if(!s.fba()&&s.quantity()==null)reason="INVENTORY_MISSING";
                else {applicable=true;kind="ELIGIBLE";reason="VERIFIED";}
            }
            var d=new Decision(s.sku(),Objects.toString(s.marketplace(),""),kind,applicable,reason,role);result.put(s.sku(),d);
            // Excluded parent identities never contaminate an independently verified child.
            if(applicable&&s.fba())identities.computeIfAbsent(s.marketplace()+"\u0000"+s.fnsku(),k->new ArrayList<>()).add(s.sku());
        }
        for(var s:rows){var d=result.get(s.sku());if(d.parent()&&s.fnsku()!=null&&identities.containsKey(s.marketplace()+"\u0000"+s.fnsku()))result.put(s.sku(),new Decision(d.sku(),d.marketplace(),d.kind(),false,"PARENT_INVENTORY_IDENTITY",d.role()));}
        for(var group:identities.values())if(group.size()>1)for(String sku:group){var d=result.get(sku);result.put(sku,new Decision(sku,d.marketplace(),"CONFLICT",false,"DUPLICATE_FNSKU",d.role()));}
        return result;
    }
}
