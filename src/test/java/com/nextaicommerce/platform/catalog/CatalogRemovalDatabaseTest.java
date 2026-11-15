package com.nextaicommerce.platform.catalog;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class CatalogRemovalDatabaseTest {
 static io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg;
 static JdbcTemplate db;static TransactionTemplate tx;static CatalogRemovalService service;
 UUID tenant=UUID.randomUUID(),global=UUID.randomUUID(),item=UUID.randomUUID(),vendor;
 @BeforeAll static void start()throws Exception{
  pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
  var source=new DriverManagerDataSource(pg.getJdbcUrl("postgres","postgres"),"postgres",null);
  org.flywaydb.core.Flyway.configure().dataSource(source).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location("public")).load().migrate();
  db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));service=new CatalogRemovalService(db);
 }
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
 @BeforeEach void seed(){
  db.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?, 'Delete fixture')",tenant,tenant.toString());
  db.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Product')",global);
  db.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,display_name,account_sku) VALUES (?,?,?,'Account product','CODE')",item,tenant,global);
  vendor=db.queryForObject("INSERT INTO vendors(tenant_id,name,currency) VALUES (?,'Vendor','USD') RETURNING id",UUID.class,tenant);
  db.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency) VALUES (?,?,?,'CODE',1,'USD')",tenant,vendor,item);
 }
 CatalogRemovalService.Review review(boolean shared){return tx.execute(s->service.review(tenant,shared?global:item,shared));}
 String remove(boolean shared,String op){return tx.execute(s->service.remove(tenant,shared?global:item,shared,op));}
 @Test void deletionRetainsIdentityAndReleasesSku(){
  assertThat(review(false).operation()).isEqualTo("RETAINED_DELETE");remove(false,"RETAINED_DELETE");
  assertThat(db.queryForObject("SELECT account_sku IS NULL AND deleted_at IS NOT NULL AND status='INACTIVE' FROM account_catalog_items WHERE id=?",Boolean.class,item)).isTrue();
  UUID replacement=db.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'CODE') RETURNING id",UUID.class,tenant,global);
  assertThat(replacement).isNotEqualTo(item);
  assertThat(CatalogIdentity.vendorBindings(db,tenant,vendor,"CODE")).isEmpty();
 }
 @Test void archivedGlobalCanRetireLinkedItemsAndFreeBarcode(){
  db.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,'UPC','855312004709',true)",global);
  db.update("UPDATE global_catalog_products SET status='ARCHIVED' WHERE id=?",global);
  remove(true,"RETAINED_DELETE");
  assertThat(db.queryForObject("SELECT deleted_at IS NOT NULL FROM global_catalog_products WHERE id=?",Boolean.class,global)).isTrue();
  assertThat(db.queryForObject("SELECT count(*) FROM global_product_identifiers WHERE global_product_id=?",Integer.class,global)).isZero();
  assertThat(db.queryForObject("SELECT deleted_at IS NOT NULL FROM account_catalog_items WHERE id=?",Boolean.class,item)).isTrue();
 }
 @Test void staleEditCannotReactivateDeletedProduct(){remove(false,"RETAINED_DELETE");assertThatThrownBy(()->db.update("UPDATE account_catalog_items SET status='ACTIVE',account_sku='CODE' WHERE id=?",item)).hasMessageContaining("deleted");}
 @Test void staleGlobalEditCannotReclaimBarcode(){remove(true,"RETAINED_DELETE");assertThatThrownBy(()->db.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value) VALUES (?,'UPC','855312004709')",global)).hasMessageContaining("deleted");}
 @Test void foreignAccountCannotRemove(){assertThatThrownBy(()->tx.execute(s->service.remove(UUID.randomUUID(),item,false,"RETAINED_DELETE"))).hasMessageContaining("not found");}
 @Test void unknownOperationCannotWrite(){assertThatThrownBy(()->remove(false,"DELETE")).hasMessageContaining("dependencies changed");assertThat(review(false).operation()).isEqualTo("RETAINED_DELETE");}
 @Test void auditCannotBeChanged(){remove(false,"RETAINED_DELETE");assertThatThrownBy(()->db.update("UPDATE account_catalog_items SET deleted_identity='{}'::jsonb WHERE id=?",item)).hasMessageContaining("deleted");}

 UUID location(){return db.queryForObject("SELECT id FROM warehouse_locations WHERE tenant_id=? AND code='MAIN'",UUID.class,tenant);}
 void movement(int quantity,UUID loc){db.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,entry_type,quantity,source_type,occurred_at,idempotency_key,location_id) VALUES (?,?,'ADJUSTMENT',?,'TEST',now(),?,?)",tenant,item,quantity,UUID.randomUUID().toString(),loc);}
 @Test void positiveStockBlocksDeletion(){movement(4,location());assertThat(review(false).operation()).isEqualTo("BLOCKED");assertThatThrownBy(()->remove(false,"RETAINED_DELETE")).hasMessageContaining("Zero out");}
 @Test void activeReservationBlocksDeletionEvenWithoutStock(){
  UUID store=UUID.randomUUID();
  db.update("INSERT INTO marketplace_connections(id,tenant_id,channel,display_name,seller_identifier,marketplace_identifier,status,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','Fixture','seller','ATVPDKIKX0DER','ACTIVE','test','UTC',now())",store,tenant);
  db.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,fulfillment_channel) VALUES (?,?,'ATVPDKIKX0DER','TEST','Unshipped','MFN')",tenant,store);
  UUID line=db.queryForObject("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,'TEST','LINE','CODE',1) RETURNING id",UUID.class,tenant,store);
  db.update("INSERT INTO order_inventory_reservations(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,account_catalog_item_id,quantity,location_id) VALUES (?,?,'TEST',?,?,1,?)",tenant,store,line,item,location());
  assertThat(review(false).operation()).isEqualTo("BLOCKED");
  assertThat(review(true).operation()).isEqualTo("BLOCKED");
 }
 @Test void globalDeletionChecksLinkedAccountStock(){movement(1,location());assertThat(review(true).operation()).isEqualTo("BLOCKED");assertThatThrownBy(()->remove(true,"RETAINED_DELETE")).hasMessageContaining("Zero out");}
 @Test void stockInDifferentLocationsCannotCancel(){UUID first=location();UUID warehouse=db.queryForObject("SELECT warehouse_id FROM warehouse_locations WHERE id=?",UUID.class,first);UUID second=db.queryForObject("INSERT INTO warehouse_locations(tenant_id,warehouse_id,code,name) VALUES (?,?,'OTHER','Other') RETURNING id",UUID.class,tenant,warehouse);movement(4,first);movement(-4,second);assertThat(review(false).operation()).isEqualTo("BLOCKED");}
 @Test void zeroStockPreservesHistory(){UUID loc=location();movement(4,loc);movement(-4,loc);remove(false,"RETAINED_DELETE");assertThat(db.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE account_catalog_item_id=?",Integer.class,item)).isEqualTo(2);}
 @Test void receiptValidatedBeforeDeletionCannotPostAfterIt(){remove(false,"RETAINED_DELETE");assertThatThrownBy(()->movement(4,location())).hasMessageContaining("deleted");}
 @Test void racingReceiptCannotPostAfterDeleteCommits()throws Exception{
  var deleted=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
  try{
   var future=executor.submit(()->tx.execute(status->{service.remove(tenant,item,false,"RETAINED_DELETE");deleted.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
   assertThat(deleted.await(5,TimeUnit.SECONDS)).isTrue();
   var timer=Executors.newSingleThreadScheduledExecutor();timer.schedule(release::countDown,150,TimeUnit.MILLISECONDS);
   try{assertThatThrownBy(()->movement(4,location())).hasMessageContaining("deleted");}finally{timer.shutdownNow();}
   assertThat(future.get(5,TimeUnit.SECONDS)).isTrue();
  }finally{release.countDown();executor.shutdownNow();}
 }
}
