package com.nextaicommerce.platform.fba;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only fulfillment evidence, loaded once by the durable forecast worker, never by a row/dialog. */
final class FbaFulfilledDemand {
 record Line(String sku,Instant purchased,Integer units,boolean promotionKnown,boolean promotion){}
 record Evidence(Instant asOf,List<Line> lines){
  Map<String,FbaReplenishmentMath.Demand> sales(){var result=new HashMap<String,FbaReplenishmentMath.Demand>();var grouped=new HashMap<String,List<Line>>();lines.stream().filter(l->!l.purchased().isBefore(asOf.minus(Duration.ofDays(90)))).forEach(l->grouped.computeIfAbsent(l.sku(),k->new ArrayList<>()).add(l));grouped.forEach((sku,rows)->{double u30=rows.stream().filter(l->l.units()!=null&&!l.purchased().isBefore(asOf.minus(Duration.ofDays(30)))).mapToInt(Line::units).sum(),u90=rows.stream().filter(l->l.units()!=null).mapToInt(Line::units).sum();int history=(int)Math.min(90,Math.max(0,Duration.between(rows.stream().map(Line::purchased).min(Comparator.naturalOrder()).orElse(asOf),asOf).toDays()));result.put(sku,new FbaReplenishmentMath.Demand(u30,u90,history,null,null));});return result;}
  Set<String> incomplete(){var result=new HashSet<String>();lines.stream().filter(l->l.units()==null&&!l.purchased().isBefore(asOf.minus(Duration.ofDays(90)))).forEach(l->result.add(l.sku()));return result;}
  Map<String,List<FbaReplenishmentForecasts.HistoricalDay>> historical(ZoneId zone){record Key(String sku,LocalDate date){}var grouped=new HashMap<Key,List<Line>>();lines.forEach(l->grouped.computeIfAbsent(new Key(l.sku(),l.purchased().atZone(zone).toLocalDate()),k->new ArrayList<>()).add(l));var result=new HashMap<String,List<FbaReplenishmentForecasts.HistoricalDay>>();grouped.forEach((key,rows)->{if(rows.stream().anyMatch(l->l.units()==null))return;result.computeIfAbsent(key.sku(),k->new ArrayList<>()).add(new FbaReplenishmentForecasts.HistoricalDay(key.date(),rows.stream().mapToInt(Line::units).sum(),rows.stream().allMatch(Line::promotionKnown),rows.stream().anyMatch(Line::promotion)));});return result;}
 }
 private final JdbcTemplate jdbc;
 FbaFulfilledDemand(JdbcTemplate jdbc){this.jdbc=jdbc;}
 // Physical identity is deduplicated before matching normalized order lines. Missing ASIN is
 // normal in the fulfillment report; a supplied, conflicting ASIN is not accepted.
 static final String SQL="""
 WITH cohort AS MATERIALIZED (
  SELECT i.id,i.amazon_order_id,i.amazon_order_item_id,i.raw_payload->>'apiOrderItemId' api_item,
   i.seller_sku,i.asin,i.quantity_ordered,i.quantity_shipped,i.promotion_discount,o.purchase_date,
   regexp_replace(upper(coalesce(o.order_status,'')),'[^A-Z]','','g') status
  FROM amazon_order_items i JOIN amazon_orders o ON o.tenant_id=i.tenant_id
   AND o.marketplace_connection_id=i.marketplace_connection_id AND o.amazon_order_id=i.amazon_order_id
  WHERE i.tenant_id=? AND i.marketplace_connection_id=? AND o.purchase_date BETWEEN ? AND ?
   AND upper(coalesce(o.fulfillment_channel,'')) IN ('AFN','AMAZON','FBA')
   AND regexp_replace(upper(coalesce(o.order_status,'')),'[^A-Z]','','g') NOT IN ('CANCELED','CANCELLED','PENDING','PENDINGAVAILABILITY')
   AND regexp_replace(upper(coalesce(nullif(trim(i.raw_payload->>'item-status'),''),nullif(trim(i.raw_payload->>'ItemStatus'),''),nullif(trim(i.raw_payload->>'item_status'),''),'')),'[^A-Z]','','g') NOT IN ('CANCELED','CANCELLED','PENDING','PENDINGAVAILABILITY','UNSHIPPED')
 ), candidates AS MATERIALIZED (
  SELECT s.* FROM amazon_customer_shipments s JOIN amazon_source_documents d ON d.tenant_id=s.tenant_id
   AND d.marketplace_connection_id=s.marketplace_connection_id AND d.id=s.source_document_id
  WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.shipment_date<=?
   AND d.received_at<=? AND d.normalized_at<=? AND d.source_type='FBA_CUSTOMER_SHIPMENTS_30_DAY'
 ), physical_keys AS (
  SELECT DISTINCT shipment_id,shipment_item_id FROM candidates s WHERE EXISTS(SELECT 1 FROM cohort i WHERE i.amazon_order_id=s.amazon_order_id)
 ), physical AS MATERIALIZED (
  SELECT s.shipment_id,s.shipment_item_id,
   array_agg(DISTINCT s.amazon_order_id) orders,array_agg(DISTINCT s.amazon_order_item_id) items,
   array_agg(DISTINCT s.seller_sku) skus,array_agg(DISTINCT s.asin) FILTER(WHERE nullif(trim(s.asin),'') IS NOT NULL) asins,
   min(s.quantity) units,
   count(DISTINCT (s.amazon_order_id,s.amazon_order_item_id,s.seller_sku))<>1
    OR count(DISTINCT s.asin) FILTER(WHERE nullif(trim(s.asin),'') IS NOT NULL)>1
    OR min(s.quantity)<>max(s.quantity)
    OR bool_or(s.quantity<0 OR nullif(trim(s.shipment_id),'') IS NULL OR nullif(trim(s.shipment_item_id),'') IS NULL
      OR NOT(coalesce(s.raw_payload->>'quantity-shipped','') ~ '^[0-9]{1,9}$')
      OR CASE WHEN coalesce(s.raw_payload->>'quantity-shipped','') ~ '^[0-9]{1,9}$'
         THEN (s.raw_payload->>'quantity-shipped')::bigint<>s.quantity ELSE true END) invalid
  FROM candidates s JOIN physical_keys k ON k.shipment_id IS NOT DISTINCT FROM s.shipment_id AND k.shipment_item_id IS NOT DISTINCT FROM s.shipment_item_id
  GROUP BY s.shipment_id,s.shipment_item_id
 ), matches AS (
  SELECT p.*,i.id,count(*) OVER(PARTITION BY p.shipment_id,p.shipment_item_id) aliases
  FROM physical p JOIN cohort i ON i.amazon_order_id=ANY(p.orders) AND i.seller_sku=ANY(p.skus)
   AND (i.amazon_order_item_id=ANY(p.items) OR i.api_item=ANY(p.items))
 ), fulfilled AS (
  SELECT m.id,sum(m.units) units,bool_or(m.invalid OR m.aliases<>1 OR (m.asins IS NOT NULL AND NOT(coalesce(i.asin=ALL(m.asins),false)))) invalid
  FROM matches m JOIN cohort i ON i.id=m.id GROUP BY m.id
 )
 SELECT i.seller_sku,i.purchase_date,
  CASE WHEN i.quantity_ordered<0 OR i.quantity_shipped<0 OR i.quantity_shipped>i.quantity_ordered THEN NULL
   WHEN f.invalid OR f.units>i.quantity_ordered THEN NULL
   WHEN f.units IS NOT NULL AND i.quantity_shipped>0 AND f.units<>i.quantity_shipped THEN NULL
   WHEN f.units IS NOT NULL THEN f.units
   WHEN i.quantity_shipped>0 THEN i.quantity_shipped
   WHEN i.status IN ('SHIPPED','PARTIALLYSHIPPED') THEN NULL
   ELSE 0 END fulfilled_units,
  i.promotion_discount IS NOT NULL promotion_known,coalesce(i.promotion_discount,0)>0 promotion
 FROM cohort i LEFT JOIN fulfilled f ON f.id=i.id ORDER BY i.purchase_date,i.id
 """;
 static Line line(java.sql.ResultSet r)throws java.sql.SQLException{Number units=(Number)r.getObject(3);return new Line(r.getString(1),r.getTimestamp(2).toInstant(),units==null?null:Math.toIntExact(units.longValue()),r.getBoolean(4),r.getBoolean(5));}
 Evidence read(UUID tenant,UUID store,Instant asOf){return new Evidence(asOf,jdbc.query(SQL,(r,n)->line(r),tenant,store,java.sql.Timestamp.from(asOf.minus(Duration.ofDays(450))),java.sql.Timestamp.from(asOf),tenant,store,java.sql.Timestamp.from(asOf),java.sql.Timestamp.from(asOf),java.sql.Timestamp.from(asOf)));}
}
