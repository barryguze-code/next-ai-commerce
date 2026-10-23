package com.nextaicommerce.platform.orders;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.web.AccountSelectionController;
import tools.jackson.databind.ObjectMapper;

/** Only the disposable, fixed loopback test database; never UAT or production. */
@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class OrderSmartFiltersDatabaseTest {
 @Test void buyBoxLostExcludesMissingAndZeroSaleAmounts(){tx.executeWithoutResult(s->{
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Price guard test')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','US','test','ACTIVE','Test','America/Chicago',now())",connection,tenant);
  jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,buy_box_price,currency,quantity,fulfillment_channel) VALUES (?,?,'US','SKU',30,25,'USD',10,'DEFAULT')",tenant,connection);
  var ids=new ArrayList<String>();UUID confirmed=null;
  for(int i=0;i<3;i++){
   String order="price-"+i;ids.add(order);UUID item=UUID.randomUUID();if(i==2)confirmed=item;
   jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,currency) VALUES (?,?,'US',?,now(),'Pending','USD')",tenant,connection,order);
   jdbc.update("INSERT INTO amazon_order_items(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price,currency) VALUES (?,?,?,?,?,'SKU',1,?,'USD')",item,tenant,connection,order,order,i==0?null:i==1?0:30);
  }
  var repo=new OrderRepository(jdbc);
  assertThat(repo.filteredSummary(tenant,connection,"BUY_BOX_LOST","",Map.of()).orders()).isEqualTo(1);
  assertThat(repo.buyBoxLostItems(tenant,connection,ids)).containsExactly(confirmed);
  assertThat(repo.buyBoxLostItems(tenant,UUID.randomUUID(),ids)).isEmpty();
 });}
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
 @BeforeAll static void setup() throws Exception {
  schema="smart_filter_test_"+UUID.randomUUID().toString().replace("-","");
  var source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim());
  org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
 }
 @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("smart_filter_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 @Test void totalsCountOrdersOnceAndIncludeEveryPage(){tx.executeWithoutResult(s->{
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Test')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','PENDING','Test','America/Los_Angeles',now())",connection,tenant);
  for(int i=0;i<31;i++){String order="summary-"+i;jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,currency) VALUES (?,?,'ATVPDKIKX0DER',?,now(),'Unshipped','USD')",tenant,connection,order);
   for(int j=0;j<2;j++)jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price,shipping_price,shipping_discount,currency) VALUES (?,?,?,?,?,?,?,?,?,'USD')",tenant,connection,order,order+"-"+j,j==0?"A":"B",j+2,(j+1)*10,2,j==0?1:0);
  }
  var repo=new OrderRepository(jdbc);var total=repo.filteredSummary(tenant,connection,"ALL","",Map.of());assertThat(total.orders()).isEqualTo(31);assertThat(total.units()).isEqualTo(155);assertThat(total.amounts().getFirst().sales()).isEqualByComparingTo("930");assertThat(total.amounts().getFirst().shipping()).isEqualByComparingTo("93");
  assertThat(repo.orders(tenant,connection,"ALL","",0,10).rows()).hasSize(10);
  var filtered=repo.filteredSummary(tenant,connection,"ALL","",Map.of("f_quantity_min","3"));assertThat(filtered.orders()).isEqualTo(31);assertThat(filtered.units()).isEqualTo(93);assertThat(filtered.amounts().getFirst().sales()).isEqualByComparingTo("620");
  assertThat(repo.filteredSummary(tenant,connection,"ALL","absent",Map.of()).orders()).isZero();
 });}
 @Test void unmappedFbaPendingAndShippedOrdersAreIncluded(){tx.executeWithoutResult(s->{
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'FBA mapping test')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','ACTIVE','Test','America/Los_Angeles',now())",connection,tenant);
  for(String status:List.of("Pending","Shipped")){
   jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,fulfillment_channel,currency) VALUES (?,?,'ATVPDKIKX0DER',?,now(),?,'AFN','USD')",tenant,connection,status,status);
   jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,currency) VALUES (?,?,?,?,'UNMAPPED',2,'USD')",tenant,connection,status,status);
  }
  var repo=new OrderRepository(jdbc);var summary=repo.filteredSummary(tenant,connection,"NEEDS_MAPPING","",Map.of());
  assertThat(summary.orders()).isEqualTo(2);assertThat(summary.units()).isEqualTo(4);
  assertThat(repo.orders(tenant,connection,"NEEDS_MAPPING","",0,25).rows()).hasSize(2);
  assertThat(repo.orders(tenant,UUID.randomUUID(),"NEEDS_MAPPING","",0,25).rows()).isEmpty();
 });}
 @Test void marketplaceSkuFiltersExecuteAgainstSchema(){tx.executeWithoutResult(s->{
 var repo=new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository(jdbc);UUID t=UUID.randomUUID(),c=UUID.randomUUID();
 var filters=new HashMap<String,String>();for(String k:List.of("sku","asin","itemCode","product","orderStatus"))filters.put("f_"+k,"sample");
 for(String k:List.of("available","sales","shipping","buyBox","soldUnits","soldOrders","fees")){filters.put("f_"+k+"_min","0");filters.put("f_"+k+"_max","999");}
 filters.put("f_date_min","2026-01-01T00:00:00Z");filters.put("f_channel","FBM");
 assertThat(repo.list(t,c,"","ALL","fourWeek","desc",0,25,filters).rows()).isEmpty();
 var controller=new com.nextaicommerce.platform.marketplace.MarketplaceSkuFilterController(jdbc,new ObjectMapper());
 var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,t);var auth=new UsernamePasswordAuthenticationToken("alice","unused");
 controller.save(new com.nextaicommerce.platform.marketplace.MarketplaceSkuFilterController.Save("SKU tag",Map.of("f_available_max","2")),session,auth);
 assertThat(controller.list(session,auth)).hasSize(1);assertThat(new OrderFilterController(jdbc,new ObjectMapper()).list(session,auth)).isEmpty();
 });}
 @Test void everyFilterAndSortExecutesAgainstTheRealSchema(){tx.executeWithoutResult(s->{var repo=new OrderRepository(jdbc);UUID t=UUID.randomUUID(),c=UUID.randomUUID();var filters=new HashMap<String,String>();for(String k:List.of("orderId","sku","asin","itemCode","product","orderStatus"))filters.put("f_"+k,"sample");for(String k:List.of("quantity","available","sales","shipping","buyBox","soldUnits","soldOrders")){filters.put("f_"+k+"_min","0");filters.put("f_"+k+"_max","999");}filters.put("f_date_min","2026-01-01T00:00:00Z");for(String sort:List.of("","units_desc","units_asc","orders_desc","orders_asc")){filters.put("smartSort",sort);assertThat(repo.orders(t,c,"ALL","",0,25,filters).rows()).isEmpty();}assertThat(repo.matchingItems(t,c,List.of("absent"),filters)).isEmpty();assertThat(repo.soldTotals(t,c,List.of("absent"))).isEmpty();});}
 @Test void presetsArePrivateAndFollowTheAccount(){tx.executeWithoutResult(s->{var controller=new OrderFilterController(jdbc,new ObjectMapper());var session=new MockHttpSession();UUID account=UUID.randomUUID();session.setAttribute(AccountSelectionController.TENANT_ID,account);var a=new UsernamePasswordAuthenticationToken("alice","unused");var b=new UsernamePasswordAuthenticationToken("bob","unused");var saved=controller.save(new OrderFilterController.Save("Low stock",Map.of("f_available_max","2")),session,a);controller.rename(UUID.fromString(saved.get("id")),new OrderFilterController.Rename("Renamed"),session,a);assertThat(controller.list(session,a).getFirst().get("name")).isEqualTo("Renamed");assertThatThrownBy(()->controller.rename(UUID.fromString(saved.get("id")),new OrderFilterController.Rename("Other"),session,b)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(controller.list(session,a)).hasSize(1);assertThat(controller.list(session,b)).isEmpty();session.setAttribute(AccountSelectionController.TENANT_ID,UUID.randomUUID());assertThat(controller.list(session,a)).isEmpty();session.setAttribute(AccountSelectionController.TENANT_ID,account);controller.delete(UUID.fromString(saved.get("id")),session,a);assertThat(controller.list(session,a)).isEmpty();});}
}
