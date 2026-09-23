package com.nextaicommerce.platform.orders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** User values are parameters; column expressions and operators are server-owned. */
public final class OrderSmartFilters {
    public record Query(String sql,List<Object> args,String sort){}
    public static final String AVAILABLE="""
      (SELECT min(floor(greatest(coalesce((SELECT sum(ledger.quantity) FROM inventory_ledger_entries ledger
        WHERE ledger.tenant_id=c.tenant_id AND ledger.account_catalog_item_id=c.account_catalog_item_id
        AND (ledger.expiration_date IS NULL OR ledger.expiration_date>current_date+coalesce(
          (SELECT minimum_sellable_days FROM inventory_shelf_life_policies WHERE tenant_id=c.tenant_id),10))),0)
        -coalesce((SELECT sum(r.quantity) FROM order_inventory_reservations r WHERE r.tenant_id=c.tenant_id
          AND r.account_catalog_item_id=c.account_catalog_item_id AND r.status='ACTIVE'),0),0)/nullif(c.quantity,0)))
       FROM marketplace_sku_mapping_components c WHERE c.tenant_id=mapping.tenant_id AND c.marketplace_sku_mapping_id=mapping.id)
      """;
    public static String metric(boolean orders){return """
      (SELECT %s FROM amazon_order_items sold JOIN amazon_orders sale ON sale.tenant_id=sold.tenant_id
        AND sale.marketplace_connection_id=sold.marketplace_connection_id AND sale.amazon_order_id=sold.amazon_order_id
       WHERE sold.tenant_id=item.tenant_id AND sold.marketplace_connection_id=item.marketplace_connection_id
        AND sold.seller_sku=item.seller_sku AND sale.purchase_date>=now()-interval '28 days' AND sale.purchase_date<=now()
        AND upper(coalesce(sale.order_status,'')) NOT IN ('CANCELLED','CANCELED'))
      """.formatted(orders?"count(DISTINCT sale.id)":"coalesce(sum(sold.quantity_ordered),0)");}
    public static final String FROM="""
      FROM amazon_orders orders JOIN amazon_order_items item ON item.tenant_id=orders.tenant_id
        AND item.marketplace_connection_id=orders.marketplace_connection_id AND item.amazon_order_id=orders.amazon_order_id
      LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
        AND mapping.marketplace_connection_id=item.marketplace_connection_id AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
      LEFT JOIN amazon_listings listing ON listing.tenant_id=item.tenant_id AND listing.marketplace_connection_id=item.marketplace_connection_id
        AND listing.marketplace_id=orders.marketplace_id AND listing.seller_sku=item.seller_sku
      """;
    private static final Map<String,String> TEXT=Map.of("orderId","orders.amazon_order_id","sku","item.seller_sku","asin","coalesce(item.asin,listing.asin)","product","item.title","orderStatus","orders.order_status");
    private static final Map<String,String> NUMBER=Map.of("available","coalesce("+AVAILABLE+",0)","quantity","item.quantity_ordered","sales","item.item_price","shipping","greatest(coalesce(item.shipping_price,0)-coalesce(item.shipping_discount,0),0)","buyBox","listing.buy_box_price","soldUnits",metric(false),"soldOrders",metric(true));
    public static Query parse(Map<String,String> input){
        return parse(input,java.time.ZoneOffset.UTC,java.time.Clock.systemUTC());
    }
    public static Query parse(Map<String,String> input,java.time.ZoneId zone,java.time.Clock clock){
        var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        TEXT.forEach((key,column)->text(input,key,column,clauses,args));
        String code=value(input,"f_itemCode");if(!code.isEmpty()){
            var nested=new ArrayList<String>();text(input,"itemCode","coalesce(nullif(offer.vendor_item_code,''),nullif(catalog.account_sku,''),catalog.display_name,product.canonical_name)",nested,args);
            clauses.add("EXISTS (SELECT 1 FROM marketplace_sku_mapping_components c JOIN account_catalog_items catalog ON catalog.tenant_id=c.tenant_id AND catalog.id=c.account_catalog_item_id JOIN global_catalog_products product ON product.id=catalog.global_product_id LEFT JOIN LATERAL (SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=catalog.tenant_id AND account_catalog_item_id=catalog.id ORDER BY (effective_to IS NULL) DESC,is_default DESC,updated_at DESC LIMIT 1) offer ON true WHERE c.tenant_id=mapping.tenant_id AND c.marketplace_sku_mapping_id=mapping.id AND "+nested.getFirst()+")");
        }
        NUMBER.forEach((key,column)->{
            BigDecimal min=number(input,"f_"+key+"_min"),max=number(input,"f_"+key+"_max");
            if(min!=null&&max!=null&&min.compareTo(max)>0)throw new IllegalArgumentException("Minimum must not exceed maximum for "+key);
            if(min!=null){clauses.add(column+">=?");args.add(min);}if(max!=null){clauses.add(column+"<=?");args.add(max);}
        });
        String channel=value(input,"f_channel");
        if(!List.of("","FBA","FBM").contains(channel))throw new IllegalArgumentException("Invalid fulfillment channel");
        if(!channel.isEmpty()){clauses.add("upper(orders.fulfillment_channel) IN (?,?)");args.add(channel);args.add(channel.equals("FBA")?"AFN":"MFN");}
        String preset=value(input,"f_date_preset");
        OffsetDateTime from,to;
        if(!preset.isEmpty()){
            var today=java.time.LocalDate.now(clock.withZone(zone));var end=today.plusDays(1);var start=today;
            switch(preset){
                case "today" -> {}
                case "yesterday" -> {start=today.minusDays(1);end=today;}
                case "last_week" -> {end=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));start=end.minusWeeks(1);}
                case "last_month" -> {end=today.withDayOfMonth(1);start=end.minusMonths(1);}
                case "last_year" -> {end=today.withDayOfYear(1);start=end.minusYears(1);}
                default -> throw new IllegalArgumentException("Invalid smart date");
            }
            start=start.plusDays(dayOffset(input,"f_date_start_offset"));end=end.plusDays(dayOffset(input,"f_date_end_offset"));
            if(!start.isBefore(end))throw new IllegalArgumentException("Smart date interval must include at least one day");
            from=start.atStartOfDay(zone).toOffsetDateTime();to=end.atStartOfDay(zone).toOffsetDateTime();
        }else{from=date(input,"f_date_min");to=date(input,"f_date_max");}
        if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException("Start date must precede end date");
        if(from!=null){clauses.add("orders.purchase_date>=?");args.add(from);}if(to!=null){clauses.add("orders.purchase_date"+(preset.isEmpty()?"<=?":"<?"));args.add(to);}
        String sort=value(input,"smartSort");if(!List.of("","units_desc","units_asc","orders_desc","orders_asc").contains(sort))throw new IllegalArgumentException("Invalid sort");
        return new Query(clauses.isEmpty()?"true":String.join(" AND ",clauses),args,sort);
    }
    public static Map<String,String> clean(Map<String,String> input){
        if(input==null||input.size()>60)throw new IllegalArgumentException("Too many filters");
        var allowed=new HashSet<String>(List.of("smartSort","q","status","f_itemCode","f_itemCode_op","f_date_min","f_date_max","f_date_preset","f_date_start_offset","f_date_end_offset","f_channel"));
        TEXT.keySet().forEach(k->{allowed.add("f_"+k);allowed.add("f_"+k+"_op");});NUMBER.keySet().forEach(k->{allowed.add("f_"+k+"_min");allowed.add("f_"+k+"_max");});
        var result=new TreeMap<String,String>();input.forEach((k,v)->{if(k.startsWith("f_")&&!allowed.contains(k))throw new IllegalArgumentException("Unknown filter");if(allowed.contains(k)){if(v==null||v.length()>250)throw new IllegalArgumentException("Filter is too long");if(!v.isBlank())result.put(k,v.trim());}});parse(result);return result;
    }
    private static String value(Map<String,String> m,String key){String s=m.getOrDefault(key,"").trim();if(s.length()>250)throw new IllegalArgumentException("Filter is too long");return s;}
    private static int dayOffset(Map<String,String> m,String key){String s=value(m,key);if(s.isEmpty())return 0;try{int n=Integer.parseInt(s);if(Math.abs((long)n)>3660)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Day offsets must be whole numbers between -3660 and 3660");}}
    private static BigDecimal number(Map<String,String> m,String key){String s=value(m,key);if(s.isEmpty())return null;try{var n=new BigDecimal(s);if(n.signum()<0||n.precision()>18||n.scale()>4)throw new IllegalArgumentException();return n;}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Enter a valid nonnegative number for "+key);}}
    private static OffsetDateTime date(Map<String,String> m,String key){String s=value(m,key);if(s.isEmpty())return null;try{return OffsetDateTime.parse(s);}catch(RuntimeException ex){throw new IllegalArgumentException("Enter a valid date and time");}}
    private static void text(Map<String,String> m,String key,String column,List<String> clauses,List<Object> args){String v=value(m,"f_"+key);if(v.isEmpty())return;String op=value(m,"f_"+key+"_op");if(!List.of("","contains","equals","starts").contains(op))throw new IllegalArgumentException("Invalid text match");if(op.equals("equals")){clauses.add("lower("+column+")=lower(?)");args.add(v);}else{clauses.add("lower("+column+") LIKE lower(?) ESCAPE '!' ");String escaped=v.replace("!","!!").replace("%","!%").replace("_","!_");args.add((op.equals("starts")?"":"%")+escaped+"%");}}
}
