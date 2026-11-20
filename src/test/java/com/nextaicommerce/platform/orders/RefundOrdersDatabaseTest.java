package com.nextaicommerce.platform.orders;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class RefundOrdersDatabaseTest {
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
 @BeforeAll static void setup() throws Exception {
  schema="refund_test_"+UUID.randomUUID().toString().replace("-","");
  schema="public";
  postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
  var ds=new DriverManagerDataSource(postgres.getJdbcUrl("postgres","postgres"),"postgres",null);
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
 }
 @AfterAll static void cleanup() throws Exception {if(postgres!=null)postgres.close();}
 @Test void countsLifecycleOnceWithoutDiscardingIndependentEqualPartialRefunds(){tx.executeWithoutResult(s->{
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Lifecycle test')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','ACTIVE','Test','UTC',now())",store,tenant);
  for(String key:List.of("deferred","released","partial")){
   String status=key.equals("deferred")?"DEFERRED_RELEASED":"RELEASED";
   String identifiers=key.equals("deferred")?"{\"relatedIdentifierName\":\"RELEASE_TRANSACTION_ID\",\"relatedIdentifierValue\":\"released\"}":key.equals("released")?"{\"relatedIdentifierName\":\"DEFERRED_TRANSACTION_ID\",\"relatedIdentifierValue\":\"deferred\"}":"";
   jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,posted_date,raw_payload) VALUES (?,?,?,'Refund','ORDER',now()-interval '1 day',?::jsonb)",tenant,store,key,
    "{\"transactionStatus\":\""+status+"\",\"relatedIdentifiers\":["+identifiers+"],\"items\":[{\"sellerSku\":\"SKU\",\"breakdowns\":[{\"breakdownType\":\"Principal\",\"breakdownAmount\":{\"currencyCode\":\"USD\",\"currencyAmount\":-10}}]}]}");
  }
  var repo=new SkuRefundHistory(jdbc,new tools.jackson.databind.ObjectMapper());
  assertThat(repo.thirtyDays(tenant,store).getFirst().amount()).isEqualByComparingTo("20");
  assertThat(repo.orders(tenant,store,"SKU",28,-1,0,null).rows().getFirst().amount()).isEqualByComparingTo("20");
  assertThat(repo.fourWeeks(tenant,store,List.of("SKU")).get("SKU")).contains("20.0000");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_sku_refunds WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(3);
  // Read model remains correct after a repeated source observation, and never destroys evidence.
  jdbc.update("UPDATE amazon_financial_transactions SET raw_payload=raw_payload WHERE tenant_id=? AND transaction_key='released'",tenant);
  assertThat(repo.thirtyDays(tenant,store).getFirst().amount()).isEqualByComparingTo("20");
  UUID product=jdbc.queryForObject("INSERT INTO global_catalog_products(canonical_name) VALUES ('Refund product') RETURNING id",UUID.class);
  UUID item=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'PRODUCT') RETURNING id",UUID.class,tenant,product);
  jdbc.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,quantity_per_marketplace_unit) VALUES (?,?,?,'SKU',4)",tenant,store,item);
  assertThat(repo.productFourWeeks(tenant,List.of(item)).get(item.toString())).contains("20.0000");
  // A second product in a bundle must not receive a duplicate allocation of the SKU's money.
  UUID secondProduct=jdbc.queryForObject("INSERT INTO global_catalog_products(canonical_name) VALUES ('Other refund product') RETURNING id",UUID.class);
  UUID second=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'SECOND') RETURNING id",UUID.class,tenant,secondProduct);
  for(UUID component:List.of(item,second))jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) SELECT tenant_id,id,?,1 FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_sku='SKU'",component,tenant);
  assertThat(repo.productFourWeeks(tenant,List.of(item,second)).get(item.toString())).contains("\"amount\":null","\"sharedRefunds\":2");
  var productPage=repo.productOrders(tenant,item,28,-1,0,null);assertThat(productPage.rows()).hasSize(1);assertThat(productPage.rows().getFirst().sharedBundle()).isTrue();assertThat(productPage.rows().getFirst().connectionId()).isEqualTo(store);assertThat(productPage.rows().getFirst().amount()).isEqualByComparingTo("20");
  UUID otherStore=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','other','ATVPDKIKX0DER','test','ACTIVE','Other store','UTC',now())",otherStore,tenant);
  jdbc.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,quantity_per_marketplace_unit) VALUES (?,?,?,'OTHER-SKU',1)",tenant,otherStore,item);insert(tenant,otherStore,"OTHER-ORDER","OTHER-SKU","CAD");
  assertThat(repo.productOrders(tenant,item,28,-1,0,null).rows()).hasSize(2).extracting(SkuRefundHistory.RefundOrder::storeName).contains("Other store","Test");
  assertThat(repo.productOrders(UUID.randomUUID(),item,28,-1,0,null).rows()).isEmpty();

  jdbc.update("DELETE FROM amazon_financial_transactions WHERE tenant_id=? AND transaction_key='released'",tenant);
  assertThat(repo.thirtyDays(tenant,store).getFirst().amount()).isEqualByComparingTo("20");

 });}

 @Test void pagesRefundEvidenceWithoutMixingStoresCurrenciesOrInventingUnits(){tx.executeWithoutResult(s->{
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),other=UUID.randomUUID();
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Refund test')",tenant,tenant.toString());
  for(UUID id:List.of(store,other))jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test','ACTIVE','Refund test','UTC',now())",id,tenant,id.toString());
  for(int i=0;i<52;i++)insert(tenant,store,"order-"+i,"SKU",i==0?"CAD":"USD");
  insert(tenant,other,"other-store","SKU","USD");insert(tenant,store,"different-sku","OTHER","USD");
  var repo=new SkuRefundHistory(jdbc,new tools.jackson.databind.ObjectMapper());
  var first=repo.orders(tenant,store,"SKU",28,-1,0,null);
  assertThat(first.rows()).hasSize(50);assertThat(first.hasMore()).isTrue();
  assertThat(first.rows()).allSatisfy(r->{assertThat(r.sellerSku()).isEqualTo("SKU");assertThat(r.units()).isNull();assertThat(r.amount()).isEqualByComparingTo("10");assertThat(r.orderId()).startsWith("order-");});
  var second=repo.orders(tenant,store,"SKU",28,-1,1,first.asOf());assertThat(second.rows()).hasSize(2);assertThat(second.hasMore()).isFalse();
  assertThat(repo.orders(tenant,other,"SKU",28,-1,0,null).rows()).extracting(SkuRefundHistory.RefundOrder::orderId).containsExactly("other-store");
  assertThat(repo.orders(tenant,store,"SKU",28,0,0,null).rows()).isEmpty();
  assertThatThrownBy(()->repo.orders(tenant,store,"SKU",365,-1,0,null)).isInstanceOf(IllegalArgumentException.class);
 });}
 static void insert(UUID tenant,UUID store,String order,String sku,String currency){
  jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,posted_date,raw_payload) VALUES (?,?,?,'Refund',?,now()-interval '1 day',?::jsonb)",tenant,store,UUID.randomUUID().toString(),order,
   "{\"items\":[{\"sellerSku\":\""+sku+"\",\"breakdowns\":[{\"breakdownType\":\"Principal\",\"breakdownAmount\":{\"currencyCode\":\""+currency+"\",\"currencyAmount\":-10}}]}]}");
 }
}
