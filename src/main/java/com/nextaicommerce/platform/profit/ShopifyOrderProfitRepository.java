package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, bounded local cost projection. No carrier quotes or Amazon fee assumptions. */
@Repository
public class ShopifyOrderProfitRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ProfitCostRepository costs;
    public ShopifyOrderProfitRepository(JdbcTemplate jdbc,ProfitCostRepository costs){this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.costs=costs;}
    record Identity(UUID item,UUID location){}
    record Basis(Map<UUID,BigDecimal> items,Map<Identity,BigDecimal> origins){
        boolean complete(){
            if(items.isEmpty()||origins.isEmpty())return false;
            var totals=new HashMap<UUID,BigDecimal>();origins.forEach((id,q)->totals.merge(id.item(),q,BigDecimal::add));
            return same(items,totals);
        }
        boolean matches(Basis other){return complete()&&other.complete()&&same(items,other.items)&&same(origins,other.origins);}
    }
    private static <K> boolean same(Map<K,BigDecimal> a,Map<K,BigDecimal> b){return a.keySet().equals(b.keySet())&&a.entrySet().stream().allMatch(e->e.getValue().compareTo(b.get(e.getKey()))==0);}
    static ProfitEstimate.Breakdown totals(List<ProfitRepository.Line> lines,BigDecimal shipping){
        BigDecimal revenue=BigDecimal.ZERO,product=BigDecimal.ZERO,other=BigDecimal.ZERO;
        boolean saleKnown=!lines.isEmpty(),costKnown=!lines.isEmpty(),otherKnown=!lines.isEmpty();
        for(var l:lines){
            BigDecimal units=BigDecimal.valueOf(l.quantity());
            if(l.unitPrice()==null)saleKnown=false;else revenue=revenue.add(l.unitPrice().multiply(units));
            if(l.productCost()==null)costKnown=false;else product=product.add(l.productCost().multiply(units));
            if(l.otherCost()==null)otherKnown=false;else other=other.add(l.otherCost().multiply(units));
        }
        revenue=saleKnown?money(revenue):null;product=costKnown?money(product):null;other=otherKnown?money(other):null;
        BigDecimal estimate=revenue!=null&&product!=null&&other!=null&&shipping!=null?
            money(revenue.subtract(product).subtract(shipping).subtract(other)):null;
        // Owner-approved planning estimate excludes payment fees, taxes, refunds and customer shipping.
        // Keep referralFee unknown; Shopify must never inherit Amazon's referral-rate formula.
        return new ProfitEstimate.Breakdown(revenue,product,null,shipping,other,estimate);
    }
    static BigDecimal otherCost(String currency,BigDecimal override,String overrideCurrency){
        if(override!=null)return Objects.equals(currency,overrideCurrency)?override:null;
        return "USD".equals(currency)?ProfitEstimate.DEFAULT_OTHER_COST:null;
    }
    public record OtherCostChange(UUID variant,BigDecimal amount){}
    @Transactional
    public void saveOtherCosts(UUID tenant,UUID connection,List<OtherCostChange> changes,String currency,String actor){
        if(changes==null||changes.isEmpty()||changes.size()>100||changes.stream().anyMatch(Objects::isNull)
            ||changes.stream().map(OtherCostChange::variant).distinct().count()!=changes.size())throw new IllegalArgumentException("Choose up to 100 distinct variants");
        for(var change:changes)saveOtherCost(tenant,connection,change.variant(),change.amount(),currency,actor);
    }
    /** Variant identity, not raw seller SKU, avoids duplicate/blank SKU ambiguity. */
    @Transactional
    public void saveOtherCost(UUID tenant,UUID connection,UUID variant,BigDecimal amount,String currency,String actor){
        if(variant==null||amount==null||amount.signum()<0||amount.scale()>2||amount.compareTo(new BigDecimal("100000"))>0
            ||currency==null||!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("Enter a valid other cost and currency");
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        int changed=jdbc.update("""
            UPDATE shopify_variants v SET profit_other_cost=?,profit_other_currency=?,
                profit_other_updated_by=?,profit_other_updated_at=clock_timestamp()
            FROM marketplace_connections c
            WHERE v.tenant_id=? AND v.marketplace_connection_id=? AND v.id=? AND v.currency=?
              AND c.tenant_id=v.tenant_id AND c.id=v.marketplace_connection_id AND c.channel='SHOPIFY'
            """,amount,currency,actor,tenant,connection,variant,currency);
        if(changed!=1)throw new IllegalArgumentException("Variant or currency not found in this store");
    }
    private static BigDecimal money(BigDecimal n){return n.setScale(2,RoundingMode.HALF_UP);}
    private static String text(Map<String,Object> r,String k){return Objects.toString(r.get(k),"");}
    /** A listing has no shipment/origin evidence. Show known costs without inventing shipping. */
    @Transactional(readOnly=true)
    public Map<String,ProfitRepository.View> skus(UUID tenant,UUID connection,List<String> keys){
        if(keys.isEmpty())return Map.of();
        var ids=keys.stream().map(UUID::fromString).toList();
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        var rows=named.queryForList("""
            SELECT v.id,v.seller_sku,p.title,v.price,v.currency,v.profit_other_cost,v.profit_other_currency,
              CASE WHEN count(part.item)>0 AND count(offer.cost)=count(part.item)
                THEN sum(part.quantity*offer.cost) END product_cost
            FROM shopify_variants v
            JOIN marketplace_connections mc ON mc.tenant_id=v.tenant_id AND mc.id=v.marketplace_connection_id AND mc.channel='SHOPIFY'
            JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
            LEFT JOIN marketplace_sku_mappings m ON m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id
              AND m.shopify_variant_id=v.id AND m.status='ACTIVE' AND m.shopify_mapping_hash=v.mapping_hash
            LEFT JOIN LATERAL (
              SELECT c.account_catalog_item_id item,c.quantity FROM marketplace_sku_mapping_components c
              WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
              UNION ALL SELECT m.account_catalog_item_id,greatest(m.quantity_per_marketplace_unit,1)
              WHERE m.account_catalog_item_id IS NOT NULL AND NOT EXISTS(
                SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id)
            ) part ON true
            LEFT JOIN LATERAL (
              SELECT CASE WHEN offer.currency=v.currency THEN round(offer.list_cost*(1-offer.discount_rate/100),4) END cost
              FROM vendor_catalog_offers offer JOIN account_catalog_items item ON item.tenant_id=offer.tenant_id AND item.id=offer.account_catalog_item_id
              WHERE offer.tenant_id=v.tenant_id AND offer.account_catalog_item_id=part.item AND item.status='ACTIVE'
                AND offer.effective_from<=current_date AND (offer.effective_to IS NULL OR offer.effective_to>=current_date)
              ORDER BY offer.is_default DESC,offer.effective_from DESC,offer.id LIMIT 1
            ) offer ON true
            WHERE v.tenant_id=:tenant AND v.marketplace_connection_id=:connection AND v.id IN (:ids)
            GROUP BY v.id,p.title
            """,Map.of("tenant",tenant,"connection",connection,"ids",ids));
        var result=new LinkedHashMap<String,ProfitRepository.View>();
        for(var r:rows){
            UUID id=(UUID)r.get("id");String currency=text(r,"currency");
            var lines=List.of(new ProfitRepository.Line(text(r,"seller_sku"),text(r,"title"),1,
                (BigDecimal)r.get("price"),(BigDecimal)r.get("product_cost"),
                otherCost(currency,(BigDecimal)r.get("profit_other_cost"),text(r,"profit_other_currency")),null,id));
            result.put(id.toString(),new ProfitRepository.View(id.toString(),"SHOPIFY_SKU",text(r,"title"),currency,
                LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")),lines,List.of(),totals(lines,null),
                "Per sold SKU unit. Current mapped catalogue costs are estimates. Shipping is not established for this listing, so estimated profit remains Pending. Other cost defaults to USD 1 per sold SKU unit. Payment fees, customer shipping, taxes and refunds are excluded.",""));
        }
        return result;
    }
    @Transactional(readOnly=true)
    public Map<String,ProfitRepository.View> orders(UUID tenant,UUID connection,List<String> keys){
        if(keys.isEmpty())return Map.of();
        var ids=keys.stream().map(UUID::fromString).toList();
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        var args=new HashMap<String,Object>();args.put("tenant",tenant);args.put("connection",connection);args.put("ids",ids);
        var rows=named.queryForList("""
            SELECT o.id order_id,o.order_name,o.currency,o.package_name,o.cancelled_at,o.evidence_complete,
              l.id line_id,l.variant_id,l.seller_sku,l.title,l.ordered_quantity,l.line_total,l.currency line_currency,
              part.item,part.quantity,offer.cost,variant.profit_other_cost,variant.profit_other_currency
            FROM shopify_orders o JOIN marketplace_connections mc ON mc.tenant_id=o.tenant_id AND mc.id=o.marketplace_connection_id AND mc.channel='SHOPIFY'
            JOIN shopify_order_lines l ON l.tenant_id=o.tenant_id AND l.marketplace_connection_id=o.marketplace_connection_id AND l.order_id=o.id
            LEFT JOIN shopify_variants variant ON variant.tenant_id=l.tenant_id
              AND variant.marketplace_connection_id=l.marketplace_connection_id AND variant.id=l.variant_id
            LEFT JOIN LATERAL (
              SELECT c.account_catalog_item_id item,c.quantity FROM shopify_order_components c
              WHERE c.tenant_id=l.tenant_id AND c.marketplace_connection_id=l.marketplace_connection_id AND c.order_line_id=l.id
              UNION ALL
              SELECT c.account_catalog_item_id,c.quantity FROM marketplace_sku_mappings m
              JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
              JOIN shopify_variants v ON v.tenant_id=m.tenant_id AND v.marketplace_connection_id=m.marketplace_connection_id AND v.id=m.shopify_variant_id AND v.mapping_hash=m.shopify_mapping_hash
              WHERE m.tenant_id=l.tenant_id AND m.marketplace_connection_id=l.marketplace_connection_id AND m.shopify_variant_id=l.variant_id AND m.status='ACTIVE'
                AND NOT EXISTS(SELECT 1 FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id)
            ) part ON true
            LEFT JOIN LATERAL (
              SELECT CASE WHEN v.currency=o.currency THEN round(v.list_cost*(1-v.discount_rate/100),4) END cost
              FROM vendor_catalog_offers v JOIN account_catalog_items i ON i.tenant_id=v.tenant_id AND i.id=v.account_catalog_item_id
              WHERE v.tenant_id=o.tenant_id AND v.account_catalog_item_id=part.item AND i.status='ACTIVE'
                AND v.effective_from<=current_date AND (v.effective_to IS NULL OR v.effective_to>=current_date)
              ORDER BY v.is_default DESC,v.effective_from DESC,v.id LIMIT 1
            ) offer ON true
            WHERE o.tenant_id=:tenant AND o.marketplace_connection_id=:connection AND o.id IN (:ids)
            ORDER BY o.id,l.id,part.item
            """,args);
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();for(var r:rows)grouped.computeIfAbsent(text(r,"order_id"),k->new ArrayList<>()).add(r);
        var origins=origins(tenant,connection,ids);
        var candidates=candidates(tenant); // One bounded batch for the page, never one query per row.
        var rates=costs.rates(tenant);LocalDate today=LocalDate.now(java.time.ZoneId.of("America/Los_Angeles"));
        var result=new LinkedHashMap<String,ProfitRepository.View>();
        for(var entry:grouped.entrySet()){
            var first=entry.getValue().getFirst();var byLine=new LinkedHashMap<UUID,List<Map<String,Object>>>();
            for(var r:entry.getValue())byLine.computeIfAbsent((UUID)r.get("line_id"),k->new ArrayList<>()).add(r);
            var lines=new ArrayList<ProfitRepository.Line>();var composition=new HashMap<UUID,BigDecimal>();boolean complete=Boolean.TRUE.equals(first.get("evidence_complete"));
            for(var partRows:byLine.values()){
                var line=partRows.getFirst();int qty=((Number)line.get("ordered_quantity")).intValue();if(qty<1)continue;
                BigDecimal cost=BigDecimal.ZERO;boolean known=true;
                for(var r:partRows){if(r.get("item")==null||r.get("quantity")==null){complete=false;known=false;continue;}var quantity=(BigDecimal)r.get("quantity");composition.merge((UUID)r.get("item"),quantity.multiply(BigDecimal.valueOf(qty)),BigDecimal::add);if(r.get("cost")==null)known=false;else cost=cost.add(quantity.multiply((BigDecimal)r.get("cost")));}
                BigDecimal sale=Objects.equals(line.get("line_currency"),first.get("currency"))?(BigDecimal)line.get("line_total"):null;
                lines.add(new ProfitRepository.Line(text(line,"seller_sku"),text(line,"title"),qty,sale==null?null:sale.divide(BigDecimal.valueOf(qty),12,RoundingMode.HALF_UP),known?cost:null,otherCost(text(first,"currency"),(BigDecimal)line.get("profit_other_cost"),text(line,"profit_other_currency")),null,(UUID)line.get("variant_id")));
            }
            String packageName=text(first,"package_name"),source="Explicit saved order package";
            var basis=new Basis(complete?composition:Map.of(),origins.getOrDefault(entry.getKey(),Map.of()));
            if(packageName.isBlank())for(var candidate:candidates)if(basis.matches(candidate.basis())){packageName=candidate.name();source="Reused saved package · "+candidate.source()+" · exact mapped quantities and warehouse locations";break;}
            var type=ProfitRepository.packageType("",packageName,"");
            BigDecimal shipping="USD".equals(first.get("currency"))?ProfitShippingRates.cost(rates,type,today):null;
            var packages=shipping==null?List.<ProfitRepository.PackageCost>of():List.of(new ProfitRepository.PackageCost(packageName,shipping));
            String note="Mapped catalogue costs are current estimates. "+(packageName.isBlank()?"No compatible saved package with complete composition and origin evidence was found in the latest 500 saved orders.":source+". Package: "+packageName+". ")+" Shipping uses the account planning-rate schedule as of "+today+"; this is not a carrier quote. Estimated profit before payment fees = item sales − product cost − planning shipping − other cost. Other cost defaults to USD 1 per sold SKU unit for insulation/ice. Payment fees, customer shipping, taxes and refund effects are excluded; this is not settled net profit.";
            var totals=first.get("cancelled_at")!=null||!Boolean.TRUE.equals(first.get("evidence_complete"))?null:totals(lines,shipping);
            result.put(entry.getKey(),new ProfitRepository.View(entry.getKey(),"SHOPIFY_ORDER",text(first,"order_name"),text(first,"currency"),today,List.copyOf(lines),packages,totals,note,type==null?"":type.name()));
        }
        return result;
    }
    private Map<String,Map<Identity,BigDecimal>> origins(UUID tenant,UUID connection,List<UUID> ids){
        var out=new HashMap<String,Map<Identity,BigDecimal>>();
        for(var r:named.queryForList("""
            SELECT l.order_id,r.account_catalog_item_id,r.location_id,sum(r.quantity) quantity
            FROM order_inventory_reservations r JOIN shopify_order_lines l ON l.tenant_id=r.tenant_id AND l.marketplace_connection_id=r.marketplace_connection_id AND l.id=r.shopify_order_line_id
            JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id AND loc.status='ACTIVE'
            WHERE r.tenant_id=:tenant AND r.marketplace_connection_id=:connection AND l.order_id IN (:ids) AND r.status IN ('ACTIVE','SHIPPED')
            GROUP BY l.order_id,r.account_catalog_item_id,r.location_id
            """,Map.of("tenant",tenant,"connection",connection,"ids",ids)))out.computeIfAbsent(text(r,"order_id"),k->new HashMap<>()).put(new Identity((UUID)r.get("account_catalog_item_id"),(UUID)r.get("location_id")),(BigDecimal)r.get("quantity"));
        return out;
    }
    record Candidate(String name,String source,Basis basis){}
    private List<Candidate> candidates(UUID tenant){
        // Raw reports and shipping purchases are never changed or used as live quotes.
        // Only saved package names with complete mapped physical stock evidence qualify.
        var data=named.queryForList("""
            WITH saved AS MATERIALIZED (
              SELECT p.marketplace_connection_id,p.amazon_order_id,p.packaging,p.imported_at
              FROM temporary_order_packaging_lookup p JOIN amazon_orders o ON o.tenant_id=p.tenant_id AND o.marketplace_connection_id=p.marketplace_connection_id AND o.amazon_order_id=p.amazon_order_id
              WHERE p.tenant_id=:tenant AND nullif(btrim(p.packaging),'') IS NOT NULL AND o.order_status NOT IN ('Canceled','Cancelled')
              ORDER BY p.imported_at DESC,p.amazon_order_id LIMIT 500
            )
            SELECT s.marketplace_connection_id,s.amazon_order_id,s.packaging,s.imported_at,i.id line_id,i.quantity_ordered,
              c.account_catalog_item_id item,c.quantity,
              r.location_id,r.stock_quantity,
              EXISTS(SELECT 1 FROM order_inventory_reservations extra WHERE extra.tenant_id=i.tenant_id AND extra.marketplace_connection_id=i.marketplace_connection_id AND extra.amazon_order_item_id=i.id AND extra.status IN ('ACTIVE','SHIPPED')
                AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components cp WHERE cp.tenant_id=m.tenant_id AND cp.marketplace_sku_mapping_id=m.id AND cp.account_catalog_item_id=extra.account_catalog_item_id)
                AND (m.account_catalog_item_id IS NULL OR extra.account_catalog_item_id<>m.account_catalog_item_id OR EXISTS(SELECT 1 FROM marketplace_sku_mapping_components cp WHERE cp.tenant_id=m.tenant_id AND cp.marketplace_sku_mapping_id=m.id))) unexpected_stock
            FROM saved s JOIN amazon_order_items i ON i.tenant_id=:tenant AND i.marketplace_connection_id=s.marketplace_connection_id AND i.amazon_order_id=s.amazon_order_id AND i.quantity_ordered>0
            LEFT JOIN marketplace_sku_mappings m ON m.tenant_id=i.tenant_id AND m.marketplace_connection_id=i.marketplace_connection_id AND m.marketplace_sku=i.seller_sku AND m.shopify_variant_id IS NULL AND m.status='ACTIVE'
            LEFT JOIN LATERAL (
              SELECT part.account_catalog_item_id,part.quantity FROM marketplace_sku_mapping_components part WHERE part.tenant_id=m.tenant_id AND part.marketplace_sku_mapping_id=m.id
              UNION ALL SELECT m.account_catalog_item_id,greatest(m.quantity_per_marketplace_unit,1) WHERE m.account_catalog_item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components x WHERE x.tenant_id=m.tenant_id AND x.marketplace_sku_mapping_id=m.id)
            ) c ON true
            LEFT JOIN LATERAL (
              SELECT r.location_id,sum(r.quantity) stock_quantity FROM order_inventory_reservations r
              JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id AND loc.status='ACTIVE'
              WHERE r.tenant_id=i.tenant_id AND r.marketplace_connection_id=i.marketplace_connection_id AND r.amazon_order_item_id=i.id AND r.account_catalog_item_id=c.account_catalog_item_id AND r.status IN ('ACTIVE','SHIPPED')
              GROUP BY r.location_id
            ) r ON true
            ORDER BY s.imported_at DESC,s.amazon_order_id,i.id,c.account_catalog_item_id,r.location_id
            """,Map.of("tenant",tenant));
        var groups=new LinkedHashMap<String,List<Map<String,Object>>>();for(var r:data)groups.computeIfAbsent(text(r,"marketplace_connection_id")+":"+text(r,"amazon_order_id"),k->new ArrayList<>()).add(r);
        var result=new ArrayList<Candidate>();
        for(var rows:groups.values()){
            var items=new HashMap<UUID,BigDecimal>();var locations=new HashMap<Identity,BigDecimal>();var seen=new HashSet<String>();boolean complete=true;
            for(var r:rows){if(Boolean.TRUE.equals(r.get("unexpected_stock"))||r.get("item")==null||r.get("location_id")==null){complete=false;continue;}UUID item=(UUID)r.get("item");String line=text(r,"line_id")+":"+item;
                if(seen.add(line))items.merge(item,((BigDecimal)r.get("quantity")).multiply(BigDecimal.valueOf(((Number)r.get("quantity_ordered")).longValue())),BigDecimal::add);
                locations.merge(new Identity(item,(UUID)r.get("location_id")),(BigDecimal)r.get("stock_quantity"),BigDecimal::add);
            }
            if(complete)result.add(new Candidate(text(rows.getFirst(),"packaging"),"Amazon packaging history · "+text(rows.getFirst(),"imported_at"),new Basis(items,locations)));
        }
        return result;
    }
}
