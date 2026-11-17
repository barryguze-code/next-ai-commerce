package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProfitDatabaseTest {
 @Test void priceOmittedByLaterOrderSyncDoesNotEraseKnownSale(){
  var now=java.time.Instant.now();
  var job=new com.nextaicommerce.platform.sync.AmazonSyncStore.Job(UUID.randomUUID(),tenant,UUID.randomUUID(),connection,"ORDERS_API_DELTA","ORDERS_API_DELTA","ATVPDKIKX0DER",now,now,null,0,0,false,null);
  var json=new tools.jackson.databind.ObjectMapper();
  var normalizer=new com.nextaicommerce.platform.sync.AmazonReportNormalizer(jdbc,tx,json,null);
  normalizer.normalizeOrderItems(job,"ORDER",json.readTree("{\"payload\":{\"OrderItems\":[{\"OrderItemId\":\"LINE\",\"SellerSKU\":\"SKU\",\"QuantityOrdered\":3}]}}"),null);
  tx.executeWithoutResult(s->{assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").lines().getFirst().unitPrice()).isEqualByComparingTo("15");
   assertThat(new com.nextaicommerce.platform.orders.OrderRepository(jdbc).itemsForOrders(tenant,connection,List.of("ORDER")).get("ORDER").getFirst().itemPrice()).isEqualByComparingTo("45");});
 }
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
 UUID tenant,connection;ProfitRepository repo;
 @BeforeAll static void setup() throws Exception {
  schema="profit_test_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
  if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();}
  else{schema="public"; // This branch owns a disposable cluster; match production function lookup.
  postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
  var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
  org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
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
  jdbc.update("UPDATE amazon_orders SET items_shipped=0,items_unshipped=3 WHERE tenant_id=? AND marketplace_connection_id=?",tenant,connection);
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price,currency) VALUES (?,?,'ORDER','LINE','SKU',3,45,'USD')",tenant,connection);
 });}

 @Test void unusedPackageCanBeRemovedWhileRatesAuditAndOtherAccountsSurvive(){tx.executeWithoutResult(s->{
  String actor="delete-"+tenant+"@example.invalid";jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Package editor','ACTIVE')",actor);
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Unused carton");managedProfile("Unused carton","FedEx");names.saveRate(tenant,"Unused carton",java.time.LocalDate.of(2025,1,1),BigDecimal.TEN,actor);
  UUID other=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Other account')",other,other.toString());names.registerCustom(other,"Unused carton");
  assertThat(names.removal(tenant,"Unused carton").removable()).isTrue();names.remove(tenant,"Unused carton",actor);
  assertThat(names.names(tenant)).doesNotContain("Unused carton");assertThat(names.names(other)).contains("Unused carton");assertThat(jdbc.queryForObject("SELECT status FROM shipping_package_profiles WHERE tenant_id=? AND name='Unused carton'",String.class,tenant)).isEqualTo("INACTIVE");assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_rates WHERE tenant_id=? AND package_key='unused carton'",Integer.class,tenant)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id=? AND action='PACKING_PACKAGE_DELETED'",Integer.class,tenant)).isEqualTo(1);
 });}
 @Test void usedPackageCannotBeRemovedAndMergedSourceCannotDeleteSurvivor(){tx.executeWithoutResult(s->{
  String actor="used-"+tenant+"@example.invalid";jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Package editor','ACTIVE')",actor);
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Used carton");names.registerCustom(tenant,"Surviving carton");repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Used carton",BigDecimal.TEN)),actor);
  var review=names.removal(tenant,"Used carton");assertThat(review.removable()).isFalse();assertThat(review.dependencies()).anyMatch(d->d.category().equals("Order cost records")&&d.count()==1);
  assertThatThrownBy(()->names.remove(tenant,"Used carton",actor)).hasMessageContaining("Order cost records (1)");assertThat(names.names(tenant)).contains("Used carton");
  var merge=names.preview(tenant,"Used carton","Surviving carton");names.apply(tenant,merge.source(),merge.destination(),merge.revision(),merge.affectedAssignments(),actor);
  assertThat(names.names(tenant)).doesNotContain("Used carton").contains("Surviving carton");assertThatThrownBy(()->names.remove(tenant,"Used carton",actor)).hasMessageContaining("no longer in the list");assertThat(names.removal(tenant,"Surviving carton").removable()).isFalse();
  assertThat(jdbc.queryForObject("SELECT amount FROM profit_order_packages WHERE tenant_id=? AND amazon_order_id='ORDER'",BigDecimal.class,tenant)).isEqualByComparingTo("10");
 });}
 @Test void savedQuantityRecipeBlocksPackageRemoval(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Recipe carton");
  var recipes=new PackageRecipes(jdbc,new tools.jackson.databind.ObjectMapper(),names,new ProfitCostRepository(jdbc));var preview=recipes.preview(tenant,connection,"SKU","SKU",1);recipes.save(tenant,connection,"SKU","SKU",1,preview.compositionKey(),0,List.of("Recipe carton"),"tester");
  assertThat(names.removal(tenant,"Recipe carton").dependencies()).anyMatch(d->d.category().equals("Saved packaging matches")&&d.count()==1);
 });}
 @Test void staleDimensionsRollBackRateAndAuditTogether(){
  String actor="atomic-"+tenant+"@example.invalid";var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);
  tx.executeWithoutResult(s->{jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Package editor','ACTIVE')",actor);names.registerCustom(tenant,"Atomic package");names.saveRate(tenant,"Atomic package",java.time.LocalDate.of(2025,1,1),new BigDecimal("10"),actor);names.saveDimensions(tenant,"Atomic package",new com.nextaicommerce.platform.orders.PackingPackageNames.Dimensions(BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,"inches",0),actor);});
  assertThatThrownBy(()->tx.executeWithoutResult(s->names.saveDetails(tenant,new com.nextaicommerce.platform.orders.PackingPackageNames.DetailsChange("Atomic package",new com.nextaicommerce.platform.orders.PackingPackageNames.Dimensions(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"inches",0),true,java.time.LocalDate.of(2025,1,1),BigDecimal.TEN,"PACKAGE",java.time.LocalDate.of(2026,1,1),new BigDecimal("12")),actor))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dimensions changed");
  tx.executeWithoutResult(s->{var history=names.history(tenant,"Atomic package",new ProfitCostRepository(jdbc).rates(tenant),java.time.LocalDate.now());assertThat(history.rates()).hasSize(1);assertThat(history.baseline().cost()).isEqualByComparingTo("10");assertThat(history.corrections()).isEmpty();assertThat(history.baseline().dimensions().length()).isEqualByComparingTo("10");});
 }
 @Test void packageRowCorrectsUsedEffectiveDateAndKeepsAuditAndExplicitOrderCost(){tx.executeWithoutResult(s->{
  String actor="package-"+tenant+"@example.invalid";jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Package editor','ACTIVE')",actor);
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Editable package");names.saveRate(tenant,"Editable package",java.time.LocalDate.of(2025,1,1),new BigDecimal("10"),actor);
  repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Editable package",new BigDecimal("10"))),actor);
  names.saveDetails(tenant,new com.nextaicommerce.platform.orders.PackingPackageNames.DetailsChange("Editable package",new com.nextaicommerce.platform.orders.PackingPackageNames.Dimensions(new BigDecimal("10"),new BigDecimal("5"),new BigDecimal("2"),"inches",0),true,java.time.LocalDate.of(2025,1,1),new BigDecimal("10"),"PACKAGE",java.time.LocalDate.of(2026,1,2),new BigDecimal("12")),actor);
  var history=names.history(tenant,"Editable package",new ProfitCostRepository(jdbc).rates(tenant),java.time.LocalDate.now());assertThat(history.rates()).hasSize(1);assertThat(history.rates().getFirst().effectiveFrom()).isEqualTo(java.time.LocalDate.of(2026,1,2));assertThat(history.corrections()).hasSize(1);assertThat(history.corrections().getFirst().oldDate()).isEqualTo("2025-01-01");
  assertThat(history.baseline().dimensions().length()).isEqualByComparingTo("10");assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().shippingCost()).isEqualByComparingTo("10");
  assertThatThrownBy(()->names.saveDetails(tenant,new com.nextaicommerce.platform.orders.PackingPackageNames.DetailsChange("Editable package",null,true,java.time.LocalDate.of(2025,1,1),new BigDecimal("10"),"PACKAGE",java.time.LocalDate.of(2026,2,1),new BigDecimal("15")),actor)).hasMessageContaining("rate changed");
 });}
 @Test void inheritedDateEditCreatesPackageSpecificRateWithoutChangingCarrierSchedule(){tx.executeWithoutResult(s->{
  String actor="package-"+tenant+"@example.invalid";jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Package editor','ACTIVE')",actor);managedProfile("Pak","FedEx");var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var costs=new ProfitCostRepository(jdbc);var before=costs.rates(tenant);var choice=names.choices(tenant,before,java.time.LocalDate.now()).stream().filter(c->c.name().equals("Pak")).findFirst().orElseThrow();
  names.saveDetails(tenant,new com.nextaicommerce.platform.orders.PackingPackageNames.DetailsChange("Pak",null,true,choice.effectiveFrom(),choice.cost(),choice.rateSource(),java.time.LocalDate.of(2026,1,1),new BigDecimal("14")),actor);
  assertThat(costs.rates(tenant)).isEqualTo(before);assertThat(names.history(tenant,"Pak",before,java.time.LocalDate.now()).baseline().cost()).isEqualByComparingTo("14");
 });}
 @Test void exactAccountRecipeWorksBeforeOrdersAndReusesAcrossStoresWithoutScaling(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"02. PAK");names.saveRate(tenant,"02. PAK",java.time.LocalDate.of(2025,1,1),new BigDecimal("12"),"tester");
  var recipes=new PackageRecipes(jdbc,new tools.jackson.databind.ObjectMapper(),names,new ProfitCostRepository(jdbc));
  var first=recipes.preview(tenant,connection,"SKU","SKU",1);assertThat(first.contents().getFirst().eaches()).isEqualByComparingTo("2");
  var saved=recipes.save(tenant,connection,"SKU","SKU",1,first.compositionKey(),0,List.of("02. PAK","02. PAK"),"tester");assertThat(saved.revision()).isEqualTo(1);
  assertThat(recipes.packages(tenant,connection,"SKU",List.of("SKU"),null).get("SKU")).hasSize(2);
  assertThat(recipes.preview(tenant,connection,"SKU","SKU",2).packages()).isEmpty();
  // One order has three SKU units; it must not inherit a one-unit recipe.
  assertThat(recipes.packages(tenant,connection,"ORDER",List.of("ORDER"),null)).isEmpty();
  UUID otherStore=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','other','ATVPDKIKX0DER','test','ACTIVE','Other','UTC',now())",otherStore,tenant);
  UUID item=first.contents().getFirst().item();
  jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel) VALUES (?,?,'ATVPDKIKX0DER','DIFFERENT-SKU',20,'USD','MFN')",tenant,otherStore);
  jdbc.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,quantity_per_marketplace_unit) VALUES (?,?,?,'DIFFERENT-SKU',2)",tenant,otherStore,item);
  assertThat(recipes.preview(tenant,otherStore,"SKU","DIFFERENT-SKU",1).packages()).containsExactly("02. PAK","02. PAK");
  repo.configureRecipes(recipes);assertThat(repo.skus(tenant,otherStore,List.of("DIFFERENT-SKU")).get("DIFFERENT-SKU").totals().shippingCost()).isEqualByComparingTo("24");
  assertThatThrownBy(()->recipes.preview(UUID.randomUUID(),otherStore,"SKU","DIFFERENT-SKU",1)).isInstanceOf(IllegalArgumentException.class);
 });}
 @Test void amazonRecipeMatchesShopifyVariantAndFrozenOrderProductsAcrossSkuNames(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Account box");names.saveRate(tenant,"Account box",java.time.LocalDate.of(2025,1,1),new BigDecimal("13"),"tester");
  var recipes=new PackageRecipes(jdbc,new tools.jackson.databind.ObjectMapper(),names,new ProfitCostRepository(jdbc));
  var basis=recipes.preview(tenant,connection,"SKU","SKU",2);UUID item=basis.contents().getFirst().item();recipes.save(tenant,connection,"SKU","SKU",2,basis.compositionKey(),0,List.of("Account box"),"tester");
  UUID shop=UUID.randomUUID(),run=UUID.randomUUID(),job=UUID.randomUUID(),doc=UUID.randomUUID(),product=UUID.randomUUID(),variant=UUID.randomUUID(),order=UUID.randomUUID(),line=UUID.randomUUID();String hash="a".repeat(64);
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'SHOPIFY','fixture.myshopify.com','fixture.myshopify.com','test','ACTIVE','Shop','UTC',now())",shop,tenant);
  jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES (?,?,?,'INCREMENTAL','SHOPIFY_CATALOG','COMPLETED',now(),now())",run,tenant,shop);
  jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number) VALUES (?,?,?,?,'SHOPIFY_CATALOG_VARIANTS',1)",job,tenant,shop,run);
  jdbc.update("INSERT INTO shopify_source_documents(id,tenant_id,marketplace_connection_id,sync_job_id,api_version,query_revision,sha256,response_body) VALUES (?,?,?,?,'2026-01','fixture',?,'{}')",doc,tenant,shop,job,hash);
  jdbc.update("INSERT INTO shopify_products(id,tenant_id,marketplace_connection_id,product_gid,title,product_status,source_updated_at,source_document_id,observation_hash) VALUES (?,?,?,'gid://shopify/Product/1','Product','ACTIVE',now(),?,?)",product,tenant,shop,doc,hash);
  jdbc.update("INSERT INTO shopify_variants(id,tenant_id,marketplace_connection_id,product_id,variant_gid,inventory_item_gid,seller_sku,title,barcodes_complete,price,currency,source_updated_at,source_document_id,last_seen_run,observation_hash,mapping_hash) VALUES (?,?,?,?,'gid://shopify/ProductVariant/1','gid://shopify/InventoryItem/1','SHOP-SKU','Pack of four',true,30,'USD',now(),?,?,?,?)",variant,tenant,shop,product,doc,run,hash,hash);
  jdbc.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,shopify_variant_id,shopify_mapping_hash,quantity_per_marketplace_unit) VALUES (?,?,?,?,?,4)",tenant,shop,item,variant,hash);
  assertThat(recipes.preview(tenant,shop,"SHOPIFY_SKU",variant.toString(),1).packages()).containsExactly("Account box");
  var shopProfit=new ShopifyOrderProfitRepository(jdbc,new ProfitCostRepository(jdbc));shopProfit.configureRecipes(recipes);assertThat(shopProfit.skus(tenant,shop,List.of(variant.toString())).get(variant.toString()).totals().shippingCost()).isEqualByComparingTo("13");
  jdbc.update("INSERT INTO shopify_orders(id,tenant_id,marketplace_connection_id,order_gid,order_name,source_created_at,source_updated_at,is_test,payment_status,fulfillment_status,currency,evidence_complete,observation_hash,source_document_id) VALUES (?,?,?,'gid://shopify/Order/1','#1',now(),now(),false,'PAID','UNFULFILLED','USD',true,?,?)",order,tenant,shop,hash,doc);
  jdbc.update("INSERT INTO shopify_order_lines(id,tenant_id,marketplace_connection_id,order_id,line_gid,variant_gid,variant_id,seller_sku,title,ordered_quantity,current_quantity,unfulfilled_quantity,requires_shipping,ownership,source_updated_at,observation_hash,line_total,currency) VALUES (?,?,?,?,'gid://shopify/LineItem/1','gid://shopify/ProductVariant/1',?,'SHOP-SKU','Pack',1,1,1,true,'MERCHANT',now(),?,30,'USD')",line,tenant,shop,order,variant,hash);
  jdbc.update("INSERT INTO shopify_order_components(tenant_id,marketplace_connection_id,order_line_id,account_catalog_item_id,quantity) VALUES (?,?,?,?,4)",tenant,shop,line,item);
  jdbc.update("UPDATE marketplace_sku_mappings SET quantity_per_marketplace_unit=9 WHERE tenant_id=? AND marketplace_connection_id=?",tenant,shop);
  assertThat(recipes.preview(tenant,shop,"SHOPIFY_SKU",variant.toString(),1).packages()).isEmpty();
  assertThat(recipes.preview(tenant,shop,"SHOPIFY_ORDER",order.toString(),1).packages()).containsExactly("Account box");
  assertThat(shopProfit.orders(tenant,shop,List.of(order.toString())).get(order.toString()).totals().shippingCost()).isEqualByComparingTo("13");
 });}
 @Test void compositeRecipeRequiresEveryProductAndEveryOrderLine(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Bundle box");var recipes=new PackageRecipes(jdbc,new tools.jackson.databind.ObjectMapper(),names,new ProfitCostRepository(jdbc));
  UUID first=jdbc.queryForObject("SELECT account_catalog_item_id FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_sku='SKU'",UUID.class,tenant);
  UUID product=jdbc.queryForObject("INSERT INTO global_catalog_products(canonical_name) VALUES ('Second product') RETURNING id",UUID.class);
  UUID second=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'SECOND') RETURNING id",UUID.class,tenant,product);
  for(UUID item:List.of(first,second))jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) SELECT tenant_id,id,?,2 FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_sku='SKU'",item,tenant);
  var match=recipes.preview(tenant,connection,"SKU","SKU",3);assertThat(match.contents()).hasSize(2).allSatisfy(c->assertThat(c.eaches()).isEqualByComparingTo("6"));
  recipes.save(tenant,connection,"SKU","SKU",3,match.compositionKey(),0,List.of("Bundle box","Bundle box"),"tester");assertThat(recipes.suggestedNames(tenant,connection,"ORDER","ORDER")).containsExactly("Bundle box","Bundle box");
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,'ORDER','UNMAPPED','UNKNOWN',1)",tenant,connection);
  assertThat(recipes.suggestedNames(tenant,connection,"ORDER","ORDER")).isEmpty();
 });}
 @Test void recipeRowsEnforceTenantIsolationForOrdinaryRole(){
  String role="recipe_rls_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
  try{jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON account_package_recipes TO "+role);
   tx.executeWithoutResult(s->{jdbc.update("INSERT INTO account_package_recipes(tenant_id,composition_key,composition,packages,updated_by) VALUES (?,?,'{}','[\"Package\"]','tester')",tenant,"a".repeat(64));jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM account_package_recipes",Integer.class)).isZero();});
  }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
 }
 @Test void packagingRecipeCorrectionsRequireCurrentMappingAndRevisionAndFollowRenames(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Custom package");
  var recipes=new PackageRecipes(jdbc,new tools.jackson.databind.ObjectMapper(),names,new ProfitCostRepository(jdbc));
  var before=recipes.preview(tenant,connection,"SKU","SKU",1);
  var saved=recipes.save(tenant,connection,"SKU","SKU",1,before.compositionKey(),0,List.of("Custom package"),"tester");
  repo.configureRecipes(recipes);assertThat(recipes.packages(tenant,connection,"SKU",List.of("SKU"),null)).containsKey("SKU");
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals().shippingCost()).isNull();
  assertThatThrownBy(()->recipes.save(tenant,connection,"SKU","SKU",1,before.compositionKey(),0,List.of("Custom package"),"stale")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another window");
  var rename=names.preview(tenant,"Custom package","Renamed package");names.apply(tenant,rename.source(),rename.destination(),rename.revision(),rename.affectedAssignments(),"tester");
  assertThat(recipes.preview(tenant,connection,"SKU","SKU",1).packages()).containsExactly("Renamed package");
  assertThat(recipes.save(tenant,connection,"SKU","SKU",1,saved.compositionKey(),1,List.of("Renamed package","Renamed package"),"tester").revision()).isEqualTo(2);
  jdbc.update("UPDATE marketplace_sku_mappings SET quantity_per_marketplace_unit=3 WHERE tenant_id=? AND marketplace_connection_id=?",tenant,connection);
  assertThatThrownBy(()->recipes.save(tenant,connection,"SKU","SKU",1,before.compositionKey(),2,List.of("Renamed package"),"tester")).hasMessageContaining("mapping changed");
  assertThat(recipes.preview(tenant,connection,"SKU","SKU",1).packages()).isEmpty();
 });}
 @Test void orderPackageChoiceTeachesExactCompositionButDoesNotRewriteExplicitOrderOrInventory(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Order package");names.saveRate(tenant,"Order package",java.time.LocalDate.of(2025,1,1),new BigDecimal("9"),"tester");
  var recipes=new PackageRecipes(jdbc,new tools.jackson.databind.ObjectMapper(),names,new ProfitCostRepository(jdbc));
  int ledger=jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Integer.class,tenant);
  recipes.recordChoice(tenant,connection,"ORDER","ORDER",List.of("Order package"),"tester");
  assertThat(recipes.preview(tenant,connection,"SKU","SKU",3).packages()).containsExactly("Order package");
  repo.configureRecipes(recipes);assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().shippingCost()).isEqualByComparingTo("9");
  repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Fixed override",new BigDecimal("18"))),"tester");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().shippingCost()).isEqualByComparingTo("18");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(ledger);
 });}
 @Test void independentRatesHaveDateBoundariesAndDoNotChangeSharedCarrier(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);
  names.saveRate(tenant,"Pak",java.time.LocalDate.of(2025,6,1),new BigDecimal("15.00"),"tester");
  names.saveRate(tenant,"Pak",java.time.LocalDate.of(2025,7,1),new BigDecimal("16.00"),"tester");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").packages().getFirst().amount()).isEqualByComparingTo("15.00");
  var costs=new ProfitCostRepository(jdbc);var batch=costs.packageRates(tenant,List.of("Pak","Medium"));
  assertThat(batch.cost("Pak",java.time.LocalDate.of(2025,5,31),new BigDecimal("12.08"))).isEqualByComparingTo("12.08");
  assertThat(batch.cost("Pak",java.time.LocalDate.of(2025,7,1),null)).isEqualByComparingTo("16.00");
  assertThat(ProfitShippingRates.cost(costs.rates(tenant),ProfitShippingRates.PackageType.FEDEX_PAK,java.time.LocalDate.of(2025,7,1))).isEqualByComparingTo("12.08");
  assertThat(jdbc.queryForObject("SELECT packaging FROM temporary_order_packaging_lookup WHERE tenant_id=?",String.class,tenant)).isEqualTo("Pak");
 });}
 @Test void customNamesAreUnpricedEvenWithCarrierWordsAndRenameKeepsTheirRate(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"UPS custom padded");
  var costs=new ProfitCostRepository(jdbc);var date=java.time.LocalDate.of(2025,6,1);
  assertThat(names.choices(tenant,costs.rates(tenant),date).stream().filter(c->c.name().equals("UPS custom padded")).findFirst().orElseThrow().cost()).isNull();
  names.saveRate(tenant,"UPS custom padded",date,new BigDecimal("5.50"),"tester");
  var p=names.preview(tenant,"UPS custom padded","Any description");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(names.history(tenant,"Any description",costs.rates(tenant),date).rates().getFirst().amount()).isEqualByComparingTo("5.50");
  names.registerCustom(tenant,"Pak");names.saveRate(tenant,"Pak",date,new BigDecimal("6.50"),"tester");var p2=names.preview(tenant,"Any description","Pak");names.apply(tenant,p2.source(),p2.destination(),p2.revision(),p2.affectedAssignments(),"tester");
  assertThat(names.history(tenant,"Pak",costs.rates(tenant),date).rates().getFirst().amount()).isEqualByComparingTo("6.50");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_rates WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
 });}
 @Test void datedRateRenameKeepsOrderProfitAndMergeUsesDestinationDatedRate(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var date=java.time.LocalDate.of(2025,6,1);
  names.saveRate(tenant,"Pak",date,new BigDecimal("6.00"),"tester");names.saveRate(tenant,"UPS OMBox",date,new BigDecimal("8.00"),"tester");
  var before=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");assertThat(before.packages().getFirst().amount()).isEqualByComparingTo("6.00");
  var p=names.preview(tenant,"Pak","My own label");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().profit()).isEqualByComparingTo(before.totals().profit());
  p=names.preview(tenant,"My own label","UPS OMBox");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").packages().getFirst().amount()).isEqualByComparingTo("8.00");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().profit()).isEqualByComparingTo(before.totals().profit().subtract(new BigDecimal("2.00")));
 });}
 @Test void independentRateValidationAndManualOverridesRemain(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var date=java.time.LocalDate.of(2025,6,1);
  assertThatThrownBy(()->names.saveRate(tenant,"Pak",null,BigDecimal.ONE,"tester")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->names.saveRate(tenant,"Pak",date,new BigDecimal("1.001"),"tester")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->names.saveRate(tenant,"foreign",date,BigDecimal.ONE,"tester")).isInstanceOf(IllegalArgumentException.class);
  names.saveRate(tenant,"Pak",date,new BigDecimal("5.50"),"tester");
  assertThatThrownBy(()->names.saveRate(tenant,"Pak",date,new BigDecimal("7.50"),"tester")).isInstanceOf(IllegalArgumentException.class);
  repo.savePackages(tenant,connection,"ORDER",List.of(new ProfitRepository.PackageCost("Manual",new BigDecimal("3.00"))),"tester");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").packages().getFirst().amount()).isEqualByComparingTo("3.00");
 });}
 @Test void independentRatesEnforceTenantRls(){
  String role="rates_rls_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
  try{jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT,INSERT ON packing_package_rates TO "+role);
   tx.executeWithoutResult(s->{var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Pak");names.saveRate(tenant,"Pak",java.time.LocalDate.of(2025,6,1),BigDecimal.ONE,"tester");jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_rates",Integer.class)).isZero();});
  }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
 }
 private void managedProfile(String name,String carrier){jdbc.update("INSERT INTO shipping_package_profiles(tenant_id,marketplace_connection_id,name,length,width,height,dimension_unit,weight,weight_unit,preferred_carrier) VALUES (?,?,?,1,1,1,'inches',1,'oz',?)",tenant,connection,name,carrier);}
 private void packageFixture(){managedProfile("UPS OMBox","UPS");new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc).registerCustom(tenant,"Large");jdbc.update("INSERT INTO temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,amazon_order_id,order_item_summary,packaging,order_sku_qty_list) VALUES (?,?,'ORDER','ASIN-3','Pak','SKU-3')",tenant,connection);}
 @Test void packageRenamePreservesProfitAndRawHistory(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);
  var before=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");
  var preview=names.preview(tenant,"Pak","My insulated bag");assertThat(preview.affectedAssignments()).isEqualTo(1);
  names.apply(tenant,preview.source(),preview.destination(),preview.revision(),preview.affectedAssignments(),"tester");
  var after=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");
  assertThat(after.totals().profit()).isEqualByComparingTo(before.totals().profit());
  assertThat(after.packages().getFirst().description()).isEqualTo("My insulated bag");assertThat(after.packages().getFirst().amount()).isEqualByComparingTo(before.packages().getFirst().amount());assertThat(names.names(tenant)).contains("My insulated bag").doesNotContain("Pak");
  assertThat(jdbc.queryForObject("SELECT packaging FROM temporary_order_packaging_lookup WHERE tenant_id=?",String.class,tenant)).isEqualTo("Pak");
 });}
 @Test void packageMergeUsesDestinationCostAndLeavesSourceOrdersUntouched(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);
  var before=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");
  var preview=names.preview(tenant,"Pak","UPS OMBox");assertThat(preview.operation()).isEqualTo("MERGE");
  names.apply(tenant,preview.source(),preview.destination(),preview.revision(),preview.affectedAssignments(),"tester");
  var after=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");
  assertThat(after.packages().getFirst().amount()).isEqualByComparingTo("14.20");
  assertThat(after.totals().profit()).isEqualByComparingTo(before.totals().profit().subtract(new BigDecimal("2.12")));
  assertThat(jdbc.queryForObject("SELECT packaging FROM temporary_order_packaging_lookup WHERE tenant_id=?",String.class,tenant)).isEqualTo("Pak");
  assertThat(names.names(tenant)).doesNotContain("Pak");
 });}
 @Test void capitalizationOnlyRenamePreservesCostsAndAssignments(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var before=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");
  var p=names.preview(tenant,"Pak","PAK");assertThat(p.operation()).isEqualTo("RENAME");assertThat(p.destination()).isEqualTo("PAK");
  names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(names.names(tenant)).contains("PAK").doesNotContain("Pak");
  assertThat(jdbc.queryForObject("SELECT packaging FROM temporary_order_packaging_lookup WHERE tenant_id=?",String.class,tenant)).isEqualTo("Pak");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().profit()).isEqualByComparingTo(before.totals().profit());
  assertThatThrownBy(()->names.preview(tenant,"PAK","PAK")).hasMessageContaining("different package name");
 });}
 @Test void equalDestinationRateLeavesProfitUnchanged(){tx.executeWithoutResult(s->{
  packageFixture();jdbc.update("INSERT INTO profit_shipping_rates(tenant_id,package_type,effective_from,amount,created_by) VALUES (?,'UPS','2025-01-01',12.08,'tester')",tenant);
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var before=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");
  var p=names.preview(tenant,"Pak","UPS OMBox");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals().profit()).isEqualByComparingTo(before.totals().profit());
 });}
 @Test void stalePackageConfirmationAndForeignAccountCannotApply(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var p=names.preview(tenant,"Pak","Bag");
  var other=names.preview(tenant,"Large","Large box");names.apply(tenant,other.source(),other.destination(),other.revision(),other.affectedAssignments(),"tester");
  assertThatThrownBy(()->names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester")).isInstanceOf(IllegalArgumentException.class);
  UUID foreign=UUID.randomUUID();assertThat(jdbc.queryForObject("SELECT packing_package_display(?,?)",String.class,foreign,"Large")).isEqualTo("Large");
 });}
 @Test void chainedRenameThenMergeStillRoutesOriginalNames(){tx.executeWithoutResult(s->{
  packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var p=names.preview(tenant,"Pak","Bag");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  p=names.preview(tenant,"Bag","UPS OMBox");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(jdbc.queryForObject("SELECT packing_package_cost(?,?)",String.class,tenant,"Pak")).isEqualTo("UPS OMBox");
  assertThat(names.names(tenant)).doesNotContain("Pak","Bag");
 });}

 @Test void mergeUpdatesDerivedSkuCostDespitePreviousPackageTypeSelection(){tx.executeWithoutResult(s->{
  packageFixture();jdbc.update("UPDATE temporary_order_packaging_lookup SET order_sku_qty_list='SKU-1' WHERE tenant_id=?",tenant);
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var p=names.preview(tenant,"Pak","UPS OMBox");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").packages().getFirst().amount()).isEqualByComparingTo("14.20");
 });}
 @Test void packageAliasesEnforceRowLevelIsolationForOrdinaryRole(){
  String role="packing_rls_"+UUID.randomUUID().toString().replace("-","");
  jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
  try{jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON packing_package_aliases,packing_package_name_events TO "+role);
   tx.executeWithoutResult(s->{packageFixture();var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);var p=names.preview(tenant,"Pak","Bag");names.apply(tenant,p.source(),p.destination(),p.revision(),p.affectedAssignments(),"tester");
    jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_aliases",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_name_events",Integer.class)).isZero();
   });
  }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
 }
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
 @Test void fbaAndForeignCurrencyNeverShowIncompleteProfit(){tx.executeWithoutResult(s->{jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AFN' WHERE tenant_id=?",tenant);assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals().profit()).isNull();jdbc.update("UPDATE amazon_listings SET fulfillment_channel='MFN',currency='CAD' WHERE tenant_id=?",tenant);assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals()).isNull();});}
 @Test void missingProductCostIsNotSilentlyZero(){tx.executeWithoutResult(s->{jdbc.update("DELETE FROM vendor_catalog_offers WHERE tenant_id=?",tenant);assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").totals().profit()).isNull();});}
 @Test void cancelledOrderDoesNotShowSalesProfit(){tx.executeWithoutResult(s->{jdbc.update("UPDATE amazon_orders SET order_status='Canceled' WHERE tenant_id=?",tenant);assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").totals()).isNull();});}
 @Test void missingListingCurrencyUsesKnownMarketplaceDefinition(){tx.executeWithoutResult(s->{jdbc.update("UPDATE amazon_listings SET currency=NULL WHERE tenant_id=?",tenant);var view=repo.skus(tenant,connection,List.of("SKU")).get("SKU");assertThat(view.currency()).isEqualTo("USD");assertThat(view.totals().profit()).isEqualByComparingTo("2.01");});}
 @Test void importedFbaFeesUseExactSkuAndOrderQuantity(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AMAZON_NA' WHERE tenant_id=?",tenant);
  jdbc.update("UPDATE amazon_orders SET fulfillment_channel='AFN' WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at) VALUES (?,?,'ATVPDKIKX0DER','SKU',5.25,2.17,'USD',now())",tenant,connection);
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at) VALUES (?,?,'ATVPDKIKX0DER','SKU',100,40,'MXN',now()+interval '1 second')",tenant,connection);
  var sku=repo.skus(tenant,connection,List.of("SKU")).get("SKU");assertThat(sku.fba()).isTrue();assertThat(sku.fbaFee()).isEqualByComparingTo("5.25");assertThat(sku.packages()).isEmpty();assertThat(sku.inboundShippingCost()).isEqualByComparingTo("1");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").fbaFee()).isEqualByComparingTo("15.75");
  assertThat(sku.fbaReferralFee()).isEqualByComparingTo("2.17");
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").fbaReferralFee()).isEqualByComparingTo("6.51");
  assertThat(repo.skus(tenant,UUID.randomUUID(),List.of("SKU"))).isEmpty();
 });}
 @Test void laterPerSkuQuoteCannotReplaceAnOlderOrdersLegacyEstimate(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AMAZON_NA' WHERE tenant_id=?",tenant);jdbc.update("UPDATE amazon_orders SET fulfillment_channel='AFN' WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at,raw_payload) VALUES (?,?,'ATVPDKIKX0DER','SKU',5,3,'USD','2025-05-01','{}')",tenant,connection);
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").fbaFee()).isEqualByComparingTo("15");
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at,raw_payload) VALUES (?,?,'ATVPDKIKX0DER','SKU',9,6,'USD',now(),'{\"_platformFeeContract\":\"FINAL_FEE_V1\",\"FeesEstimateIdentifier\":{\"IdType\":\"SellerSKU\",\"IdValue\":\"SKU\"}}')",tenant,connection);
  var old=repo.orders(tenant,connection,List.of("ORDER")).get("ORDER");assertThat(old.fbaFee()).isEqualByComparingTo("15");
  assertThat(old.feeSource()).contains("Current report estimate","historical FBA basis unavailable","historical referral basis unavailable");
  assertThat(repo.skus(tenant,connection,List.of("SKU")).get("SKU").fbaFee()).isEqualByComparingTo("9");
 });}
 @Test void laterQuoteWithoutAnyHistoricalBasisRemainsUnavailable(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_orders SET fulfillment_channel='AFN' WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at,raw_payload) VALUES (?,?,'ATVPDKIKX0DER','SKU',9,6,'USD',now(),'{\"FeesEstimateIdentifier\":{\"IdType\":\"SellerSKU\",\"IdValue\":\"SKU\"}}')",tenant,connection);
  assertThat(repo.orders(tenant,connection,List.of("ORDER")).get("ORDER").fbaFee()).isNull();
 });}
 @Test void fbaUsesOneCombinedInboundFallbackAndExcludesFbmOtherCost(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AFN',other_cost_per_sku=9 WHERE tenant_id=?",tenant);
  var missing=repo.skus(tenant,connection,List.of("SKU")).get("SKU");
  assertThat(missing.inboundShippingCost().add(missing.placementFee())).isEqualByComparingTo("1");
  assertThat(missing.totals().otherCost()).isEqualByComparingTo("0");
  assertThat(missing.totals().profit()).isNull();
  assertThat(missing.fbaFee()).isNull();
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at) VALUES (?,?,'ATVPDKIKX0DER','SKU',5.25,2.17,'USD',now())",tenant,connection);
  var quoted=repo.skus(tenant,connection,List.of("SKU")).get("SKU");
  assertThat(quoted.totals().profit()).isEqualByComparingTo("7.58");
  assertThat(quoted.inboundSource()).contains("$1.00 estimated per SKU unit");
 });}
 @Test void latestCompletePostedShipmentCostsRemainExplicitEstimates(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AFN' WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_fee,referral_fee,currency,effective_at) VALUES (?,?,'ATVPDKIKX0DER','SKU',5.25,2.17,'USD',now())",tenant,connection);
  for(String shipment:List.of("FBA-OLD","FBA-NEW")){
   jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_status) VALUES (?,?,?,'CLOSED')",tenant,connection,shipment);
   jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped) VALUES (?,?,?,'SKU',10),(?,?,?,'OTHER',10)",tenant,connection,shipment,tenant,connection,shipment);
   for(String description:List.of("FBAPostInboundTransportation","FBAInboundConvenience"))jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,description,amount,currency,posted_date,raw_payload) VALUES (?,?,?,'ServiceFee',?,?,-20,'USD',?::timestamptz,'{\"transactionStatus\":\"RELEASED\"}')",tenant,connection,shipment+description,shipment,description,shipment.equals("FBA-NEW")?"2026-10-10T00:00:00Z":"2026-09-01T00:00:00Z");
  }
  var view=repo.skus(tenant,connection,List.of("SKU")).get("SKU");assertThat(view.inboundShippingCost()).isEqualByComparingTo("1");assertThat(view.placementFee()).isEqualByComparingTo("1");assertThat(view.note()).contains("FBA-NEW").doesNotContain("FBA-OLD");
  assertThat(view.totals().profit()).isEqualByComparingTo("6.58");
  jdbc.update("DELETE FROM amazon_financial_transactions WHERE tenant_id=? AND transaction_key='FBA-NEWFBAInboundConvenience'",tenant);
  view=repo.skus(tenant,connection,List.of("SKU")).get("SKU");assertThat(view.placementFee()).isEqualByComparingTo("1");assertThat(view.totals().profit()).isEqualByComparingTo("6.58");assertThat(view.note()).contains("FBA-OLD").doesNotContain("FBA-NEW");assertThat(view.inboundSource()).contains("Estimate","2026-08-31");
 });}
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
 @Test void allFeeReadersUseExactMarketplaceCurrencyAndConsistentReportEvidence(){tx.executeWithoutResult(s->{
  jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AFN',currency='' WHERE tenant_id=?",tenant);
  for(var row:List.of(List.of("ATVPDKIKX0DER","USD","USD","5","3","2026-10-01"),
      List.of("OTHER_MARKET","USD","USD","99","99","2026-10-02"),
      List.of("ATVPDKIKX0DER","USD","MXN","88","88","2026-10-03"),
      List.of("ATVPDKIKX0DER","CAD","CAD","77","77","2026-10-04"))){
   jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,fulfillment_fee,referral_fee,estimated_fee_total,effective_at,raw_payload) VALUES (?,?,?,'SKU',?,?::numeric,?::numeric,(?::numeric+?::numeric),?::timestamptz,jsonb_build_object('currency',?::text))",
    tenant,connection,row.get(0),row.get(1),row.get(3),row.get(4),row.get(3),row.get(4),row.get(5),row.get(2));
  }
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,fulfillment_fee,referral_fee,estimated_fee_total,effective_at,raw_payload) VALUES (?,?,'ATVPDKIKX0DER','SKU','USD',66,66,132,'2026-10-05','{\"currency\":\"USD\",\"amazon-store\":\"CA\"}')",tenant,connection);
  var view=repo.skus(tenant,connection,List.of("SKU")).get("SKU");
  assertThat(view.fbaFee()).isEqualByComparingTo("5");assertThat(view.fbaReferralFee()).isEqualByComparingTo("3");assertThat(view.feeSource()).contains("2026-10-01","days since import","freshness not assessed","Fee archive unavailable");
  var stock=new com.nextaicommerce.platform.fba.FbaReadRepository(jdbc).stocks(tenant,connection,List.of("SKU")).get("SKU");
  assertThat(stock.fulfillmentFee()).isEqualByComparingTo("5");
  var listing=new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository(jdbc).list(tenant,connection,"SKU","ALL","status","asc",0,25).rows().getFirst();
  assertThat(listing.estimatedFees()).isEqualByComparingTo("8");assertThat(listing.feeCurrency()).isEqualTo("USD");
  assertThat(repo.skus(UUID.randomUUID(),connection,List.of("SKU"))).isEmpty();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_estimates WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(5);
 });}
 @Test void packageDimensionsFollowRenameAndUseDestinationOnMerge(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);names.registerCustom(tenant,"Carton A");names.registerCustom(tenant,"Carton B");
  names.saveDimensions(tenant,"Carton A",new com.nextaicommerce.platform.orders.PackingPackageNames.Dimensions(new BigDecimal("12"),new BigDecimal("8"),new BigDecimal("6"),"inches",0),"test");
  assertThatThrownBy(()->names.saveDimensions(tenant,"Carton A",new com.nextaicommerce.platform.orders.PackingPackageNames.Dimensions(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"inches",0),"test")).hasMessageContaining("changed");
  names.saveDimensions(tenant,"Carton A",new com.nextaicommerce.platform.orders.PackingPackageNames.Dimensions(new BigDecimal("13"),new BigDecimal("8"),new BigDecimal("6"),"inches",1),"test");
  var review=names.preview(tenant,"Carton A","Insulated carton");names.apply(tenant,review.source(),review.destination(),review.revision(),review.affectedAssignments(),"test");
  var choices=names.choices(tenant,new ProfitCostRepository(jdbc).rates(tenant),java.time.LocalDate.now());assertThat(choices.stream().filter(c->c.name().equals("Insulated carton")).findFirst().orElseThrow().dimensions().length()).isEqualByComparingTo("13");
  var merge=names.preview(tenant,"Insulated carton","Carton B");names.apply(tenant,merge.source(),merge.destination(),merge.revision(),merge.affectedAssignments(),"test");
  assertThat(names.choices(tenant,new ProfitCostRepository(jdbc).rates(tenant),java.time.LocalDate.now()).stream().filter(c->c.name().equals("Carton B")).findFirst().orElseThrow().dimensions()).isNull();
 });}
 @Test void accountWithoutPackageDefinitionsHasNoInventedChoices(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);
  assertThat(names.choices(tenant,new ProfitCostRepository(jdbc).rates(tenant),java.time.LocalDate.of(2026,11,9))).isEmpty();
 });}
 @Test void packageManagementCostUsesAliasIdentityAndDatedAccountPlanningRate(){tx.executeWithoutResult(s->{
  var names=new com.nextaicommerce.platform.orders.PackingPackageNames(jdbc);
  var rates=new ProfitCostRepository(jdbc).rates(tenant);
  var date=java.time.LocalDate.of(2026,11,9);
  managedProfile("Pak","FedEx");
  var initial=names.choices(tenant,rates,date).stream().filter(c->c.name().equals("Pak")).findFirst().orElseThrow();
  assertThat(initial.cost()).isEqualByComparingTo("12.08");assertThat(initial.currency()).isEqualTo("USD");assertThat(initial.date()).isEqualTo(date);
  var review=names.preview(tenant,"Pak","Insulated bag");names.apply(tenant,review.source(),review.destination(),review.revision(),review.affectedAssignments(),"test");
  var renamed=names.choices(tenant,rates,date).stream().filter(c->c.name().equals("Insulated bag")).findFirst().orElseThrow();assertThat(renamed.cost()).isEqualByComparingTo("12.08");
  assertThat(names.choices(UUID.randomUUID(),rates,date).stream().map(c->c.name())).doesNotContain("Insulated bag");
 });}

}
