package com.nextaicommerce.platform.marketplace;
import com.nextaicommerce.platform.orders.OrderSmartFilters;
import java.math.BigDecimal;
import java.util.*;

/** Whitelisted SKU predicates applied before server pagination. */
public final class MarketplaceSkuFilters {
    private MarketplaceSkuFilters(){}
    public record Query(String sql,List<Object> args){}
    private static final Map<String,String> TEXT=Map.of("sku","seller_sku","asin","asin","product","item_name","itemCode","mapping_codes","orderStatus","operational_status");
    private static final Map<String,String> NUMBER=Map.of("available","available","sales","price","shipping","average_shipping","buyBox","buy_box_price","soldUnits","(week_4+week_3+week_2+current_week)","soldOrders","sold_orders","fees","estimated_fee_total");
    public static Query parse(Map<String,String> input){
        List<String> sql=new ArrayList<>();List<Object> args=new ArrayList<>();
        TEXT.forEach((key,column)->{
            String value=input.getOrDefault("f_"+key,"").trim();if(value.isEmpty())return;
            if(value.length()>250)throw new IllegalArgumentException("Filter is too long");
            String op=input.getOrDefault("f_"+key+"_op","contains");
            if(!List.of("contains","equals","starts").contains(op))throw new IllegalArgumentException("Invalid text matching");
            if(op.equals("equals")){sql.add("lower("+column+")=lower(?)");args.add(value);}
            else{sql.add("lower("+column+") LIKE lower(?) ESCAPE '!'");args.add((op.equals("starts")?"":"%")+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        });
        NUMBER.forEach((key,column)->{
            BigDecimal min=number(input.get("f_"+key+"_min")),max=number(input.get("f_"+key+"_max"));
            if(min!=null&&max!=null&&min.compareTo(max)>0)throw new IllegalArgumentException("Minimum exceeds maximum");
            if(min!=null){sql.add(column+">=?");args.add(min);}if(max!=null){sql.add(column+"<=?");args.add(max);}
        });
        String channel=input.getOrDefault("f_channel","");
        if(!List.of("","FBA","FBM").contains(channel))throw new IllegalArgumentException("Invalid fulfillment channel");
        if(!channel.isEmpty())sql.add("upper(coalesce(fulfillment_channel,'')) "+(channel.equals("FBA")?"LIKE":"NOT LIKE")+" '%AMAZON%'");
        Map<String,String> dates=new HashMap<>();input.forEach((k,v)->{if(k.startsWith("f_date_"))dates.put(k,v);});
        var dateQuery=OrderSmartFilters.parse(dates);
        if(!dateQuery.sql().equals("true")){sql.add(dateQuery.sql().replace("orders.purchase_date","last_seen_at"));args.addAll(dateQuery.args());}
        return new Query(sql.isEmpty()?"true":String.join(" AND ",sql),args);
    }
    private static BigDecimal number(String value){
        if(value==null||value.isBlank())return null;
        try{BigDecimal n=new BigDecimal(value);if(n.signum()<0||n.precision()>18||n.scale()>4)throw new NumberFormatException();return n;}
        catch(NumberFormatException ex){throw new IllegalArgumentException("Enter a valid nonnegative number");}
    }
    public static Map<String,String> clean(Map<String,String> input){
        if(input==null||input.size()>60)throw new IllegalArgumentException("Too many filters");
        Set<String> allowed=new HashSet<>(List.of("q","status","f_channel","f_date_min","f_date_max","f_date_preset","f_date_start_offset","f_date_end_offset"));
        TEXT.keySet().forEach(k->{allowed.add("f_"+k);allowed.add("f_"+k+"_op");});
        NUMBER.keySet().forEach(k->{allowed.add("f_"+k+"_min");allowed.add("f_"+k+"_max");});
        Map<String,String> result=new TreeMap<>();
        input.forEach((k,v)->{if(k.startsWith("f_")&&!allowed.contains(k))throw new IllegalArgumentException("Unknown filter");if(allowed.contains(k)){if(v==null||v.length()>250)throw new IllegalArgumentException("Filter is too long");if(!v.isBlank())result.put(k,v.trim());}});
        parse(result);return result;
    }
}
