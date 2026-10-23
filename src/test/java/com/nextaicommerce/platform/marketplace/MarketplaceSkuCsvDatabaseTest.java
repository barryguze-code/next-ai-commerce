package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import com.nextaicommerce.platform.orders.SkuRefundHistory;
import com.nextaicommerce.platform.profit.ProfitRepository;

/** Creates only deterministic disposable fixture tables. No migrations/UAT/providers. */
@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class MarketplaceSkuCsvDatabaseTest {
 static String schema;static JdbcTemplate jdbc,writer;static TransactionTemplate tx;
 final UUID tenant=UUID.randomUUID(),other=UUID.randomUUID(),store=UUID.randomUUID(),otherStore=UUID.randomUUID();
 @BeforeAll static void fixture()throws Exception{
  schema="sku_csv_"+UUID.randomUUID().toString().replace("-","");
  String url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema;
  var ds=new DriverManagerDataSource(url,"postgres",Files.readString(Path.of(".local/database-password")).trim());jdbc=new JdbcTemplate(ds);writer=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",Files.readString(Path.of(".local/database-password")).trim()));
  jdbc.execute("CREATE SCHEMA "+schema);
  jdbc.execute("CREATE TABLE marketplace_connections(id uuid,tenant_id uuid,marketplace_identifier text)");
  jdbc.execute("CREATE TABLE amazon_listings(tenant_id uuid,marketplace_connection_id uuid,marketplace_id text,seller_sku text,quantity integer,currency text,fulfillment_channel text)");
  jdbc.execute("CREATE TABLE marketplace_sku_mappings(tenant_id uuid,marketplace_connection_id uuid,marketplace_sku text,status text,account_catalog_item_id uuid)");
  jdbc.execute("CREATE TABLE account_catalog_items(id uuid,tenant_id uuid,account_sku text)");
  jdbc.execute("CREATE TABLE amazon_sku_refunds(tenant_id uuid,marketplace_connection_id uuid,seller_sku text,posted_date timestamptz,currency text)");
  jdbc.execute("CREATE TABLE amazon_orders(tenant_id uuid,marketplace_connection_id uuid,amazon_order_id text,currency text,purchase_date timestamptz,order_status text)");
  jdbc.execute("CREATE TABLE amazon_order_items(tenant_id uuid,marketplace_connection_id uuid,amazon_order_id text,seller_sku text,shipping_price numeric)");
  jdbc.execute("CREATE TABLE amazon_inventory_snapshots(payload text)");jdbc.update("INSERT INTO amazon_inventory_snapshots VALUES ('untouched historical snapshot')");
  tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(60);
 }
 @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("sku_csv_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 void insert(UUID t,UUID c,String market,int quantity){jdbc.update("INSERT INTO marketplace_connections VALUES (?,?,?)",c,t,market);jdbc.update("INSERT INTO amazon_listings VALUES (?,?,?,'0000147',?,'USD','DEFAULT')",t,c,market,quantity);}
 MarketplaceSkuCsvExport service(MarketplaceSkuRepository list){return new MarketplaceSkuCsvExport(list,mock(FbaReadRepository.class),mock(SkuRefundHistory.class),mock(ProfitRepository.class),jdbc,new ObjectMapper());}
 @Test void rawEvidencePreservesExactKeyAndTenantConnectionMarketplaceScope(){
  insert(tenant,store,"US",7);insert(other,otherStore,"CA",99);UUID second=UUID.randomUUID();insert(tenant,second,"CA",44);
  var export=service(mock(MarketplaceSkuRepository.class));var result=tx.execute(s->export.rawEvidence(tenant,store,List.of("0000147")));assertThat(result).hasSize(1);assertThat(result.get("0000147").get("quantity")).isEqualTo(7);assertThat(tx.<Map<String,Map<String,Object>>>execute(s->export.rawEvidence(tenant,store,List.of("147")))).isEmpty();
  assertThat(tx.<Map<String,Map<String,Object>>>execute(s->export.rawEvidence(other,store,List.of("0000147")))).isEmpty();
  jdbc.update("UPDATE amazon_listings SET marketplace_id='WRONG' WHERE tenant_id=? AND marketplace_connection_id=?",tenant,store);
  assertThat(tx.<Map<String,Map<String,Object>>>execute(s->export.rawEvidence(tenant,store,List.of("0000147")))).isEmpty();
 }
 @Test void consistentReadSnapshotSurvivesConcurrentSourceChangeWithoutInventoryMutation(){
  insert(tenant,store,"US",7);var list=mock(MarketplaceSkuRepository.class);when(list.relationshipPolicy(tenant,store)).thenReturn(MarketplaceSkuRelationships.Batch.empty());var export=service(list);
  var sku=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","0000147","marketplaceId","US","fulfillmentChannel","DEFAULT","calculatedAvailable",new BigDecimal("7"),"listingStatus","ACTIVE"));
  when(list.list(eq(tenant),eq(store),anyString(),anyString(),anyString(),anyString(),eq(0),eq(100),anyMap())).thenAnswer(i->{
   assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
   assertThat(jdbc.queryForObject("SELECT quantity FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=?",Integer.class,tenant,store)).isEqualTo(7);
   writer.update("UPDATE amazon_listings SET quantity=9 WHERE tenant_id=? AND marketplace_connection_id=?",tenant,store);
   return new MarketplaceSkuRepository.SkuPage(List.of(sku),1,0,100);
  });
  when(list.mappingComponents(eq(tenant),eq(store),anyList())).thenReturn(Map.of());when(export.stock.stocks(any(),any(),any())).thenReturn(Map.of());when(export.refunds.fourWeeks(any(),any(),any())).thenReturn(Map.of());
  var request=new MarketplaceSkuCsvExport.Request("","ALL","status","asc",Map.of(),List.of("product-details","available"),Map.of());
  var artifact=tx.execute(s->export.generate(tenant,store,request));assertThat(new String(artifact.bytes(),java.nio.charset.StandardCharsets.UTF_8)).contains("\"0000147\"").contains("\"7\"").doesNotContain(" units state\"");
  assertThat(jdbc.queryForObject("SELECT quantity FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=?",Integer.class,tenant,store)).isEqualTo(9);
  assertThat(jdbc.queryForObject("SELECT payload FROM amazon_inventory_snapshots",String.class)).isEqualTo("untouched historical snapshot");
 }
 @Test void weekBoundaryPredicatesDoNotOverlap(){
  var counts=tx.execute(s->jdbc.queryForMap("""
   WITH t AS (SELECT transaction_timestamp() anchor), events AS
    (SELECT anchor,anchor-n*interval '7 days' occurred FROM t CROSS JOIN generate_series(0,4) n)
   SELECT count(*) FILTER(WHERE occurred>=anchor-interval '28 days' AND occurred<anchor-interval '21 days') w1,
    count(*) FILTER(WHERE occurred>=anchor-interval '21 days' AND occurred<anchor-interval '14 days') w2,
    count(*) FILTER(WHERE occurred>=anchor-interval '14 days' AND occurred<anchor-interval '7 days') w3,
    count(*) FILTER(WHERE occurred>=anchor-interval '7 days' AND occurred<=anchor) w4 FROM events
   """));assertThat(counts.get("w1")).isEqualTo(1L);assertThat(counts.get("w2")).isEqualTo(1L);assertThat(counts.get("w3")).isEqualTo(1L);assertThat(counts.get("w4")).isEqualTo(2L);
 }
}
