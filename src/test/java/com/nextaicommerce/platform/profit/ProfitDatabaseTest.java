package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProfitDatabaseTest {
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
 UUID tenant,connection;ProfitRepository repo;
 @BeforeAll static void setup() throws Exception {
  schema="profit_test_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
  if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();}
  else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
  var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
  org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  jdbc.execute("ALTER FUNCTION "+schema+".ensure_item_default_location() SET search_path TO "+schema);
 }
 @AfterAll static void cleanup() throws Exception {if(jdbc!=null&&schema.matches("profit_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(postgres!=null)postgres.close();}
 @BeforeEach void fixture(){tenant=UUID.randomUUID();connection=UUID.randomUUID();repo=new ProfitRepository(jdbc,new ProfitCostRepository(jdbc));tx.executeWithoutResult(s->{
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Profit test')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','ACTIVE','Test','America/Los_Angeles',now())",connection,tenant);
  UUID product=jdbc.queryForObject("INSERT INTO global_catalog_products(canonical_name) VALUES ('Test item') RETURNING id",UUID.class);
  UUID item=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'CODE') RETURNING id",UUID.class,tenant,product);
  UUID vendor=jdbc.queryForObject("INSERT INTO vendors(tenant_id,name) VALUES (?,'Vendor') RETURNING id",UUID.class,tenant);
  jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,list_cost,effective_from,is_default) VALUES (?,?,?,2,'2025-01-01',true)",tenant,vendor,item);
  jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,profit_package_type) VALUES (?,?,'ATVPDKIKX0DER','SKU',20,'USD','MFN','FEDEX_XSMALL')",tenant,connection);
  jdbc.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,quantity_per_marketplace_unit) VALUES (?,?,?,'SKU',2)",tenant,connection,item);
  jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,fulfillment_channel,currency) VALUES (?,?,'ATVPDKIKX0DER','ORDER','2025-06-01T12:00:00Z','MFN','USD')",tenant,connection);
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price,currency) VALUES (?,?,'ORDER','LINE','SKU',3,45,'USD')",tenant,connection);
 });}
 @Test void skuOverridePersistsAndResetUsesCatalogueWithoutChangingItemOrSale(){tx.executeWithoutResult(s->{
  repo.saveDefaults(tenant,connection,"SKU","SKU",List.of(new ProfitRepository.SkuCostChange("SKU",new BigDecimal("6.25"))),List.of(),null,BigDecimal.ONE,"FEDEX_XSMALL","tester");
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").lines().getFirst().productCost()).isEqualByComparingTo("6.25");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").lines().getFirst().productCost()).isEqualByComparingTo("6.25");
  assertThat(repo.defaults(tenant,connection,List.of("SKU")).getFirst().items().getFirst().cost()).isEqualByComparingTo("2");
  repo.saveDefaults(tenant,connection,"SKU","SKU",List.of(new ProfitRepository.SkuCostChange("SKU",null)),List.of(),null,BigDecimal.ONE,"FEDEX_XSMALL","tester");
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").lines().getFirst().productCost()).isEqualByComparingTo("4");
  assertThat(jdbc.queryForObject("SELECT price FROM amazon_listings WHERE tenant_id=?",BigDecimal.class,tenant)).isEqualByComparingTo("20");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM sku_product_cost_history WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
 });}
 @Test void itemEditorRecalculatesPackCostAndAuditsVendorCost(){tx.executeWithoutResult(s->{
  String actor=tenant+"@test.invalid";jdbc.update("INSERT INTO app_users(email,display_name) VALUES (?,'Cost editor')",actor);
  var item=repo.defaults(tenant,connection,List.of("SKU")).getFirst().items().getFirst();
  assertThat(item.quantity()).isEqualByComparingTo("2");
  repo.saveDefaults(tenant,connection,"SKU","SKU",List.of(),List.of(new ProfitRepository.ItemCostChange(item.id(),new BigDecimal("3.125"))),null,BigDecimal.ONE,"FEDEX_XSMALL",actor);
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").lines().getFirst().productCost()).isEqualByComparingTo("6.25");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor_cost_history WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
 });}
 @Test void calculatorSaveRollsBackAllChangesIfPackagesAreInvalid(){
  assertThatThrownBy(()->tx.executeWithoutResult(s->repo.saveDefaults(tenant,connection,"SKU","SKU",
    List.of(new ProfitRepository.SkuCostChange("SKU",new BigDecimal("9.00"))),List.of(),List.of(),BigDecimal.ONE,null,"tester")))
    .isInstanceOf(IllegalArgumentException.class);
  tx.executeWithoutResult(s->{assertThat(repo.defaults(tenant,connection,List.of("SKU")).getFirst().override()).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sku_product_cost_history WHERE tenant_id=?",Integer.class,tenant)).isZero();});
 }
 @Test void defaultsRejectUnrelatedSkuAndItem(){tx.executeWithoutResult(s->{
  assertThatThrownBy(()->repo.saveDefaults(tenant,connection,"SKU","SKU",List.of(new ProfitRepository.SkuCostChange("FOREIGN",BigDecimal.ONE)),List.of(),null,BigDecimal.ONE,null,"test")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->repo.saveDefaults(tenant,connection,"SKU","SKU",List.of(),List.of(new ProfitRepository.ItemCostChange(UUID.randomUUID(),BigDecimal.ONE)),null,BigDecimal.ONE,null,"test")).isInstanceOf(IllegalArgumentException.class);
  assertThat(repo.defaults(UUID.randomUUID(),connection,List.of("SKU"))).isEmpty();
 });}
 @Test void skuIncludesAllMappedUnitsAndOnlyOneOtherCost(){tx.executeWithoutResult(s->{var v=repo.skus(tenant,connection,List.of("SKU")).get("SKU");assertThat(v.totals().productCost()).isEqualByComparingTo("4.00");assertThat(v.totals().otherCost()).isEqualByComparingTo("1.00");assertThat(v.totals().profit()).isEqualByComparingTo("2.01");});}
 @Test void multipleUnitsNeedPackageConfirmationAndCanAddAnother(){tx.executeWithoutResult(s->{
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().profit()).isNull();
  repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Box",new BigDecimal("9.99"))),"test");
  var first=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");assertThat(first.totals().referralFee()).isEqualByComparingTo("3.60");assertThat(first.totals().profit()).isEqualByComparingTo("16.41");
  repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Box",new BigDecimal("9.99")),new ProfitRepository.PackageCost("Extra",new BigDecimal("5.00"))),"test");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().profit()).isEqualByComparingTo("11.41");
 });}
 @Test void otherTenantCannotReadOrChangeCosts(){tx.executeWithoutResult(s->{UUID other=UUID.randomUUID();assertThat(repo.skus(other,connection,List.of("SKU"))).isEmpty();assertThat(repo.orders(other,connection,List.of("ORDER"))).isEmpty();assertThatThrownBy(()->repo.saveSku(other,connection,"SKU",null,BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);});}
 @Test void fbaAndForeignCurrencyNeverShowIncompleteProfit(){tx.executeWithoutResult(s->{jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AFN' WHERE tenant_id=?",tenant);assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals()).isNull();jdbc.update("UPDATE amazon_listings SET fulfillment_channel='MFN',currency='CAD' WHERE tenant_id=?",tenant);assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals()).isNull();});}
 @Test void missingProductCostIsNotSilentlyZero(){tx.executeWithoutResult(s->{jdbc.update("DELETE FROM vendor_catalog_offers WHERE tenant_id=?",tenant);assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals().profit()).isNull();});}
 @Test void cancelledOrderDoesNotShowSalesProfit(){tx.executeWithoutResult(s->{jdbc.update("UPDATE amazon_orders SET order_status='Canceled' WHERE tenant_id=?",tenant);assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals()).isNull();});}
 @Test void missingListingCurrencyUsesKnownMarketplaceDefinition(){tx.executeWithoutResult(s->{jdbc.update("UPDATE amazon_listings SET currency=NULL WHERE tenant_id=?",tenant);var view=repo.skus(tenant,connection,List.of("SKU")).get("SKU");assertThat(view.currency()).isEqualTo("USD");assertThat(view.totals().profit()).isEqualByComparingTo("2.01");});}
 @Test void changingSkuCostsDoesNotChangeSellingPrice(){tx.executeWithoutResult(s->{repo.saveSku(tenant,connection,"SKU",ProfitShippingRates.PackageType.UPS,new BigDecimal("2.00"));assertThat(jdbc.queryForObject("SELECT price FROM amazon_listings WHERE tenant_id=?",BigDecimal.class,tenant)).isEqualByComparingTo("20");});}
 @Test void skuUsesSingleUnitPackingHistoryWithoutNewProfile(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_listings SET profit_package_type=NULL WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,amazon_order_id,order_item_summary,packaging,order_sku_qty_list) VALUES (?,?,'PAST','ASIN-1','XSmall','SKU-1')",tenant,connection);
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals().shippingCost()).isEqualByComparingTo("9.99");
  jdbc.update("UPDATE temporary_order_packaging_lookup SET packaging='Insulated UPS carton' WHERE tenant_id=?",tenant);
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals().shippingCost()).isEqualByComparingTo("14.20");
 });}
 @Test void orderUsesPackingSlipDecisionForMultipleUnitsAndPreservesExtraPackages(){tx.executeWithoutResult(s->{
  jdbc.update("INSERT INTO temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,amazon_order_id,order_item_summary,packaging,order_sku_qty_list) VALUES (?,?,'ORDER','UNKNOWN-3','Large','SKU-3')",tenant,connection);
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().shippingCost()).isEqualByComparingTo("23.59");
  repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Large",new BigDecimal("23.59")),new ProfitRepository.PackageCost("Extra UPS",new BigDecimal("14.20"))),"test");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().shippingCost()).isEqualByComparingTo("37.79");
 });}
 @Test void orderUsesMatchingPackingSignatureWhenNoExactOrderExists(){tx.executeWithoutResult(s->{
  jdbc.update("INSERT INTO temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,amazon_order_id,order_item_summary,packaging,order_sku_qty_list) VALUES (?,?,'PAST','UNKNOWN-3','Pak','SKU-3')",tenant,connection);
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().shippingCost()).isEqualByComparingTo("12.08");
 });}
 @Test void activeConfirmedSaleUsesDiscountedPriceButExpiredSaleDoesNot(){tx.executeWithoutResult(s->{
  jdbc.update("""
    INSERT INTO shelf_sale_publications(tenant_id,connection_id,marketplace_id,seller_sku,status,owned_discount)
    VALUES (?,?,'ATVPDKIKX0DER','SKU','CONFIRMED',
      jsonb_build_array(jsonb_build_object('schedule',jsonb_build_array(jsonb_build_object(
        'value_with_tax',15,'start_at',now()-interval '1 day','end_at',now()+interval '1 day')))))
    """,tenant,connection);
  var sale=repo.skus(tenant,connection,List.of("SKU")).get("SKU");
  assertThat(sale.lines().getFirst().unitPrice()).isEqualByComparingTo("15");
  assertThat(sale.totals().referralFee()).isEqualByComparingTo("1.20");
  jdbc.update("UPDATE shelf_sale_publications SET owned_discount=jsonb_set(owned_discount,'{0,schedule,0,end_at}',to_jsonb(now()-interval '1 hour')) WHERE tenant_id=?",tenant);
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").lines().getFirst().unitPrice()).isEqualByComparingTo("20");
 });}
 @Test void priceSubmissionIsAuditedAndCannotBeSentTwice(){
  var amazon=org.mockito.Mockito.mock(com.nextaicommerce.platform.sync.AmazonSpApiClient.class);var json=new tools.jackson.databind.ObjectMapper();
  var environment=new org.springframework.mock.env.MockEnvironment();environment.setActiveProfiles("prod");
  var publisher=new ProfitPricePublisher(jdbc,tx,amazon,json,environment,false,true,true,connection.toString());
  org.mockito.Mockito.when(amazon.get(org.mockito.ArgumentMatchers.eq(tenant),org.mockito.ArgumentMatchers.eq(connection),org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.nextaicommerce.platform.sync.AmazonSpApiClient.ApiResponse(200,"test",json.readTree(ProfitPricePublisherTest.LISTING),""));
  org.mockito.Mockito.when(amazon.patch(org.mockito.ArgumentMatchers.eq(tenant),org.mockito.ArgumentMatchers.eq(connection),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.nextaicommerce.platform.sync.AmazonSpApiClient.ApiResponse(200,"test",json.readTree("{\"status\":\"ACCEPTED\"}"),""));
  var quote=publisher.prepare(tenant,connection,"SKU",new BigDecimal("18.00"),"tester");
  assertThat(publisher.submit(tenant,connection,quote.id(),"tester")).contains("processing is not yet confirmed");
  assertThatThrownBy(()->publisher.submit(tenant,connection,quote.id(),"tester")).isInstanceOf(IllegalArgumentException.class);
  org.mockito.Mockito.verify(amazon,org.mockito.Mockito.times(1)).patch(org.mockito.ArgumentMatchers.eq(tenant),org.mockito.ArgumentMatchers.eq(connection),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
  assertThat(jdbc.queryForObject("SELECT status FROM profit_price_confirmations WHERE id=?",String.class,quote.id())).isEqualTo("ACCEPTED");
  assertThat(jdbc.queryForObject("SELECT price FROM amazon_listings WHERE tenant_id=?",BigDecimal.class,tenant)).isEqualByComparingTo("20");
 }
}
