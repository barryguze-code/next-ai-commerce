package com.nextaicommerce.platform.orders;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class RefundOrdersDatabaseTest {
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
 @BeforeAll static void setup() throws Exception {
  schema="refund_test_"+UUID.randomUUID().toString().replace("-","");
  var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim());
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
 }
 @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("refund_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
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
