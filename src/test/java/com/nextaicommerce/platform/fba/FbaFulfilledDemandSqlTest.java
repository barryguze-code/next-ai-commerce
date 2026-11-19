package com.nextaicommerce.platform.fba;

import java.util.*;import java.time.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DriverManagerDataSource;
/** Actual PostgreSQL query against VALUES relations only: no fixtures or application rows are written. */
class FbaFulfilledDemandSqlTest {
 static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;static JdbcTemplate jdbc;
 static final String T="00000000-0000-0000-0000-000000000001",S="00000000-0000-0000-0000-000000000002";
 static final Instant AT=Instant.parse("2026-11-19T14:57:30Z");
 @BeforeAll static void start()throws Exception{String url,password=null;if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("testDatabasePasswordFile",".local/database-password"))).trim();}else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));}
 @AfterAll static void stop()throws Exception{if(postgres!=null)postgres.close();}
 record Item(String id,String order,String item,String alias,String sku,String asin,int ordered,int shipped,String status){Item(int ordered,int shipped,String status){this("line","order","item",null,"SKU","ASIN",ordered,shipped,status);}}
 record Ship(String physical,String item,String sku,String asin,int units,String quantity,String store,boolean future){Ship(String physical,int units){this(physical,"item","SKU",null,units,""+units,S,false);}}
 static String q(String s){return s==null?"NULL":"'"+s.replace("'","''")+"'";}
 List<Map<String,Object>> run(List<Item> items,List<Ship> shipments){
  String itemRows=items.stream().map(i->"("+String.join(",",q(i.id),q(i.order),q(i.item),q(i.alias!=null&&i.alias.startsWith("{")?i.alias:i.alias==null?"{}":"{\"apiOrderItemId\":\""+i.alias+"\"}")+"::jsonb",q(i.sku),q(i.asin),""+i.ordered,""+i.shipped,"0::numeric",q(T)+"::uuid",q(S)+"::uuid")+")").collect(java.util.stream.Collectors.joining(","));
  String orderRows=items.stream().map(i->"("+String.join(",",q(i.order),q(i.status),"'AFN'","'2026-11-01T00:00:00Z'::timestamptz",q(T)+"::uuid",q(S)+"::uuid")+")").distinct().collect(java.util.stream.Collectors.joining(","));
  String shipRows=shipments.stream().map(s->"("+String.join(",",q("shipment"),q(s.physical),q("order"),q(s.item),q(s.sku),q(s.asin),""+s.units,q("{\"quantity-shipped\":\""+s.quantity+"\"}")+"::jsonb",q(T)+"::uuid",q(s.store)+"::uuid","'source'","'2026-11-02T00:00:00Z'::timestamptz")+")").collect(java.util.stream.Collectors.joining(","));
  if(shipRows.isEmpty())shipRows="('none','none','none','none','none',NULL,0,'{}'::jsonb,'"+T+"'::uuid,'"+S+"'::uuid,'none','2026-11-02'::timestamptz)";
  boolean future=shipments.stream().anyMatch(Ship::future);
  String prefix="WITH amazon_order_items(id,amazon_order_id,amazon_order_item_id,raw_payload,seller_sku,asin,quantity_ordered,quantity_shipped,promotion_discount,tenant_id,marketplace_connection_id) AS(VALUES "+itemRows+"),amazon_orders(amazon_order_id,order_status,fulfillment_channel,purchase_date,tenant_id,marketplace_connection_id)AS(VALUES "+orderRows+"),amazon_customer_shipments(shipment_id,shipment_item_id,amazon_order_id,amazon_order_item_id,seller_sku,asin,quantity,raw_payload,tenant_id,marketplace_connection_id,source_document_id,shipment_date)AS(VALUES "+shipRows+"),amazon_source_documents(id,tenant_id,marketplace_connection_id,received_at,normalized_at,source_type)AS(VALUES('source','"+T+"'::uuid,'"+S+"'::uuid,'"+(future?"2026-11-20":"2026-11-03")+"'::timestamptz,'2026-11-03'::timestamptz,'FBA_CUSTOMER_SHIPMENTS_30_DAY')) SELECT * FROM ("+FbaFulfilledDemand.SQL+") actual";
  var ts=java.sql.Timestamp.from(AT);return jdbc.query(prefix,(r,n)->{var line=FbaFulfilledDemand.line(r);var values=new HashMap<String,Object>();values.put("fulfilled_units",line.units());return values;},UUID.fromString(T),UUID.fromString(S),java.sql.Timestamp.from(AT.minus(Duration.ofDays(90))),ts,UUID.fromString(T),UUID.fromString(S),ts,ts,ts);
 }
 Integer units(List<Map<String,Object>> rows){return rows.isEmpty()?null:rows.getFirst().get("fulfilled_units")==null?null:((Number)rows.getFirst().get("fulfilled_units")).intValue();}
 @Test void shippedReportLineRepairsZeroNormalizedShippedWithoutHeaderFallback(){assertEquals(1,units(run(List.of(new Item(1,0,"Shipped")),List.of(new Ship("physical",1)))));assertNull(units(run(List.of(new Item(1,0,"Shipped")),List.of())));}
 @Test void physicalRepeatsDoNotMultiplyAndSplitShipmentsAddOnlyOnce(){assertEquals(2,units(run(List.of(new Item(4,0,"Shipped")),List.of(new Ship("a",1),new Ship("a",1),new Ship("b",1)))));}
 @Test void apiAndShipmentEvidenceAreNeverAddedAndDisagreementsStayUnknown(){assertEquals(2,units(run(List.of(new Item(4,2,"PartiallyShipped")),List.of(new Ship("a",2)))));assertNull(units(run(List.of(new Item(4,1,"Shipped")),List.of(new Ship("a",2)))));}
 @Test void pendingCancelledAndCancellationVariantsAreNotEligibleSales(){for(String status:List.of("Pending","PendingAvailability","Cancelled","Canceled"))assertTrue(run(List.of(new Item(2,0,status)),List.of(new Ship("a",2))).isEmpty());}
 @Test void invalidOverorderedAndReshipQuantitiesAreNotCappedOrInvented(){assertNull(units(run(List.of(new Item(1,0,"Shipped")),List.of(new Ship("a",2)))));assertNull(units(run(List.of(new Item(2,-1,"Shipped")),List.of(new Ship("a",1)))));assertNull(units(run(List.of(new Item(2,0,"Shipped")),List.of(new Ship("a","item","SKU",null,1,"bad",S,false)))));}
 @Test void explicitAsinConflictRejectedButOmittedReportAsinAccepted(){assertNull(units(run(List.of(new Item(2,0,"Shipped")),List.of(new Ship("a","item","SKU","OTHER",1,"1",S,false)))));assertEquals(1,units(run(List.of(new Item(2,0,"Shipped")),List.of(new Ship("a",1)))));}
 @Test void crossStoreAndFutureSourceCannotSupplyEvidence(){assertNull(units(run(List.of(new Item(2,0,"Shipped")),List.of(new Ship("a","item","SKU",null,1,"1",T,false)))));assertNull(units(run(List.of(new Item(2,0,"Shipped")),List.of(new Ship("a","item","SKU",null,1,"1",S,true)))));}
 @Test void conflictingPhysicalIdentityIsFoundBeforeSkuFiltering(){assertNull(units(run(List.of(new Item(2,0,"Shipped")),List.of(new Ship("a",1),new Ship("a","item","OTHER",null,1,"1",S,false)))));}
 @Test void aliasCannotJoinOnePhysicalShipmentIntoTwoNormalizedLines(){var a=new Item("one","order","first","item","SKU","ASIN",2,0,"Shipped");var b=new Item("two","order","second","item","SKU","ASIN",2,0,"Shipped");assertTrue(run(List.of(a,b),List.of(new Ship("a",1))).stream().allMatch(r->r.get("fulfilled_units")==null));}
 @Test void explicitPendingOrCancelledItemOverridesShippedHeaderAndPositiveEvidence(){for(String status:List.of("Cancelled","Canceled","Pending","Pending Availability","Unshipped")){var item=new Item("line","order","item","{\"item-status\":\""+status+"\"}","SKU","ASIN",2,1,"Shipped");assertTrue(run(List.of(item),List.of(new Ship("a",1))).isEmpty(),status);}}

}
