package com.nextaicommerce.platform.shopify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Local projections only. No network, queue, mapping or inventory writes while rendering Orders. */
@Repository
public class ShopifyOrderReadRepository {
    public static final Set<String> TABS=Set.of("ALL","PENDING","UNSHIPPED","SHIPPED","UNFULFILLED","PARTIALLY_FULFILLED","FULFILLED","CANCELLED","NEEDS_MAPPING","INVENTORY_SHORTAGE","NEEDS_REVIEW");
    public record Tab(String key,String label,long orders,long units){public Tab(String key,String label,long orders){this(key,label,orders,0);} public boolean readiness(){return Set.of("NEEDS_MAPPING","INVENTORY_SHORTAGE","NEEDS_REVIEW").contains(key);}}
    public record Part(UUID item,String code,BigDecimal quantity){}
    public record Line(UUID id,UUID variant,String revision,String sku,String title,String image,int quantity,int open,
                       BigDecimal total,String currency,String state,String scope,String review,List<Part> parts,BigDecimal available,String weekly,String adminUrl){
        public Line(UUID id,UUID variant,String revision,String sku,String title,String image,int quantity,int open,BigDecimal total,String currency,String state,String scope,String review,List<Part> parts,BigDecimal available,String weekly){this(id,variant,revision,sku,title,image,quantity,open,total,currency,state,scope,review,parts,available,weekly,null);}
        public boolean mapped(){return !parts.isEmpty();}
        public String stateLabel(){return switch(state){case "NEEDS_MAPPING"->"Needs mapping";case "INVENTORY_SHORTAGE"->"Inventory shortage";case "NEEDS_REVIEW"->"Needs review";case "READY_TO_SHIP"->"Reserved";case "REPORTING_ONLY"->"Historical";case "SHIPPED"->"Fulfilled";case "CLOSED"->"No open units";case "CANCELLED"->"Cancelled";default->state;};}
        public boolean actionRequired(){return Set.of("NEEDS_MAPPING","INVENTORY_SHORTAGE","NEEDS_REVIEW").contains(state);}
        public String mappedItems(){return parts.stream().map(p->Objects.toString(p.code(),"Item code unavailable")+" × "+p.quantity().stripTrailingZeros().toPlainString()).collect(java.util.stream.Collectors.joining(" | "));}
    }
    public record Order(UUID id,String gid,String name,Instant created,String payment,String fulfillment,String currency,
                        BigDecimal total,boolean complete,boolean cancelled,String review,String adminUrl,List<Line> lines,boolean marked){
        public Order(UUID id,String gid,String name,Instant created,String payment,String fulfillment,String currency,BigDecimal total,boolean complete,boolean cancelled,String review,String adminUrl,List<Line> lines){this(id,gid,name,created,payment,fulfillment,currency,total,complete,cancelled,review,adminUrl,lines,false);}
        public boolean paymentPending(){return Set.of("PENDING","AUTHORIZED","PARTIALLY_PAID").contains(payment);}
        public boolean sandboxShippingEligible(){return complete&&!cancelled&&!Set.of("VOIDED","REFUNDED","EXPIRED").contains(payment)&&!"FULFILLED".equals(fulfillment)&&lines.stream().anyMatch(l->l.open()>0);}
        public String statusLabel(){return cancelled?"Cancelled":marked?"Waiting for pickup":fulfillment.equals("FULFILLED")?"Shipped":fulfillment.equals("PARTIALLY_FULFILLED")?"Partially shipped":paymentPending()?"Pending":"Unshipped";}
        public String statusTone(){return switch(statusLabel()){case "Pending"->"pending";case "Unshipped","Partially shipped"->"unshipped";case "Shipped"->"shipped";case "Waiting for pickup"->"pickup";default->"issue";};}
        public String rowClass(){if(cancelled)return "state-cancelled";if(marked||"FULFILLED".equals(fulfillment))return "state-shipped";if(lines.stream().anyMatch(l->"INVENTORY_SHORTAGE".equals(l.state())))return "state-inventory_shortage";if(lines.stream().anyMatch(l->"NEEDS_MAPPING".equals(l.state())))return "state-needs_mapping";if(lines.stream().anyMatch(l->"READY_TO_SHIP".equals(l.state())))return "state-ready_to_ship";return "state-on_hold";}
        public boolean pickupEligible(){return marked || (complete&&!cancelled&&!paymentPending()&&review==null&&!lines.isEmpty()&&lines.stream().filter(l->l.open()>0).anyMatch(l->true)&&lines.stream().filter(l->l.open()>0).allMatch(l->"LIVE".equals(l.scope())&&"READY_TO_SHIP".equals(l.state())&&l.review()==null));}
        public String subjectKey(){return "SHOPIFY_ORDER:"+id;}
    }
    public record Page(List<Order> rows,long total,int page,int size){
        public int totalPages(){return Math.max(1,(int)Math.ceil(total/(double)size));}public int displayPage(){return page+1;}
        public boolean hasPrevious(){return page>0;}public boolean hasNext(){return page+1<totalPages();}
    }
    private final JdbcTemplate jdbc;private final ShopifyOrderInventory inventory;
    public ShopifyOrderReadRepository(JdbcTemplate jdbc,ShopifyOrderInventory inventory){this.jdbc=jdbc;this.inventory=inventory;}
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private static String predicate(String status){return switch(status){
        case "ALL"->"true";case "PENDING"->"o.cancelled_at IS NULL AND NOT o.platform_waiting_for_pickup AND o.payment_status IN ('PENDING','AUTHORIZED','PARTIALLY_PAID') AND o.fulfillment_status<>'FULFILLED'";case "UNSHIPPED"->"o.cancelled_at IS NULL AND NOT o.platform_waiting_for_pickup AND o.fulfillment_status IN ('UNFULFILLED','PARTIALLY_FULFILLED')";case "SHIPPED"->"o.cancelled_at IS NULL AND (o.platform_waiting_for_pickup OR o.fulfillment_status='FULFILLED')";case "CANCELLED"->"o.cancelled_at IS NOT NULL";
        case "NEEDS_REVIEW"->"(NOT o.evidence_complete OR o.review_reason IS NOT NULL OR EXISTS(SELECT 1 FROM shopify_order_lines alert WHERE alert.tenant_id=o.tenant_id AND alert.marketplace_connection_id=o.marketplace_connection_id AND alert.order_id=o.id AND alert.inventory_state='NEEDS_REVIEW'))";
        case "NEEDS_MAPPING","INVENTORY_SHORTAGE"->"EXISTS(SELECT 1 FROM shopify_order_lines alert WHERE alert.tenant_id=o.tenant_id AND alert.marketplace_connection_id=o.marketplace_connection_id AND alert.order_id=o.id AND alert.inventory_state='"+status+"')";
        case "UNFULFILLED","PARTIALLY_FULFILLED","FULFILLED"->"o.cancelled_at IS NULL AND o.fulfillment_status='"+status+"'";
        default->throw new IllegalArgumentException("Unsupported Shopify order view.");
    };}
    @Transactional(readOnly=true)
    public List<Tab> tabs(UUID tenant,UUID connection){scope(tenant);var result=new ArrayList<Tab>();
        for(String key:List.of("ALL","UNSHIPPED","PENDING","SHIPPED","CANCELLED","NEEDS_MAPPING","INVENTORY_SHORTAGE","NEEDS_REVIEW")){
            Long count=jdbc.queryForObject("SELECT count(*) FROM shopify_orders o WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND "+predicate(key),Long.class,tenant,connection);
            Long units=jdbc.queryForObject("SELECT coalesce(sum(l.ordered_quantity),0) FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND "+predicate(key),Long.class,tenant,connection);
            result.add(new Tab(key,switch(key){case "ALL"->"All";case "PENDING"->"Pending";case "UNSHIPPED"->"Unshipped";case "SHIPPED"->"Shipped";case "UNFULFILLED"->"Unfulfilled";case "PARTIALLY_FULFILLED"->"Partially fulfilled";case "FULFILLED"->"Fulfilled";case "CANCELLED"->"Cancelled";case "NEEDS_MAPPING"->"Unmapped";case "INVENTORY_SHORTAGE"->"Inventory shortage";default->"Needs review";},count==null?0:count,units==null?0:units));
        }return List.copyOf(result);
    }
    @Transactional(readOnly=true)
    public Page page(UUID tenant,UUID connection,String status,String query,int requested,int size){
        scope(tenant);if(!TABS.contains(status)||query.length()>250||size<1||size>250)throw new IllegalArgumentException("Unsupported Shopify order search.");
        String shop=jdbc.queryForObject("SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='SHOPIFY'",String.class,tenant,connection);
        shop=com.nextaicommerce.platform.web.ShopifyConnectionService.shopDomain(shop);
        String filter="""
            FROM shopify_orders o WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND (
            """+predicate(status)+"""
            ) AND (?='' OR o.order_name ILIKE ? ESCAPE '!' OR EXISTS(
                SELECT 1 FROM shopify_order_lines l LEFT JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
                LEFT JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
                WHERE l.tenant_id=o.tenant_id AND l.marketplace_connection_id=o.marketplace_connection_id AND l.order_id=o.id
                  AND (concat_ws(' ',l.seller_sku,l.title,p.vendor) ILIKE ? ESCAPE '!' OR EXISTS(
                    SELECT 1 FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components part ON part.tenant_id=m.tenant_id AND part.marketplace_sku_mapping_id=m.id
                    JOIN account_catalog_items item ON item.tenant_id=part.tenant_id AND item.id=part.account_catalog_item_id
                    LEFT JOIN vendor_catalog_offers offer ON offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id AND offer.effective_to IS NULL
                    WHERE m.tenant_id=l.tenant_id AND m.marketplace_connection_id=l.marketplace_connection_id AND m.shopify_variant_id=l.variant_id AND m.status='ACTIVE'
                      AND concat_ws(' ',item.account_sku,offer.vendor_item_code) ILIKE ? ESCAPE '!'))))
            """;
        String pattern="%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        Object[] args={tenant,connection,query,pattern,pattern,pattern};Long total=jdbc.queryForObject("SELECT count(*) "+filter,Long.class,args);
        long count=total==null?0:total;int page=(int)Math.min(Math.max(0,requested),Math.max(0,(count-1)/size));
        var values=new ArrayList<Object>(Arrays.asList(args));values.add(size);values.add((long)page*size);
        var orders=jdbc.queryForList("SELECT o.* "+filter+" ORDER BY o.source_created_at DESC,o.id LIMIT ? OFFSET ?",values.toArray());
        if(orders.isEmpty())return new Page(List.of(),count,page,size);
        var ids=orders.stream().map(o->(UUID)o.get("id")).toList();String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var lineArgs=new ArrayList<Object>(List.of(tenant,connection));lineArgs.addAll(ids);
        var lines=jdbc.queryForList("SELECT l.*,v.observation_hash variant_revision,v.variant_gid admin_variant,p.product_gid admin_product FROM shopify_order_lines l LEFT JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id LEFT JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.order_id IN ("+placeholders+") ORDER BY l.line_gid",lineArgs.toArray());
        var parts=parts(tenant,connection,ids);var available=inventory.availability(tenant,parts.values().stream().flatMap(Collection::stream).map(Part::item).distinct().toList());
        var weekly=weekly(tenant,connection,lines.stream().map(l->(UUID)l.get("variant_id")).filter(Objects::nonNull).distinct().toList());
        var byOrder=new HashMap<UUID,List<Line>>();var headers=new HashMap<UUID,Map<String,Object>>();orders.forEach(o->headers.put((UUID)o.get("id"),o));
        for(var l:lines){
            UUID id=(UUID)l.get("id");var components=parts.getOrDefault(id,List.of());BigDecimal units=null;
            for(var part:components){BigDecimal n=available.getOrDefault(part.item(),BigDecimal.ZERO).divideToIntegralValue(part.quantity());units=units==null?n:units.min(n);}
            var header=headers.get((UUID)l.get("order_id"));boolean complete=Boolean.TRUE.equals(header.get("evidence_complete"));
            var row=new Line(id,(UUID)l.get("variant_id"),(String)l.get("variant_revision"),(String)l.get("seller_sku"),(String)l.get("title"),(String)l.get("image_url"),
                (Integer)l.get("ordered_quantity"),(Integer)l.get("unfulfilled_quantity"),(BigDecimal)l.get("line_total"),(String)l.get("currency"),complete?(String)l.get("inventory_state"):"NEEDS_REVIEW",(String)l.get("stock_scope"),complete?(String)l.get("review_reason"):(String)header.get("review_reason"),components,units,weekly.get((UUID)l.get("variant_id")),l.get("admin_product")==null?null:"https://"+shop+"/admin/products/"+l.get("admin_product").toString().replace("gid://shopify/Product/","")+"/variants/"+l.get("admin_variant").toString().replace("gid://shopify/ProductVariant/",""));
            byOrder.computeIfAbsent((UUID)l.get("order_id"),k->new ArrayList<>()).add(row);
        }
        var result=new ArrayList<Order>();
        for(var o:orders){String gid=(String)o.get("order_gid");UUID id=(UUID)o.get("id");result.add(new Order(id,gid,(String)o.get("order_name"),((java.sql.Timestamp)o.get("source_created_at")).toInstant(),(String)o.get("payment_status"),(String)o.get("fulfillment_status"),(String)o.get("currency"),(BigDecimal)o.get("total"),(Boolean)o.get("evidence_complete"),o.get("cancelled_at")!=null,(String)o.get("review_reason"),"https://"+shop+"/admin/orders/"+gid.substring(gid.lastIndexOf('/')+1),List.copyOf(byOrder.getOrDefault(id,List.of())),Boolean.TRUE.equals(o.get("platform_waiting_for_pickup"))));}
        return new Page(List.copyOf(result),count,page,size);
    }
    private Map<UUID,List<Part>> parts(UUID tenant,UUID connection,List<UUID> orders){
        var args=new ArrayList<Object>(List.of(tenant,connection));args.addAll(orders);var result=new HashMap<UUID,List<Part>>();
        jdbc.query("""
            SELECT l.id,c.account_catalog_item_id,coalesce(offer.vendor_item_code,item.account_sku),c.quantity FROM shopify_order_lines l
            JOIN LATERAL(
              SELECT account_catalog_item_id,quantity FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id
              UNION ALL
              SELECT part.account_catalog_item_id,part.quantity FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components part ON part.tenant_id=m.tenant_id AND part.marketplace_sku_mapping_id=m.id
              JOIN shopify_variants v ON v.tenant_id=m.tenant_id AND v.marketplace_connection_id=m.marketplace_connection_id AND v.id=m.shopify_variant_id AND v.mapping_hash=m.shopify_mapping_hash
              WHERE m.tenant_id=l.tenant_id AND m.marketplace_connection_id=l.marketplace_connection_id AND m.shopify_variant_id=l.variant_id AND m.status='ACTIVE'
                AND NOT EXISTS(SELECT 1 FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id)
            ) c ON true JOIN account_catalog_items item ON item.tenant_id=l.tenant_id AND item.id=c.account_catalog_item_id
            LEFT JOIN LATERAL(SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=item.tenant_id AND account_catalog_item_id=item.id AND effective_to IS NULL ORDER BY is_default DESC,updated_at DESC,id LIMIT 1) offer ON true
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.order_id IN (
            """+String.join(",",Collections.nCopies(orders.size(),"?"))+") ORDER BY l.id,c.account_catalog_item_id",rs->{result.computeIfAbsent(rs.getObject(1,UUID.class),k->new ArrayList<>()).add(new Part(rs.getObject(2,UUID.class),rs.getString(3),rs.getBigDecimal(4)));},args.toArray());return result;
    }
    @Transactional(readOnly=true)
    public List<com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.MappingComponentView> frozenComponents(UUID tenant,UUID connection,UUID variant,UUID line){
        scope(tenant);if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND variant_id=?)",Boolean.class,tenant,connection,line,variant)))throw new IllegalArgumentException("This line is not available in the selected store and variant.");
        return jdbc.query("SELECT i.id,coalesce(i.display_name,g.canonical_name),i.account_sku,c.quantity,g.brand FROM shopify_order_components c JOIN account_catalog_items i ON i.tenant_id=c.tenant_id AND i.id=c.account_catalog_item_id JOIN global_catalog_products g ON g.id=i.global_product_id WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.order_line_id=? ORDER BY i.id",(rs,n)->new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.MappingComponentView(rs.getObject(1,UUID.class),rs.getString(2),null,rs.getString(3),rs.getBigDecimal(4),rs.getString(5)),tenant,connection,line);
    }
    @Transactional(readOnly=true)
    public String localVersion(UUID tenant,UUID connection){scope(tenant);return jdbc.queryForObject("SELECT coalesce(max(updated_at)::text,'') FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=?",String.class,tenant,connection);}
    @Transactional(readOnly=true)
    public Map<UUID,String> weekly(UUID tenant,UUID connection,List<UUID> variants){
        scope(tenant);if(variants.isEmpty())return Map.of();
        Boolean complete=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shopify_order_controls c WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.baseline_completed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM shopify_orders o WHERE o.tenant_id=c.tenant_id AND o.marketplace_connection_id=c.marketplace_connection_id AND NOT o.evidence_complete AND o.source_created_at>=now()-interval '28 days'))",Boolean.class,tenant,connection);
        if(!Boolean.TRUE.equals(complete))return Map.of();
        var args=new ArrayList<Object>(List.of(tenant,connection));args.addAll(variants);var buckets=new HashMap<UUID,long[]>();for(UUID v:variants)buckets.put(v,new long[4]);
        jdbc.query("SELECT l.variant_id,floor(extract(epoch FROM (now()-o.source_created_at))/604800)::int bucket,sum(l.current_quantity) FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND o.cancelled_at IS NULL AND NOT o.is_test AND o.evidence_complete AND o.source_created_at>=now()-interval '28 days' AND o.source_created_at<=now() AND l.variant_id IN ("+String.join(",",Collections.nCopies(variants.size(),"?"))+") GROUP BY l.variant_id,bucket",rs->{int bucket=rs.getInt(2);if(bucket>=0&&bucket<4)buckets.get(rs.getObject(1,UUID.class))[3-bucket]=rs.getLong(3);},args.toArray());
        var result=new HashMap<UUID,String>();buckets.forEach((v,a)->result.put(v,a[0]+" | "+a[1]+" | "+a[2]+" | "+a[3]));return result;
    }
}
