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
 public record RefundOrder(String orderId,String sellerSku,String title,String imageUrl,String status,
   java.time.Instant postedDate,String currency,java.math.BigDecimal amount,Long units,long pendingAmounts){}
 public record RefundPage(List<RefundOrder> rows,boolean hasMore,java.time.Instant asOf){}
 @Transactional(readOnly=true)
 public RefundPage orders(UUID tenant,UUID connection,String sku,int days,int week,int page,java.time.Instant asOf){
  if((days!=28&&days!=30)||week < -1||week>3||(week>=0&&days!=28)||page<0||page>10000||sku.length()>250)
   throw new IllegalArgumentException("Invalid refund history filter");
  var anchor=asOf==null?java.time.Instant.now():asOf;
  if(anchor.isAfter(java.time.Instant.now().plusSeconds(60)))throw new IllegalArgumentException("Invalid history date");
  var from=anchor.minus(java.time.Duration.ofDays(week<0?days:28-week*7));
  var to=week<0?anchor:from.plus(java.time.Duration.ofDays(7));
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  var rows=jdbc.query("""
   WITH history AS (
    SELECT nullif(source.amazon_order_id,'') order_id,refund.seller_sku,refund.currency,
     max(refund.posted_date) posted_date,sum(refund.item_refund_amount) amount,
     CASE WHEN count(*) FILTER(WHERE refunded_units IS NULL)=0 THEN sum(refunded_units) END units,
     count(*) FILTER(WHERE item_refund_amount IS NULL) pending
    FROM amazon_sku_refunds refund JOIN amazon_financial_transactions source
     ON source.id=refund.transaction_id AND source.tenant_id=refund.tenant_id
      AND source.marketplace_connection_id=refund.marketplace_connection_id
    WHERE refund.tenant_id=? AND refund.marketplace_connection_id=?
     AND refund.posted_date>=? AND refund.posted_date<? AND (?='' OR refund.seller_sku=?)
    GROUP BY nullif(source.amazon_order_id,''),refund.seller_sku,refund.currency,
     CASE WHEN nullif(source.amazon_order_id,'') IS NULL THEN source.id END
   )
   SELECT history.*,listing.item_name,listing.image_url,orders.order_status
   FROM history LEFT JOIN amazon_orders orders ON orders.tenant_id=? AND orders.marketplace_connection_id=?
    AND orders.amazon_order_id=history.order_id
   LEFT JOIN LATERAL (SELECT item_name,image_url FROM amazon_listings WHERE tenant_id=?
    AND marketplace_connection_id=? AND seller_sku=history.seller_sku ORDER BY updated_at DESC LIMIT 1) listing ON true
   ORDER BY history.posted_date DESC,history.order_id NULLS LAST,history.seller_sku,history.currency
   LIMIT 51 OFFSET ?
   """,(rs,n)->new RefundOrder(rs.getString("order_id"),rs.getString("seller_sku"),rs.getString("item_name"),
    rs.getString("image_url"),rs.getString("order_status"),rs.getTimestamp("posted_date").toInstant(),rs.getString("currency"),
    rs.getBigDecimal("amount"),rs.getObject("units",Long.class),rs.getLong("pending")),
    tenant,connection,java.sql.Timestamp.from(from),java.sql.Timestamp.from(to),sku,sku,tenant,connection,tenant,connection,page*50);
  return new RefundPage(rows.stream().limit(50).toList(),rows.size()>50,anchor);
 }
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
