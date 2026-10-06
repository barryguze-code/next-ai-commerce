package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProfitRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ProfitCostRepository costs;
    public ProfitRepository(JdbcTemplate jdbc,ProfitCostRepository costs) {
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.costs=costs;
    }
    public record Line(String sku,String title,int quantity,BigDecimal unitPrice,BigDecimal productCost,
                       BigDecimal otherCost,BigDecimal customerShipping) {}
    public record PackageCost(String description,BigDecimal amount) {
        public PackageCost {
            if(description==null||description.isBlank()||description.length()>120||amount==null||amount.signum()<0||amount.scale()>2||amount.compareTo(new BigDecimal("100000"))>0)
                throw new IllegalArgumentException("Enter a package description and valid cost");
        }
    }
    public record View(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,
                       List<PackageCost> packages,ProfitEstimate.Breakdown totals,String note,String packageType) {}

    // Catalogue costs are estimates, not historical FIFO/settlement expenses. All
    // components must have a known USD cost: partial bundle costs must never look complete.
    private static final String COST_JOIN="""
        LEFT JOIN LATERAL (
          SELECT CASE WHEN count(*)>0 AND count(offer.cost)=count(*) THEN sum(part.quantity*offer.cost) END cost
          FROM marketplace_sku_mappings mapping
          CROSS JOIN LATERAL (
            SELECT c.account_catalog_item_id item,c.quantity FROM marketplace_sku_mapping_components c
            WHERE c.tenant_id=mapping.tenant_id AND c.marketplace_sku_mapping_id=mapping.id
            UNION ALL SELECT mapping.account_catalog_item_id,greatest(mapping.quantity_per_marketplace_unit,1)
            WHERE mapping.account_catalog_item_id IS NOT NULL AND NOT EXISTS (
              SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=mapping.tenant_id AND c.marketplace_sku_mapping_id=mapping.id)
          ) part
          LEFT JOIN LATERAL (
            SELECT CASE WHEN v.currency='USD' THEN round(v.list_cost*(1-v.discount_rate/100),4) END cost
            FROM vendor_catalog_offers v WHERE v.tenant_id=mapping.tenant_id AND v.account_catalog_item_id=part.item
              AND v.effective_from<=current_date AND (v.effective_to IS NULL OR v.effective_to>=current_date)
            ORDER BY v.is_default DESC,v.effective_from DESC,v.id LIMIT 1
          ) offer ON true
          WHERE mapping.tenant_id=l.tenant_id AND mapping.marketplace_connection_id=l.marketplace_connection_id
            AND mapping.marketplace_sku=l.seller_sku AND mapping.status='ACTIVE'
        ) product ON true
        LEFT JOIN marketplace_sku_package_defaults package_default ON package_default.tenant_id=l.tenant_id
          AND package_default.marketplace_connection_id=l.marketplace_connection_id AND package_default.seller_sku=l.seller_sku
        LEFT JOIN shipping_package_profiles profile ON profile.tenant_id=package_default.tenant_id
          AND profile.id=package_default.package_profile_id AND profile.status='ACTIVE'
        """;

    @Transactional(readOnly=true)
    public Map<String,View> skus(UUID tenant,UUID connection,List<String> keys) {
        scope(tenant);if(keys.isEmpty())return Map.of();
        var rows=named.queryForList("""
            SELECT l.seller_sku,l.item_name,coalesce(CASE WHEN sale.status='CONFIRMED'
                AND (sale.owned_discount#>>'{0,schedule,0,start_at}')::timestamptz<=now()
                AND (sale.owned_discount#>>'{0,schedule,0,end_at}')::timestamptz>now()
                THEN (sale.owned_discount#>>'{0,schedule,0,value_with_tax}')::numeric END,l.price) price,
              coalesce(nullif(trim(l.currency),''),md.currency_code) currency,l.fulfillment_channel,l.other_cost_per_sku,
              l.profit_package_type,product.cost,coalesce(profile.name,packing.packaging) package_name,profile.preferred_carrier carrier
            FROM amazon_listings l
            JOIN marketplace_connections mc ON mc.tenant_id=l.tenant_id AND mc.id=l.marketplace_connection_id
            LEFT JOIN marketplace_definitions md ON md.channel=mc.channel AND md.marketplace_identifier=mc.marketplace_identifier
            LEFT JOIN shelf_sale_publications sale ON sale.tenant_id=l.tenant_id
              AND sale.connection_id=l.marketplace_connection_id
              AND sale.marketplace_id=l.marketplace_id AND sale.seller_sku=l.seller_sku
            LEFT JOIN LATERAL (
              SELECT p.packaging FROM temporary_order_packaging_lookup p
              WHERE p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id
                AND (p.order_sku_qty_list=l.seller_sku||'-1'
                  OR (nullif(l.asin,'') IS NOT NULL AND p.order_item_summary=l.asin||'-1'))
              ORDER BY CASE WHEN p.order_sku_qty_list=l.seller_sku||'-1' THEN 0 ELSE 1 END,p.imported_at DESC,p.amazon_order_id LIMIT 1
            ) packing ON true
            """+COST_JOIN+" WHERE l.tenant_id=:tenant AND l.marketplace_connection_id=:connection AND l.seller_sku IN (:keys)",
            Map.of("tenant",tenant,"connection",connection,"keys",keys));
        var rates=costs.rates(tenant);var result=new LinkedHashMap<String,View>();
        Map<String,List<PackageCost>> skuPackages=new HashMap<>();
        for(var saved:named.queryForList("SELECT seller_sku,description,amount FROM profit_sku_packages WHERE tenant_id=:tenant AND marketplace_connection_id=:connection AND seller_sku IN (:keys) ORDER BY sequence",Map.of("tenant",tenant,"connection",connection,"keys",keys)))
            skuPackages.computeIfAbsent(text(saved,"seller_sku"),k->new ArrayList<>()).add(new PackageCost(text(saved,"description"),decimal(saved,"amount")));
        LocalDate today=LocalDate.now(java.time.ZoneId.of("America/Los_Angeles"));
        for(var r:rows){
            String key=text(r,"seller_sku"),currency=text(r,"currency");
            var type=packageType(text(r,"profit_package_type"),text(r,"package_name"),text(r,"carrier"));
            BigDecimal amount=ProfitShippingRates.cost(rates,type,today);
            var packages=skuPackages.getOrDefault(key,amount==null?List.<PackageCost>of():List.of(new PackageCost(type.name(),amount)));
            var lines=List.of(new Line(key,text(r,"item_name"),1,decimal(r,"price"),decimal(r,"cost"),decimal(r,"other_cost_per_sku"),BigDecimal.ZERO));
            result.put(key,view(key,"SKU",text(r,"item_name"),currency,today,lines,packages,text(r,"fulfillment_channel"),type));
        }
        return result;
    }
    @Transactional(readOnly=true)
    public Map<String,View> orders(UUID tenant,UUID connection,List<String> keys) {
        scope(tenant);if(keys.isEmpty())return Map.of();
        var args=Map.of("tenant",tenant,"connection",connection,"keys",keys);
        var rows=named.queryForList("""
            SELECT o.amazon_order_id,o.fulfillment_channel,o.currency,o.order_status,
              (o.purchase_date AT TIME ZONE coalesce(mc.reporting_timezone,'America/Los_Angeles'))::date purchase_day,
              i.seller_sku,i.title,i.quantity_ordered,
              (i.item_price-coalesce(i.promotion_discount,0))/nullif(i.quantity_ordered,0) unit_price,
              coalesce(i.shipping_price,0)-coalesce(i.shipping_discount,0) customer_shipping,
              coalesce(l.other_cost_per_sku,1.00) other_cost_per_sku,product.cost,
              l.profit_package_type,profile.name package_name,profile.preferred_carrier carrier,packing.packaging order_package
            FROM amazon_orders o JOIN amazon_order_items i ON i.tenant_id=o.tenant_id
              AND i.marketplace_connection_id=o.marketplace_connection_id AND i.amazon_order_id=o.amazon_order_id
            JOIN marketplace_connections mc ON mc.tenant_id=o.tenant_id AND mc.id=o.marketplace_connection_id
            LEFT JOIN amazon_listings l ON l.tenant_id=i.tenant_id AND l.marketplace_connection_id=i.marketplace_connection_id
              AND l.seller_sku=i.seller_sku
            LEFT JOIN LATERAL (
              SELECT p.packaging FROM temporary_order_packaging_lookup p
              WHERE p.tenant_id=o.tenant_id AND p.marketplace_connection_id=o.marketplace_connection_id
                AND (p.amazon_order_id=o.amazon_order_id OR p.order_item_summary=(
                  SELECT string_agg(coalesce(nullif(oi.asin,''),'UNKNOWN')||'-'||oi.quantity_ordered::text,',' ORDER BY oi.asin,oi.quantity_ordered)
                  FROM amazon_order_items oi WHERE oi.tenant_id=o.tenant_id
                    AND oi.marketplace_connection_id=o.marketplace_connection_id AND oi.amazon_order_id=o.amazon_order_id AND oi.quantity_ordered>0))
              ORDER BY CASE WHEN p.amazon_order_id=o.amazon_order_id THEN 0 ELSE 1 END,p.imported_at DESC,p.amazon_order_id LIMIT 1
            ) packing ON true
            """+COST_JOIN+" WHERE o.tenant_id=:tenant AND o.marketplace_connection_id=:connection AND o.amazon_order_id IN (:keys) ORDER BY o.amazon_order_id,i.id",args);
        var saved=named.queryForList("SELECT amazon_order_id,description,amount FROM profit_order_packages WHERE tenant_id=:tenant AND marketplace_connection_id=:connection AND amazon_order_id IN (:keys) ORDER BY sequence",args);
        Map<String,List<PackageCost>> packageMap=new HashMap<>();
        for(var r:saved)packageMap.computeIfAbsent(text(r,"amazon_order_id"),k->new ArrayList<>()).add(new PackageCost(text(r,"description"),decimal(r,"amount")));
        Map<String,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        rows.forEach(r->grouped.computeIfAbsent(text(r,"amazon_order_id"),k->new ArrayList<>()).add(r));
        var rates=costs.rates(tenant);var result=new LinkedHashMap<String,View>();
        for(var entry:grouped.entrySet()){
            var first=entry.getValue().getFirst();Object date=first.get("purchase_day");
            LocalDate day=date instanceof java.sql.Date d?d.toLocalDate():date instanceof LocalDate d?d:null;
            var lines=new ArrayList<Line>();
            for(var r:entry.getValue()){
                int quantity=((Number)r.get("quantity_ordered")).intValue();
                if(quantity<1)continue;
                lines.add(new Line(text(r,"seller_sku"),text(r,"title"),quantity,decimal(r,"unit_price"),decimal(r,"cost"),decimal(r,"other_cost_per_sku"),decimal(r,"customer_shipping")));
            }
            var packages=packageMap.getOrDefault(entry.getKey(),List.of());
            var type=packageType(text(first,"profit_package_type"),text(first,"package_name"),text(first,"carrier"));
            var orderType=packageType("",text(first,"order_package"),"");
            if(orderType!=null)type=orderType;
            if(packages.isEmpty()&&(orderType!=null||(lines.size()==1&&lines.getFirst().quantity()==1))){
                var amount=ProfitShippingRates.cost(rates,type,day);
                if(amount!=null)packages=List.of(new PackageCost(type.name(),amount));
            }
            var estimate=view(entry.getKey(),"ORDER",entry.getKey(),text(first,"currency"),day,lines,packages,text(first,"fulfillment_channel"),type);
            String status=text(first,"order_status").replaceAll("[^A-Za-z]","").toUpperCase(Locale.ROOT);
            if(Set.of("CANCELLED","CANCELED").contains(status))
                estimate=new View(estimate.key(),estimate.kind(),estimate.title(),estimate.currency(),estimate.date(),estimate.lines(),estimate.packages(),null,"Cancelled order — no sales profit estimated.",estimate.packageType());
            result.put(entry.getKey(),estimate);
        }
        return result;
    }
    private static View view(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,List<PackageCost> packages,String fulfillment,ProfitShippingRates.PackageType type){
        boolean fba=Set.of("AFN","FBA","AMAZON").contains(fulfillment.toUpperCase(Locale.ROOT));
        String note=fba?"FBA profit is not available yet.":!"USD".equals(currency)?"Only USD estimates are supported.":
            "Estimated using current catalogue costs; excludes refunds, advertising and settlement adjustments. Order revenue excludes tax and promotions. Confirm shipping packages for multi-unit orders.";
        ProfitEstimate.Breakdown total=null;
        if(!fba&&"USD".equals(currency)&&!lines.isEmpty()&&lines.stream().allMatch(l->(l.unitPrice()==null||l.unitPrice().signum()>=0)&&l.customerShipping().signum()>=0))
            total=ProfitEstimate.calculate(lines.stream().map(l->new ProfitEstimate.Line(l.unitPrice(),l.quantity(),l.productCost(),l.otherCost(),l.customerShipping())).toList(),packages.stream().map(PackageCost::amount).toList());
        return new View(key,kind,title,currency,date,lines,packages,total,note,type==null?null:type.name());
    }
    @Transactional
    public void savePackages(UUID tenant,UUID connection,String order,List<PackageCost> packages,String actor){
        if(packages==null||packages.isEmpty()||packages.size()>50)throw new IllegalArgumentException("Confirm between 1 and 50 packages");
        scope(tenant);
        var found=jdbc.queryForList("SELECT id FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? FOR UPDATE",tenant,connection,order);
        if(found.isEmpty())throw new IllegalArgumentException("Order not found in this store");
        jdbc.update("DELETE FROM profit_order_packages WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",tenant,connection,order);
        int sequence=0;for(var p:packages)jdbc.update("INSERT INTO profit_order_packages(tenant_id,marketplace_connection_id,amazon_order_id,sequence,description,amount,updated_by) VALUES (?,?,?,?,?,?,?)",tenant,connection,order,++sequence,p.description(),p.amount(),actor);
    }
    @Transactional
    public void saveSku(UUID tenant,UUID connection,String sku,ProfitShippingRates.PackageType type,BigDecimal other){
        if(other==null||other.signum()<0||other.scale()>2||other.compareTo(new BigDecimal("100000"))>0)throw new IllegalArgumentException("Enter a valid other cost");
        scope(tenant);
        if(jdbc.update("UPDATE amazon_listings SET profit_package_type=?,other_cost_per_sku=? WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=?",type==null?null:type.name(),other,tenant,connection,sku)==0)
            throw new IllegalArgumentException("SKU not found in this store");
    }
    @Transactional
    public void saveSku(UUID tenant,UUID connection,String sku,ProfitShippingRates.PackageType type,BigDecimal other,List<PackageCost> packages){
        if(packages==null||packages.isEmpty()||packages.size()>50||packages.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Confirm between 1 and 50 packages");
        saveSku(tenant,connection,sku,type,other);
        jdbc.update("DELETE FROM profit_sku_packages WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=?",tenant,connection,sku);
        int sequence=0;
        for(var p:packages)jdbc.update("INSERT INTO profit_sku_packages(tenant_id,marketplace_connection_id,seller_sku,sequence,description,amount) VALUES (?,?,?,?,?,?)",tenant,connection,sku,++sequence,p.description(),p.amount());
    }
    static ProfitShippingRates.PackageType packageType(String explicit,String name,String carrier){
        if(!explicit.isBlank())return ProfitShippingRates.PackageType.valueOf(explicit);
        String value=name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]","");
        if(carrier.equalsIgnoreCase("UPS")||name.toUpperCase(Locale.ROOT).contains("UPS"))return ProfitShippingRates.PackageType.UPS;
        value=value.replace("FEDEX","").replace("BOX","");
        return switch(value){case "XSMALL","EXTRASMALL"->ProfitShippingRates.PackageType.FEDEX_XSMALL;case "PAK"->ProfitShippingRates.PackageType.FEDEX_PAK;case "SMALL"->ProfitShippingRates.PackageType.FEDEX_SMALL;case "MEDIUM"->ProfitShippingRates.PackageType.FEDEX_MEDIUM;case "LARGE"->ProfitShippingRates.PackageType.FEDEX_LARGE;case "XLARGE","EXTRALARGE"->ProfitShippingRates.PackageType.FEDEX_XLARGE;default->null;};
    }
    private static String text(Map<String,Object> r,String key){return Objects.toString(r.get(key),"");}
    private static BigDecimal decimal(Map<String,Object> r,String key){return (BigDecimal)r.get(key);}
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
}
