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
    private com.nextaicommerce.platform.fees.AmazonFeeVarianceRepository acceptedFees;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureAcceptedFees(com.nextaicommerce.platform.fees.AmazonFeeVarianceRepository value){acceptedFees=value;}
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
                       List<PackageCost> packages,ProfitEstimate.Breakdown totals,String note,String packageType,
                       boolean fba,BigDecimal fbaFee,BigDecimal inboundShippingCost,BigDecimal placementFee,
                       BigDecimal fbaReferralFee,String inboundSource,String feeSource,String feeSourceSummary) {
        public View(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,List<PackageCost> packages,ProfitEstimate.Breakdown totals,String note,String packageType,boolean fba,BigDecimal fbaFee,BigDecimal inboundShippingCost,BigDecimal placementFee,BigDecimal fbaReferralFee,String inboundSource){this(key,kind,title,currency,date,lines,packages,totals,note,packageType,fba,fbaFee,inboundShippingCost,placementFee,fbaReferralFee,inboundSource,null,null);}
        public View(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,List<PackageCost> packages,ProfitEstimate.Breakdown totals,String note,String packageType,boolean fba,BigDecimal fbaFee,BigDecimal inboundShippingCost,BigDecimal placementFee,BigDecimal fbaReferralFee){this(key,kind,title,currency,date,lines,packages,totals,note,packageType,fba,fbaFee,inboundShippingCost,placementFee,fbaReferralFee,null);}
        public View(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,List<PackageCost> packages,ProfitEstimate.Breakdown totals,String note,String packageType,boolean fba,BigDecimal fbaFee,BigDecimal inboundShippingCost,BigDecimal placementFee){this(key,kind,title,currency,date,lines,packages,totals,note,packageType,fba,fbaFee,inboundShippingCost,placementFee,null);}
        public View(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,List<PackageCost> packages,ProfitEstimate.Breakdown totals,String note,String packageType){this(key,kind,title,currency,date,lines,packages,totals,note,packageType,false,null,null,null);}
    }

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
        return skus(tenant,connection,keys,null);
    }
    /** Export pins the existing planning-rate date to its read snapshot. */
    @Transactional(readOnly=true)
    public Map<String,View> skus(UUID tenant,UUID connection,List<String> keys,LocalDate planningDate) {
        scope(tenant);if(keys.isEmpty())return Map.of();
        var rows=named.queryForList("""
            SELECT l.seller_sku,l.item_name,coalesce(CASE WHEN sale.status='CONFIRMED'
                AND (sale.owned_discount#>>'{0,schedule,0,start_at}')::timestamptz<=now()
                AND (sale.owned_discount#>>'{0,schedule,0,end_at}')::timestamptz>now()
                THEN (sale.owned_discount#>>'{0,schedule,0,value_with_tax}')::numeric END,l.price) price,
              coalesce(nullif(trim(l.currency),''),md.currency_code) currency,l.fulfillment_channel,l.other_cost_per_sku,
              l.profit_package_type,coalesce(l.product_cost_override,product.cost) cost,coalesce(profile.name,packing.packaging) package_name,profile.preferred_carrier carrier
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
        LocalDate today=planningDate==null?LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")):planningDate;
        for(var r:rows){
            String key=text(r,"seller_sku"),currency=text(r,"currency");
            var type=packageType(text(r,"profit_package_type"),text(r,"package_name"),text(r,"carrier"));
            BigDecimal amount=ProfitShippingRates.cost(rates,type,today);
            var packages=skuPackages.getOrDefault(key,amount==null?List.<PackageCost>of():List.of(new PackageCost(type.name(),amount)));
            var lines=List.of(new Line(key,text(r,"item_name"),1,decimal(r,"price"),decimal(r,"cost"),decimal(r,"other_cost_per_sku"),BigDecimal.ZERO));
            result.put(key,view(key,"SKU",text(r,"item_name"),currency,today,lines,packages,text(r,"fulfillment_channel"),type));
        }
        return withFbaFees(tenant,connection,result);
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
              coalesce(l.other_cost_per_sku,1.00) other_cost_per_sku,coalesce(l.product_cost_override,product.cost) cost,
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
        return withFbaFees(tenant,connection,result);
    }
    /** Bounded bulk lookup; do not substitute ASIN fees for another condition/SKU or currency. */
    private Map<String,View> withFbaFees(UUID tenant,UUID connection,Map<String,View> views){
        var skus=views.values().stream().filter(View::fba).flatMap(v->v.lines().stream()).map(Line::sku).distinct().toList();
        if(skus.isEmpty())return views;
        boolean historical=views.values().stream().allMatch(v->"ORDER".equals(v.kind()));
        var frozen=historical&&acceptedFees!=null?acceptedFees.orderBasis(tenant,connection,views.keySet()):Map.<String,Map<String,BigDecimal>>of();
        var fees=named.queryForList("""
            SELECT DISTINCT ON (f.seller_sku,f.currency) f.seller_sku,f.fulfillment_fee,f.referral_fee,f.currency,
              to_char(f.effective_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS') imported_at_utc,
              floor(extract(epoch FROM (current_timestamp-f.effective_at))/86400)::bigint import_age_days,
              CASE WHEN d.id IS NULL THEN 'Fee archive unavailable'
                WHEN jsonb_exists(f.raw_payload,'FeesEstimateIdentifier') THEN 'Amazon per-SKU fee quote'
                ELSE 'Amazon fee report' END source_label
            FROM amazon_fee_estimates f
            JOIN marketplace_connections c ON c.tenant_id=f.tenant_id AND c.id=f.marketplace_connection_id
            LEFT JOIN amazon_source_documents d ON d.tenant_id=f.tenant_id
              AND d.marketplace_connection_id=f.marketplace_connection_id AND d.id=f.source_document_id
            WHERE f.tenant_id=:tenant AND f.marketplace_connection_id=:connection AND f.seller_sku IN (:skus)
              AND f.marketplace_id=c.marketplace_identifier
              AND (:historical=false OR (NOT jsonb_exists(f.raw_payload,'FeesEstimateIdentifier')
                AND NOT jsonb_exists(f.raw_payload,'_platformQuoteBasis') AND NOT jsonb_exists(f.raw_payload,'_platformFeeContract')))
              AND (NOT(jsonb_exists(f.raw_payload,'currency')) OR f.raw_payload->>'currency'=f.currency)
              AND f.currency=(SELECT currency_code FROM marketplace_definitions WHERE channel='AMAZON' AND marketplace_identifier=f.marketplace_id)
              AND (NOT jsonb_exists(f.raw_payload,'amazon-store') OR f.raw_payload->>'amazon-store'=(SELECT country_code FROM marketplace_definitions WHERE channel='AMAZON' AND marketplace_identifier=f.marketplace_id))
            ORDER BY f.seller_sku,f.currency,f.effective_at DESC,f.id
            """,Map.of("tenant",tenant,"connection",connection,"skus",skus,"historical",historical));
        var inbound=named.queryForList("""
            WITH charges AS MATERIALIZED (
              SELECT amazon_order_id shipment_id,max(posted_date) posted_at,
                CASE WHEN count(DISTINCT currency)=1 AND bool_and(amount IS NOT NULL AND currency IS NOT NULL)
                  THEN sum(-amount) FILTER (WHERE description='FBAPostInboundTransportation') END transport,
                CASE WHEN count(DISTINCT currency)=1 AND bool_and(amount IS NOT NULL AND currency IS NOT NULL)
                  THEN sum(-amount) FILTER (WHERE description='FBAInboundConvenience') END placement,
                CASE WHEN count(DISTINCT currency)=1 THEN min(currency) END currency
              FROM amazon_financial_transactions
              WHERE tenant_id=:tenant AND marketplace_connection_id=:connection
                AND transaction_type IN ('ServiceFee','ServiceFee - Reversal')
                AND description IN ('FBAPostInboundTransportation','FBAInboundConvenience')
                AND raw_payload->>'transactionStatus' IN ('RELEASED','DEFERRED_RELEASED')
                AND posted_date IS NOT NULL
              GROUP BY amazon_order_id
            ), latest AS (
              SELECT DISTINCT ON (i.seller_sku) i.seller_sku,c.*,
                (c.posted_at AT TIME ZONE 'America/Los_Angeles')::date estimate_date
              FROM amazon_fba_shipment_items i
              JOIN amazon_fba_shipments s ON s.tenant_id=i.tenant_id
                AND s.marketplace_connection_id=i.marketplace_connection_id AND s.shipment_id=i.shipment_id
              JOIN charges c ON c.shipment_id=i.shipment_id
              WHERE i.tenant_id=:tenant AND i.marketplace_connection_id=:connection
                AND i.seller_sku IN (:skus) AND i.quantity_shipped>0
                AND c.transport IS NOT NULL AND c.placement IS NOT NULL
                AND c.transport>=0 AND c.placement>=0 AND c.currency='USD'
                AND s.shipment_status NOT IN ('CANCELLED','DELETED')
              ORDER BY i.seller_sku,c.posted_at DESC,c.shipment_id
            )
            SELECT l.*,l.transport/nullif(units.total,0) transport_unit,l.placement/nullif(units.total,0) placement_unit
            FROM latest l CROSS JOIN LATERAL (
              SELECT sum(quantity_shipped)::numeric total FROM amazon_fba_shipment_items i
              WHERE i.tenant_id=:tenant AND i.marketplace_connection_id=:connection AND i.shipment_id=l.shipment_id
            ) units
            """,Map.of("tenant",tenant,"connection",connection,"skus",skus));
        views.replaceAll((key,v)->{
            if(!v.fba())return v;
            BigDecimal total=BigDecimal.ZERO,referral=BigDecimal.ZERO;boolean known=true,referralKnown=true;
            BigDecimal transport=BigDecimal.ZERO,placement=BigDecimal.ZERO;boolean transportKnown=true,placementKnown=true;
            var sources=new LinkedHashSet<String>();var feeSources=new LinkedHashSet<String>();var feeSummaries=new LinkedHashSet<String>();
            var basis=frozen.getOrDefault(v.key(),Map.of());
            for(var line:v.lines()){
                var fee=fees.stream().filter(f->line.sku().equals(f.get("seller_sku"))&&v.currency().equals(f.get("currency"))).findFirst().orElse(null);
                if(fee!=null){
                    long age=((Number)fee.get("import_age_days")).longValue();
                    var provenance=new ArrayList<String>();
                    if(!historical)provenance.add(fee.get("source_label").toString());
                    else{
                        if(!basis.containsKey("FBA")&&fee.get("fulfillment_fee")!=null)provenance.add("FBA · Current report estimate; historical FBA basis unavailable · "+fee.get("source_label"));
                        if(!basis.containsKey("REFERRAL")&&fee.get("referral_fee")!=null)provenance.add("Referral · Current report estimate; historical referral basis unavailable · "+fee.get("source_label"));
                    }
                    for(String label:provenance){
                        feeSummaries.add(label+" · imported "+fee.get("imported_at_utc").toString().substring(0,10)+" UTC");
                        feeSources.add(line.sku()+" · "+label+" · imported "+fee.get("imported_at_utc")+" UTC · "+(age<0?"import time needs review":age+" days since import")+"; freshness not assessed");
                    }
                }
                // Explicitly prospective SKU planning only. Historical Orders retain their prior path/basis.
                if("SKU".equals(v.kind())&&acceptedFees!=null&&acceptedFees.prospectiveEnabled()){
                    String market=jdbc.queryForObject("SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,connection);
                    BigDecimal shippingBasis=null;
                    if(line.customerShipping()!=null)try{shippingBasis=line.customerShipping().divide(BigDecimal.valueOf(line.quantity()),4,RoundingMode.UNNECESSARY);}catch(ArithmeticException ignored){}
                    var fbaAccepted=acceptedFees.applicableSku(tenant,connection,market,"FBA",line.sku(),v.currency(),"FBA",line.unitPrice(),shippingBasis);
                    var referralAccepted=acceptedFees.applicableSku(tenant,connection,market,"FBA",line.sku(),v.currency(),"REFERRAL",line.unitPrice(),shippingBasis);
                    if(fbaAccepted!=null||referralAccepted!=null){
                        fee=fee==null?new LinkedHashMap<>():new LinkedHashMap<>(fee);fee.put("currency",v.currency());
                        if(fbaAccepted!=null)fee.put("fulfillment_fee",fbaAccepted);if(referralAccepted!=null)fee.put("referral_fee",referralAccepted);
                        feeSources.add(line.sku()+" · User-approved actual fee · prospective exact basis");
                        feeSummaries.add("User-approved actual fee · exact SKU basis");
                    }
                }
                if(fee==null||fee.get("fulfillment_fee")==null||!v.currency().equals(fee.get("currency")))known=false;
                else total=total.add(((BigDecimal)fee.get("fulfillment_fee")).multiply(BigDecimal.valueOf(line.quantity())));
                if(fee==null||fee.get("referral_fee")==null||!v.currency().equals(fee.get("currency")))referralKnown=false;
                else referral=referral.add(((BigDecimal)fee.get("referral_fee")).multiply(BigDecimal.valueOf(line.quantity())));
                var shipping=inbound.stream().filter(f->line.sku().equals(f.get("seller_sku"))).findFirst().orElse(null);
                if(shipping==null||shipping.get("transport_unit")==null||!v.currency().equals(shipping.get("currency")))transportKnown=false;
                else transport=transport.add(((BigDecimal)shipping.get("transport_unit")).multiply(BigDecimal.valueOf(line.quantity())));
                if(shipping==null||shipping.get("placement_unit")==null||!v.currency().equals(shipping.get("currency")))placementKnown=false;
                else placement=placement.add(((BigDecimal)shipping.get("placement_unit")).multiply(BigDecimal.valueOf(line.quantity())));
                if(shipping!=null)sources.add(shipping.get("shipment_id")+" · charges posted "+shipping.get("estimate_date"));
            }
            if(basis.containsKey("FBA")){total=basis.get("FBA");known=true;feeSources.add("Expected FBA fees fixed at purchase · complete exact item coverage");feeSummaries.add("FBA basis fixed at purchase");}
            if(basis.containsKey("REFERRAL")){referral=basis.get("REFERRAL");referralKnown=true;feeSources.add("Expected referral fees fixed at purchase · complete exact item coverage");feeSummaries.add("Referral basis fixed at purchase");}
            String note=v.note()+(historical?" Later per-SKU quotes cannot replace an order's purchase-time basis; any current report fallback is an estimate, not a historical charge.":"")+" Inbound estimate uses each SKU's latest complete shipment by posted charge date, not a historical order-to-replenishment match."+(sources.isEmpty()?"":" Source: "+String.join(", ",sources)+".");
            ProfitEstimate.Breakdown breakdown=null;
            if("USD".equals(v.currency())&&!v.lines().isEmpty()){
                BigDecimal revenue=v.lines().stream().allMatch(l->l.unitPrice()!=null&&l.customerShipping()!=null)?v.lines().stream().map(l->l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())).add(l.customerShipping())).reduce(BigDecimal.ZERO,BigDecimal::add):null;
                BigDecimal product=v.lines().stream().allMatch(l->l.productCost()!=null)?v.lines().stream().map(l->l.productCost().multiply(BigDecimal.valueOf(l.quantity()))).reduce(BigDecimal.ZERO,BigDecimal::add):null;
                BigDecimal other=v.lines().stream().allMatch(l->l.otherCost()!=null)?v.lines().stream().map(l->l.otherCost().multiply(BigDecimal.valueOf(l.quantity()))).reduce(BigDecimal.ZERO,BigDecimal::add):null;
                BigDecimal profit=revenue!=null&&product!=null&&other!=null&&known&&referralKnown&&transportKnown&&placementKnown?
                    revenue.subtract(product).subtract(other).subtract(total).subtract(referral).subtract(transport).subtract(placement).setScale(2,RoundingMode.HALF_UP):null;
                breakdown=new ProfitEstimate.Breakdown(revenue,product,referralKnown?referral:null,null,other,profit);
            }
            return new View(v.key(),v.kind(),v.title(),v.currency(),v.date(),v.lines(),List.of(),breakdown,note,null,true,known?total:null,
                transportKnown?transport.setScale(4,RoundingMode.HALF_UP):null,placementKnown?placement.setScale(4,RoundingMode.HALF_UP):null,referralKnown?referral:null,
                sources.isEmpty()?null:"Estimate · "+String.join("; ",sources),
                feeSources.isEmpty()?null:String.join("; ",feeSources),feeSummaries.isEmpty()?null:String.join("; ",feeSummaries));
        });return views;
    }
    private static View view(String key,String kind,String title,String currency,LocalDate date,List<Line> lines,List<PackageCost> packages,String fulfillment,ProfitShippingRates.PackageType type){
        boolean fba=fulfillment.toUpperCase(Locale.ROOT).contains("AMAZON")||Set.of("AFN","FBA").contains(fulfillment.toUpperCase(Locale.ROOT));
        String note=fba?"FBA fulfillment fees are imported estimates. Shipping to FBA requires verified transportation and placement charges allocated per shipped unit. Missing charges remain Pending; historical order fees are not settled actuals.":!"USD".equals(currency)?"Only USD estimates are supported.":
            "Estimated using current catalogue costs; excludes refunds, advertising and settlement adjustments. Order revenue excludes tax and promotions. Confirm shipping packages for multi-unit orders.";
        ProfitEstimate.Breakdown total=null;
        if(!fba&&"USD".equals(currency)&&!lines.isEmpty()&&lines.stream().allMatch(l->(l.unitPrice()==null||l.unitPrice().signum()>=0)&&l.customerShipping().signum()>=0))
            total=ProfitEstimate.calculate(lines.stream().map(l->new ProfitEstimate.Line(l.unitPrice(),l.quantity(),l.productCost(),l.otherCost(),l.customerShipping())).toList(),packages.stream().map(PackageCost::amount).toList());
        return new View(key,kind,title,currency,date,lines,fba?List.of():packages,total,note,type==null?null:type.name(),fba,null,null,null);
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
    public record ItemCost(UUID id,String name,BigDecimal quantity,BigDecimal cost,String currency,InvoiceCostEvidence.Invoice invoice){}
    public record Defaults(String sku,BigDecimal override,List<ItemCost> items){}
    public record SkuCostChange(String sku,BigDecimal amount){}
    public record ItemCostChange(UUID id,BigDecimal amount){}
    @Transactional(readOnly=true)
    public List<Defaults> defaults(UUID tenant,UUID connection,List<String> skus){
        scope(tenant);if(skus.isEmpty())return List.of();
        var args=Map.of("tenant",tenant,"connection",connection,"skus",skus);
        var parts=named.queryForList("""
            SELECT m.marketplace_sku,a.id,coalesce(a.display_name,g.canonical_name) name,part.quantity,
              offer.cost,offer.currency
            FROM marketplace_sku_mappings m
            CROSS JOIN LATERAL (
              SELECT c.account_catalog_item_id item,c.quantity FROM marketplace_sku_mapping_components c
                WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
              UNION ALL SELECT m.account_catalog_item_id,greatest(m.quantity_per_marketplace_unit,1)
                WHERE m.account_catalog_item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c
                  WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id)
            ) part
            JOIN account_catalog_items a ON a.tenant_id=m.tenant_id AND a.id=part.item
            JOIN global_catalog_products g ON g.id=a.global_product_id
            LEFT JOIN LATERAL (SELECT v.list_cost*(1-v.discount_rate/100) cost,v.currency
              FROM vendor_catalog_offers v WHERE v.tenant_id=a.tenant_id AND v.account_catalog_item_id=a.id
                AND v.effective_from<=current_date AND (v.effective_to IS NULL OR v.effective_to>=current_date)
              ORDER BY v.is_default DESC,v.effective_from DESC,v.id LIMIT 1) offer ON true
            WHERE m.tenant_id=:tenant AND m.marketplace_connection_id=:connection AND m.marketplace_sku IN (:skus) AND m.status='ACTIVE'
            ORDER BY m.marketplace_sku,a.id
            """,args);
        var invoices=InvoiceCostEvidence.latest(jdbc,tenant,parts.stream().map(p->(UUID)p.get("id")).distinct().toList());
        return named.query("SELECT seller_sku,product_cost_override FROM amazon_listings WHERE tenant_id=:tenant AND marketplace_connection_id=:connection AND seller_sku IN (:skus)",args,
            (rs,n)->{String sku=rs.getString(1);return new Defaults(sku,rs.getBigDecimal(2),parts.stream().filter(p->sku.equals(p.get("marketplace_sku")))
                .map(p->new ItemCost((UUID)p.get("id"),text(p,"name"),decimal(p,"quantity"),decimal(p,"cost"),text(p,"currency"),invoices.get((UUID)p.get("id")))).toList());});
    }
    @Transactional
    public void acceptInvoiceCost(UUID tenant,UUID connection,String sku,UUID itemId,UUID documentId,
            BigDecimal expectedCost,BigDecimal expectedInvoice,String actor){
        scope(tenant);
        if(sku==null||sku.length()>240||itemId==null||documentId==null||actor==null||actor.isBlank())
            throw new IllegalArgumentException("Choose an invoice cost to review.");
        // Same catalogue-write lock as the existing editor: compare and save atomically.
        jdbc.execute("SELECT pg_advisory_xact_lock(193641,1)");
        var item=defaults(tenant,connection,List.of(sku)).stream().flatMap(d->d.items().stream())
            .filter(i->i.id().equals(itemId)).findFirst().orElseThrow(()->new IllegalArgumentException("Item is not mapped in this store."));
        var invoice=item.invoice();
        if(invoice==null||!invoice.documentId().equals(documentId)||!sameCost(invoice.cost(),expectedInvoice)
                ||!sameCost(item.cost(),expectedCost))throw new IllegalArgumentException("Costs changed. Reload the panel and review them again.");
        if(item.currency()!=null&&!item.currency().equals(invoice.currency()))
            throw new IllegalArgumentException("Invoice and default currencies differ. Review this item in Account catalogue.");
        new com.nextaicommerce.platform.catalog.CatalogRepository(jdbc).updateDefaultFromInvoice(tenant,actor,itemId,
            invoice.vendorId(),invoice.cost(),invoice.currency(),"Accepted invoice "+invoice.number()+" / "+invoice.documentId());
    }
    private static boolean sameCost(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    /** One transaction for a calculator save; only explicitly edited fields are persisted. */
    @Transactional
    public void saveDefaults(UUID tenant,UUID connection,String kind,String key,List<SkuCostChange> skuChanges,
            List<ItemCostChange> itemChanges,List<PackageCost> packages,BigDecimal other,String packageType,String actor){
        scope(tenant);
        if(actor==null||actor.isBlank()||skuChanges==null||itemChanges==null||skuChanges.size()>100||itemChanges.size()>100)
            throw new IllegalArgumentException("Choose up to 100 costs to save.");
        var view="SKU".equals(kind)?skus(tenant,connection,List.of(key)).get(key):"ORDER".equals(kind)?orders(tenant,connection,List.of(key)).get(key):null;
        if(view==null||!"USD".equals(view.currency()))throw new IllegalArgumentException("Choose a USD listing or order in this store.");
        var allowed=view.lines().stream().map(Line::sku).distinct().toList();
        var items=defaults(tenant,connection,allowed).stream().flatMap(d->d.items().stream()).toList();
        for(var change:itemChanges){
            if(change==null||items.stream().noneMatch(i->i.id().equals(change.id())&&"USD".equals(i.currency())))
                throw new IllegalArgumentException("Choose a mapped USD catalogue item; set missing vendor pricing in Account catalogue.");
            new com.nextaicommerce.platform.catalog.CatalogRepository(jdbc).saveItemCost(tenant,actor,change.id(),change.amount(),null);
        }
        for(var change:skuChanges){
            if(change==null||!allowed.contains(change.sku())||change.amount()!=null&&(change.amount().signum()<0||change.amount().scale()>4||change.amount().compareTo(new BigDecimal("100000"))>0))
                throw new IllegalArgumentException("Enter a valid SKU cost, or use catalogue costs.");
            var found=jdbc.queryForList("SELECT product_cost_override FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=? FOR UPDATE",tenant,connection,change.sku());
            if(found.isEmpty())throw new IllegalArgumentException("SKU not found in this store.");
            jdbc.update("INSERT INTO sku_product_cost_history(tenant_id,marketplace_connection_id,seller_sku,previous_cost,new_cost,changed_by) VALUES (?,?,?,?,?,?)",tenant,connection,change.sku(),found.getFirst().get("product_cost_override"),change.amount(),actor);
            jdbc.update("UPDATE amazon_listings SET product_cost_override=? WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=?",change.amount(),tenant,connection,change.sku());
        }
        if("ORDER".equals(kind)){if(packages!=null)savePackages(tenant,connection,key,packages,actor);}
        else {
            var type=packageType==null||packageType.isBlank()?null:ProfitShippingRates.PackageType.valueOf(packageType);
            if(packages!=null)saveSku(tenant,connection,key,type,other,packages);
            else saveSku(tenant,connection,key,type,other);
        }
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
