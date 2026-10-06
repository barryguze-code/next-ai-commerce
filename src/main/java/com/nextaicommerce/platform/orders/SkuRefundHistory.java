package com.nextaicommerce.platform.orders;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** One indexed, store-scoped query for all visible SKUs, independent of order dates. */
@Repository
public class SkuRefundHistory {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 public SkuRefundHistory(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public record RefundTotal(String currency,java.math.BigDecimal amount,long orders,long pendingAmounts,long unknownOrders){}
 @Transactional(readOnly=true)
 public List<RefundTotal> thirtyDays(UUID tenant,UUID connection){
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  return jdbc.query("""
   SELECT refund.currency,sum(refund.item_refund_amount),count(DISTINCT nullif(source.amazon_order_id,'')),
     count(*) FILTER(WHERE refund.item_refund_amount IS NULL),count(*) FILTER(WHERE nullif(source.amazon_order_id,'') IS NULL)
   FROM amazon_sku_refunds refund JOIN amazon_financial_transactions source
     ON source.id=refund.transaction_id AND source.tenant_id=refund.tenant_id AND source.marketplace_connection_id=refund.marketplace_connection_id
   WHERE refund.tenant_id=? AND refund.marketplace_connection_id=? AND refund.posted_date>=now()-interval '30 days' AND refund.posted_date<=now()
   GROUP BY refund.currency ORDER BY refund.currency
   """,(rs,row)->new RefundTotal(rs.getString(1),rs.getBigDecimal(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)),tenant,connection);
 }
 @Transactional(readOnly=true)
 public Map<String,String> fourWeeks(UUID tenant,UUID connection,Collection<String> requested){
  var skus=requested.stream().filter(Objects::nonNull).distinct().toList();
  if(skus.isEmpty())return Map.of();
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  var args=new ArrayList<Object>(List.of(tenant,connection));args.addAll(skus);
  var result=new LinkedHashMap<String,List<Map<String,Object>>>();
  jdbc.query("""
   WITH scoped AS (
    SELECT refund.*,nullif(source.amazon_order_id,'') amazon_order_id
    FROM amazon_sku_refunds refund JOIN amazon_financial_transactions source
      ON source.id=refund.transaction_id AND source.tenant_id=refund.tenant_id
      AND source.marketplace_connection_id=refund.marketplace_connection_id
    WHERE refund.tenant_id=? AND refund.marketplace_connection_id=?
      AND refund.posted_date>=now()-interval '28 days' AND refund.posted_date<=now()
      AND refund.seller_sku IN (
   """+String.join(",",Collections.nCopies(skus.size(),"?"))+"""
    )
   ), totals AS (
    SELECT seller_sku,count(DISTINCT amazon_order_id) total_orders,
      count(*) FILTER(WHERE amazon_order_id IS NULL) unknown_orders FROM scoped GROUP BY seller_sku
   )
   SELECT scoped.seller_sku,least(3,floor(extract(epoch FROM (posted_date-(now()-interval '28 days')))/604800)::int) week,
     currency,sum(item_refund_amount) amount,sum(refunded_units) units,
     count(*) FILTER(WHERE refunded_units IS NULL) unknown_units,
     count(*) FILTER(WHERE item_refund_amount IS NULL) unknown_amounts,
     count(DISTINCT amazon_order_id) orders,max(total_orders) total_orders,max(unknown_orders) unknown_orders
   FROM scoped JOIN totals USING(seller_sku) GROUP BY scoped.seller_sku,week,currency
   """,rs->{
    var item=new LinkedHashMap<String,Object>();item.put("week",rs.getInt("week"));item.put("currency",rs.getString("currency"));
    item.put("amount",rs.getBigDecimal("amount"));item.put("units",rs.getObject("units"));
    item.put("unknownUnits",rs.getInt("unknown_units"));item.put("unknownAmounts",rs.getInt("unknown_amounts"));
    item.put("orders",rs.getLong("orders"));item.put("totalOrders",rs.getLong("total_orders"));item.put("unknownOrders",rs.getLong("unknown_orders"));
    result.computeIfAbsent(rs.getString("seller_sku"),key->new ArrayList<>()).add(item);
   },args.toArray());
  var encoded=new LinkedHashMap<String,String>();result.forEach((sku,history)->encoded.put(sku,json.writeValueAsString(history)));return encoded;
 }
}
