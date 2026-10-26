package com.nextaicommerce.platform.catalog;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class AccountCatalogSelectionDatabaseTest {
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
 static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
 CatalogRepository repo;UUID tenant,other,user,vendor,foreignVendor,global;String actor;
 @BeforeAll static void setup()throws Exception{
  schema="catalog_selection_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
  if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=Files.readString(Path.of(".local/database-password")).trim();}
  else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  jdbc.execute("ALTER FUNCTION "+schema+".ensure_item_default_location() SET search_path TO "+schema);
 }
 @AfterAll static void cleanup()throws Exception{if(jdbc!=null&&schema.matches("catalog_selection_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(postgres!=null)postgres.close();}
 @BeforeEach void fixture(){
  repo=new CatalogRepository(jdbc);tenant=account();other=account();user=UUID.randomUUID();actor=user+"@example.test";
  jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Selection tester')",user,actor);
  vendor=vendor(tenant,"41");foreignVendor=vendor(other,"19");global=product("Selection cheese "+tenant,"079834140503");
 }
 UUID account(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Selection account')",id,id.toString());return id;}
 UUID vendor(UUID account,String dc){return jdbc.queryForObject("INSERT INTO vendors(tenant_id,name,vendor_code,distribution_center,currency) VALUES (?,'KEHE','KEHE',?,'USD') RETURNING id",UUID.class,account,dc);}
 UUID product(String name,String barcode){
  // Each test has its own barcode-independent products; replace the prior fixture's identifier only inside this disposable schema.
  jdbc.update("DELETE FROM global_product_identifiers WHERE identifier_value=?",barcode);
  UUID id=jdbc.queryForObject("INSERT INTO global_catalog_products(canonical_name,brand,package_size) VALUES (?,'Shared brand','12 oz') RETURNING id",UUID.class,name);
  jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,'UPC',?,true)",id,barcode);return id;
 }
 UUID item(UUID account,UUID product,String code){return jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,?) RETURNING id",UUID.class,account,product,code);}
 CatalogRepository.GlobalChoice choice(UUID account,UUID product){return tx.execute(s->repo.searchGlobalProducts(account,"Selection")).stream().filter(x->x.id().equals(product)).findFirst().orElseThrow();}
 CatalogRepository.AccountProductDraft draft(UUID account,UUID product,String code,UUID supplier,String supplierCode,BigDecimal cost){return new CatalogRepository.AccountProductDraft(product,choice(account,product).revision(),"forged shared name","forged shared brand","UPC","850032994026",code,supplier,supplierCode,cost,false);}
 CatalogRepository.AccountAddition add(UUID account,CatalogRepository.AccountProductDraft draft){return tx.execute(s->repo.addProductToAccount(account,actor,draft));}
 String snapshot(String table){return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "+table+" t",String.class);}
 @Test void searchContainsCanonicalFieldsAndOnlyCurrentAccountPresence()throws Exception{
  UUID foreign=item(other,global,"PRIVATE-OTHER");jdbc.update("UPDATE account_catalog_items SET display_name='PRIVATE NAME' WHERE id=?",foreign);
  jdbc.update("INSERT INTO account_catalog_product_images(tenant_id,account_catalog_item_id,image_bytes,image_content_type) VALUES (?,?,?,'image/png')",other,foreign,new byte[]{9});
  var found=choice(tenant,global);assertThat(found.accountItemId()).isNull();assertThat(found.imageUrl()).isNull();
  String json=new tools.jackson.databind.ObjectMapper().writeValueAsString(found);assertThat(json).doesNotContain("PRIVATE",foreign.toString(),"vendor","cost");
  List<CatalogRepository.GlobalChoice> byBarcode=tx.execute(s->repo.searchGlobalProducts(tenant,"0079834140503"));
  List<CatalogRepository.GlobalChoice> byBrand=tx.execute(s->repo.searchGlobalProducts(tenant,"Shared brand"));
  assertThat(byBarcode).extracting(CatalogRepository.GlobalChoice::id).contains(global);
  assertThat(byBrand).isNotEmpty();
 }
 @Test void selectedGlobalLinksIndependentAccountCodesAndVendorOffersWithoutChangingSharedOrOperationalRows(){
  UUID original=item(other,global,"KETO-CODE");
  UUID store=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,display_name,seller_identifier,marketplace_identifier,status,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','Synthetic','seller','ATVPDKIKX0DER','ACTIVE','test','America/Los_Angeles',now())",store,other);
  jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,item_name,listing_status,fulfillment_channel) VALUES (?,?,'ATVPDKIKX0DER','PRIVATE-EXISTING','ASIN','Existing product','ACTIVE','AMAZON')",other,store);
  tx.executeWithoutResult(s->new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository(jdbc).saveMapping(other,store,"PRIVATE-EXISTING",List.of(original.toString()),List.of("3")));
  tx.executeWithoutResult(s->repo.saveVendorOffer(other,actor,original,foreignVendor,"KETO-VENDOR",new BigDecimal("4.50"),BigDecimal.ZERO,"USD"));
  jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,source_type,occurred_at,idempotency_key) SELECT ?,?,location_id,'RECEIPT',12,'MANUAL',now(),? FROM account_catalog_item_locations WHERE tenant_id=? AND account_catalog_item_id=? LIMIT 1",other,original,"selection-"+original,other,original);
  String products=snapshot("global_catalog_products"),ledger=snapshot("inventory_ledger_entries"),maps=snapshot("marketplace_sku_mappings"),components=snapshot("marketplace_sku_mapping_components");
  var oldHistory=jdbc.queryForList("SELECT to_jsonb(h)::text row FROM vendor_cost_history h WHERE tenant_id=? ORDER BY id",other);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sku_mappings",Integer.class)).isPositive();
  var a=add(tenant,draft(tenant,global,"IB-ACCOUNT",vendor,"VENDOR-DIFFERENT",new BigDecimal("2.50")));
  assertThat(a.created()).isTrue();assertThat(a.itemId()).isNotEqualTo(original);
  assertThat(jdbc.queryForMap("SELECT account_sku,global_product_id FROM account_catalog_items WHERE id=?",a.itemId())).containsEntry("account_sku","IB-ACCOUNT").containsEntry("global_product_id",global);
  assertThat(jdbc.queryForObject("SELECT vendor_item_code FROM vendor_catalog_offers WHERE account_catalog_item_id=?",String.class,a.itemId())).isEqualTo("VENDOR-DIFFERENT");
  assertThat(jdbc.queryForObject("SELECT account_sku FROM account_catalog_items WHERE id=?",String.class,original)).isEqualTo("KETO-CODE");
  assertThat(snapshot("global_catalog_products")).isEqualTo(products);assertThat(snapshot("inventory_ledger_entries")).isEqualTo(ledger);assertThat(snapshot("marketplace_sku_mappings")).isEqualTo(maps);
  assertThat(snapshot("marketplace_sku_mapping_components")).isEqualTo(components);
  assertThat(jdbc.queryForList("SELECT to_jsonb(h)::text row FROM vendor_cost_history h WHERE tenant_id=? ORDER BY id",other)).isEqualTo(oldHistory);
 }
 @Test void existingInactiveItemIsReturnedWithoutReactivationCostOrCodeChanges(){
  UUID existing=item(tenant,global,"ORIGINAL");jdbc.update("UPDATE account_catalog_items SET status='INACTIVE' WHERE id=?",existing);
  String before=snapshot("account_catalog_items"),cost=snapshot("vendor_cost_history");
  var a=add(tenant,draft(tenant,global,"REPLACE",vendor,"REPLACE",BigDecimal.TEN));
  assertThat(a.created()).isFalse();assertThat(a.itemId()).isEqualTo(existing);assertThat(snapshot("account_catalog_items")).isEqualTo(before);assertThat(snapshot("vendor_cost_history")).isEqualTo(cost);
  CatalogRepository.AccountItemPage page=tx.execute(s->repo.pageAccountItems(tenant,"079834140503",0,25));
  assertThat(page.rows()).extracting(CatalogRepository.AccountItemView::id).contains(existing);
 }
 @Test void staleGlobalReferenceAndForeignVendorFailAtomically(){
  var stale=draft(tenant,global,"STALE",null,null,null);jdbc.update("UPDATE global_catalog_products SET canonical_name='Changed selection' WHERE id=?",global);
  assertThatThrownBy(()->add(tenant,stale)).hasMessageContaining("changed");
  jdbc.update("UPDATE global_catalog_products SET canonical_name=? WHERE id=?","Selection restored "+tenant,global);
  assertThatThrownBy(()->add(tenant,draft(tenant,global,"FOREIGN",foreignVendor,"FOREIGN",BigDecimal.ONE))).hasMessageContaining("this account");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isZero();
 }
 @Test void accountSkuAndVendorCodeConflictsRejectWithoutCreatingAnItem(){
  UUID different=product("Other product","850032994026"),existing=item(tenant,different,"A-100");
  assertThatThrownBy(()->add(tenant,draft(tenant,global,"a100",null,null,null))).hasMessageContaining("already assigned");
  jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost) VALUES (?,?,?,'PRIVATE-CODE',1)",tenant,vendor,existing);
  assertThatThrownBy(()->add(tenant,draft(tenant,global,"NEW",vendor,"PRIVATE-CODE",BigDecimal.ONE))).hasMessageContaining("different product");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
 }
 @Test void newProductRequiresValidIdentifierAndPreservesExpirationChoice(){
  var invalid=new CatalogRepository.AccountProductDraft(null,null,"New product","Brand","UPC","079834140504","BAD",null,null,null,true);
  assertThatThrownBy(()->add(tenant,invalid)).hasMessageContaining("valid UPC");
  var valid=new CatalogRepository.AccountProductDraft(null,null,"New product","Brand","UPC","036000291452","NEW",null,null,null,true);
  jdbc.update("DELETE FROM global_product_identifiers WHERE identifier_value='036000291452'");var created=add(tenant,valid);assertThat(created.created()).isTrue();
  assertThat(jdbc.queryForObject("SELECT g.requires_expiration_date FROM global_catalog_products g JOIN account_catalog_items a ON a.global_product_id=g.id WHERE a.id=?",Boolean.class,created.itemId())).isTrue();
 }
 @Test void canonicalImageNeverReadsAnotherAccountsUploadedPicture(){
  UUID foreign=item(other,global,"IMAGE");jdbc.update("INSERT INTO account_catalog_product_images(tenant_id,account_catalog_item_id,image_bytes,image_content_type) VALUES (?,?,?,'image/png')",other,foreign,new byte[]{9});
  CatalogRepository.ProductImage foreignImage=tx.execute(s->repo.productImage(tenant,foreign));
  assertThat(repo.sharedGlobalImage(global)).isNull();assertThat(foreignImage).isNull();
  jdbc.update("INSERT INTO global_catalog_product_images(global_product_id,image_bytes,image_content_type,source_asin,source_tenant_id) VALUES (?,?,'image/png','SHARED',?)",global,new byte[]{1,2},other);
  var created=add(tenant,draft(tenant,global,"IMAGE-OWN",null,null,null));assertThat(tx.execute(s->repo.productImage(tenant,created.itemId())).bytes()).containsExactly(1,2);
  assertThat(choice(tenant,global).imageUrl()).contains("/global-products/"+global);
 }
 @Test void concurrentDuplicateSubmissionsCreateOneItemAndOneVendorHistory()throws Exception{
  var input=draft(tenant,global,"CONCURRENT",vendor,"V-ONLY",BigDecimal.ONE);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var work=(Callable<CatalogRepository.AccountAddition>)()->{ready.countDown();start.await();return add(tenant,input);};
   var one=pool.submit(work);var two=pool.submit(work);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
   var a=one.get(10,TimeUnit.SECONDS);var b=two.get(10,TimeUnit.SECONDS);assertThat(a.itemId()).isEqualTo(b.itemId());assertThat(a.created()).isNotEqualTo(b.created());
  }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor_cost_history WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
 }
}
