package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class InventoryPublicationDatabaseTest {
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
 InventoryPublicationRepository repo;UUID tenant,connection,item,second,user;
 @BeforeAll static void setup() throws Exception{
  schema="publication_test_"+UUID.randomUUID().toString().replace("-","");
  String url,password=null;
  if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();}
  else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
  var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
  org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  for(String f:List.of("ensure_item_default_location","fill_inventory_ledger_location","fill_receiving_receipt_location","fill_order_reservation_location"))jdbc.execute("ALTER FUNCTION "+schema+"."+f+"() SET search_path TO "+schema);
 }
 @AfterAll static void cleanup() throws Exception{if(jdbc!=null&&schema.matches("publication_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(postgres!=null)postgres.close();}
 @BeforeEach void fixture(){repo=new InventoryPublicationRepository(jdbc);tenant=UUID.randomUUID();connection=UUID.randomUUID();user=UUID.randomUUID();tx.executeWithoutResult(s->{
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?, 'Publication test')",tenant,tenant.toString());repo.scope(tenant);
  jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Test')",user,user+"@example.test");
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','seller','ATVPDKIKX0DER','test','ACTIVE','Test','America/Los_Angeles',now()-interval '1 day')",connection,tenant);
  item=product();second=product();stock(item,12,40);stock(second,3,40);
  mapping("SINGLE",item,1);mapping("PACK",item,4);UUID bundle=mapping("BUNDLE",item,2);
  jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) VALUES (?,?,?,1)",tenant,bundle,second);
  mapping("FBA",item,1);jdbc.update("UPDATE amazon_listings SET fulfillment_channel='AFN' WHERE tenant_id=? AND seller_sku='FBA'",tenant);
 });}
 @Test void newConnectionsInitializeInventoryBoundaryWithoutActivation(){tx.executeWithoutResult(s->{
  repo.scope(tenant);
  var access=new com.nextaicommerce.platform.web.WorkspaceAccessRepository(jdbc);
  var existing=jdbc.queryForObject("SELECT inventory_activated_at FROM marketplace_connections WHERE id=?",java.sql.Timestamp.class,connection);
 access.addConnection(tenant,"AMAZON","New Amazon US","new-seller","ATVPDKIKX0DER");
  access.addConnection(tenant,"WALMART","New Walmart US",null,"Walmart-US");
  var rows=jdbc.queryForList("SELECT status,inventory_activated_at,credential_secret_ref FROM marketplace_connections WHERE tenant_id=? AND id<>?",tenant,connection);
  assertThat(rows).hasSize(2).allSatisfy(row->{
   assertThat(row.get("inventory_activated_at")).isNotNull();
   assertThat(row.get("status")).isEqualTo("PENDING");
  assertThat(row.get("credential_secret_ref")).isEqualTo("pending://marketplace-connection");
  });
  jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'OWNER')",tenant,user);
  assertThat(access.canAdministerAccount(tenant,user+"@example.test",false)).isTrue();
  jdbc.update("UPDATE tenant_memberships SET role='OPERATOR' WHERE tenant_id=? AND user_id=?",tenant,user);
  assertThat(access.canAdministerAccount(tenant,user+"@example.test",false)).isFalse();
  assertThat(access.canAdministerAccount(tenant,user+"@example.test",true)).isTrue();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_publications WHERE tenant_id=?",Integer.class,tenant)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM shelf_sale_settings WHERE tenant_id=?",Integer.class,tenant)).isZero();
  jdbc.update("UPDATE marketplace_connections SET status='DISABLED' WHERE id=?",connection);
  access.addConnection(tenant,"AMAZON","Reconnected","seller","ATVPDKIKX0DER");
  assertThat(jdbc.queryForObject("SELECT inventory_activated_at FROM marketplace_connections WHERE id=?",java.sql.Timestamp.class,connection)).isEqualTo(existing);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_connections WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(3);
 });}
 UUID product(){UUID p=UUID.randomUUID(),g=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Test product')",g);jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,?)",p,tenant,g,p.toString());return p;}
 UUID stock(UUID p,int qty,int days){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,entry_type,quantity,expiration_date,source_type,occurred_at,idempotency_key) VALUES (?,?,?,'ADJUSTMENT',?,current_date+?,'MANUAL',now(),?)",id,tenant,p,qty,days,id.toString());return id;}
 UUID mapping(String sku,UUID p,int qty){UUID m=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_sku_mappings(id,tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,quantity_per_marketplace_unit) VALUES (?,?,?,?,?,?)",m,tenant,connection,p,sku,qty);jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) VALUES (?,?,?,?)",tenant,m,p,qty);jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_channel,listing_status) VALUES (?,?,'ATVPDKIKX0DER',?,'MFN','Active')",tenant,connection,sku);return m;}
 ShelfSaleRepository.Listing saleRow(String sku){return new ShelfSaleRepository.Listing(tenant,connection,"ATVPDKIKX0DER",sku,"seller",null,null,"CHECK",0);}
 ShelfSaleRepository saleFixture(){
  var sales=new ShelfSaleRepository(jdbc);sales.configure(tenant,true,"test");
  jdbc.update("INSERT INTO inventory_shelf_life_policies(tenant_id,minimum_sellable_days,warning_days,sale_start_days_before_expiration,sale_duration_days) VALUES (?,10,30,20,10)",tenant);
  stock(item,4,15);repo.plan(tenant);jdbc.update("UPDATE inventory_publications SET status='CONFIRMED' WHERE tenant_id=?",tenant);sales.discover(tenant,connection);return sales;
 }
 @Test void salesExcludeExpiredFbaAndExhaustedBatchesAndRespectAccountOff(){tx.executeWithoutResult(s->{
  var sales=saleFixture();assertThat(sales.plan(saleRow("SINGLE"))).isPresent();assertThat(sales.plan(saleRow("PACK"))).isPresent();
  assertThat(sales.plan(saleRow("FBA"))).isEmpty();assertThat(sales.next(tenant,connection)).isPresent();
  sales.configure(tenant,false,"test");assertThat(sales.plan(saleRow("SINGLE"))).isEmpty();sales.configure(tenant,true,"test");
  stock(item,-4,15);stock(item,100,5);assertThat(sales.plan(saleRow("SINGLE"))).isEmpty();
 });}
 @Test void reservationsRemoveDiscountAndConfirmedCancellationRestoresIt(){tx.executeWithoutResult(s->{
  var sales=saleFixture();order("reserve-discount","SINGLE",4);var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  assertThat(sales.plan(saleRow("SINGLE"))).isEmpty();
  jdbc.update("UPDATE amazon_orders SET order_status='Canceled' WHERE tenant_id=?",tenant);orders.reconcile(tenant,connection);
  assertThat(sales.plan(saleRow("SINGLE"))).isPresent();
 });}
 @Test void saleHoldsUnmappingAndUnconfirmedStockBlockPublishing(){tx.executeWithoutResult(s->{
  var sales=saleFixture();jdbc.update("UPDATE inventory_publications SET status='PENDING' WHERE tenant_id=?",tenant);assertThat(sales.plan(saleRow("SINGLE"))).isEmpty();
  jdbc.update("UPDATE inventory_publications SET status='CONFIRMED' WHERE tenant_id=?",tenant);
  jdbc.update("INSERT INTO inventory_expiration_actions(tenant_id,account_catalog_item_id,expiration_date,action_type,created_by) VALUES (?,?,current_date+15,'HOLD',?)",tenant,item,user);
  assertThat(sales.plan(saleRow("SINGLE"))).isEmpty();jdbc.update("DELETE FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_sku='PACK'",tenant);assertThat(sales.plan(saleRow("PACK"))).isEmpty();
 });}
 @Test void itemLedgerScopeExcludesOtherProductsAndTenants(){tx.executeWithoutResult(s->{
  var inventory=new com.nextaicommerce.platform.receiving.InventoryRepository(jdbc);
  assertThat(inventory.ledgerPage(tenant,"",0,25,item).rows()).hasSize(1).allMatch(r->r.itemId().equals(item));
  assertThat(inventory.ledgerPage(tenant,"",0,25,UUID.randomUUID()).rows()).isEmpty();
 });}
 @Test void saleQueuePersistsIdleIntentConfirmationAndRetryStates(){tx.executeWithoutResult(s->{
  var sales=saleFixture();var row=saleRow("SINGLE");
  sales.result(row,"IDLE",null,null,60,null);
  sales.result(row,"READY",null,"[]",1,null);
  sales.result(row,"CONFIRMED","[]",null,60,null);
  sales.result(row,"RETRY","[]",null,60,"Unconfirmed");
  assertThat(jdbc.queryForObject("SELECT attempts FROM shelf_sale_publications WHERE tenant_id=? AND seller_sku='SINGLE'",Integer.class,tenant)).isEqualTo(1);
  sales.result(row,"REMOVED",null,null,60,null);
  assertThat(jdbc.queryForObject("SELECT attempts FROM shelf_sale_publications WHERE tenant_id=? AND seller_sku='SINGLE'",Integer.class,tenant)).isZero();
 });}
 @Test void saleChangesFanOutImmediatelyAndPolicyCutoffBoundsEndDate(){tx.executeWithoutResult(s->{
  var sales=saleFixture();sales.reconcileDirty(tenant);
  jdbc.update("UPDATE shelf_sale_publications SET urgent=false,next_attempt_at=now()+interval '15 minutes' WHERE tenant_id=?",tenant);
  stock(item,-4,15);sales.reconcileDirty(tenant);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM shelf_sale_publications WHERE tenant_id=? AND urgent AND next_attempt_at<=now()",Integer.class,tenant)).isEqualTo(3);
  assertThat(sales.next(tenant,connection)).isPresent();assertThat(sales.plan(saleRow("SINGLE"))).isEmpty();
  stock(item,4,15);
  jdbc.update("UPDATE inventory_shelf_life_policies SET minimum_sellable_days=14 WHERE tenant_id=?",tenant);
  sales.reconcileDirty(tenant);
  assertThat(sales.plan(saleRow("SINGLE")).orElseThrow().end()).isEqualTo(jdbc.queryForObject("SELECT (current_date+1)::timestamptz",java.sql.Timestamp.class).toInstant());
  var inventory=new com.nextaicommerce.platform.receiving.InventoryRepository(jdbc);
  assertThat(inventory.shelfPolicySkuCount(tenant)).isEqualTo(3);
 });}
 int quantity(String sku){return jdbc.queryForObject("SELECT desired_quantity FROM inventory_publications WHERE tenant_id=? AND seller_sku=?",Integer.class,tenant,sku);}
 @Test void sharesStockAndUsesLimitingBundleComponentExcludingFba(){tx.executeWithoutResult(s->{repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);assertThat(quantity("PACK")).isEqualTo(3);assertThat(quantity("BUNDLE")).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_publications WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(3);assertThat(repo.plan(tenant)).isZero();});}
 @Test void adjustmentsFanOutOnlyChangedQuantitiesAndNeverDuplicate(){tx.executeWithoutResult(s->{repo.plan(tenant);stock(item,-9,40);assertThat(repo.plan(tenant)).isEqualTo(3);assertThat(quantity("SINGLE")).isEqualTo(3);assertThat(quantity("PACK")).isZero();assertThat(quantity("BUNDLE")).isEqualTo(1);assertThat(repo.plan(tenant)).isZero();stock(item,9,40);repo.plan(tenant);assertThat(quantity("PACK")).isEqualTo(3);});}
 @Test void expiryPolicyDateCorrectionsAndHoldsInvalidate(){tx.executeWithoutResult(s->{repo.plan(tenant);jdbc.update("UPDATE inventory_ledger_entries SET expiration_date=current_date+10 WHERE tenant_id=? AND account_catalog_item_id=?",tenant,item);repo.plan(tenant);assertThat(quantity("SINGLE")).isZero();jdbc.update("INSERT INTO inventory_shelf_life_policies(tenant_id,minimum_sellable_days,warning_days) VALUES (?,5,30)",tenant);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);jdbc.update("INSERT INTO inventory_expiration_actions(tenant_id,account_catalog_item_id,expiration_date,action_type,created_by) VALUES (?,?,current_date+10,'HOLD',?)",tenant,item,user);repo.plan(tenant);assertThat(quantity("BUNDLE")).isZero();jdbc.update("UPDATE inventory_expiration_actions SET status='CANCELLED' WHERE tenant_id=?",tenant);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);});}
 @Test void clockSweepRunsWithoutStockEditsAndUnmappingZerosPreviouslyManagedSku(){tx.executeWithoutResult(s->{repo.plan(tenant);jdbc.update("UPDATE inventory_ledger_entries SET expiration_date=current_date+10 WHERE tenant_id=? AND account_catalog_item_id=?",tenant,item);jdbc.update("DELETE FROM inventory_publication_dirty WHERE tenant_id=?",tenant);jdbc.update("UPDATE inventory_publication_reconciliation SET checked_at=now()-interval '16 minutes' WHERE tenant_id=?",tenant);repo.plan(tenant);assertThat(quantity("SINGLE")).isZero();assertThat(jdbc.queryForObject("SELECT checked_at>now()-interval '1 minute' FROM inventory_publication_reconciliation WHERE tenant_id=?",Boolean.class,tenant)).isTrue();jdbc.update("DELETE FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_sku='PACK'",tenant);repo.plan(tenant);assertThat(quantity("PACK")).isZero();});}
 @Test void cancelledOrdersReleaseAvailabilityAndOverlapNotesDoNotDeductStock(){tx.executeWithoutResult(s->{
  order("one","SINGLE",8);order("two","PACK",2);
  var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  repo.plan(tenant);assertThat(quantity("SINGLE")).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='SHARED_STOCK_SALES'",Integer.class,tenant)).isEqualTo(1);
  orders.reconcile(tenant,connection);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='SHARED_STOCK_SALES'",Integer.class,tenant)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",Integer.class,tenant,item)).isEqualTo(12);
  jdbc.update("UPDATE amazon_orders SET order_status='Canceled' WHERE tenant_id=?",tenant);orders.reconcile(tenant,connection);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);
 });}
 void order(String id,String sku,int qty){jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,fulfillment_channel,operational_scope) VALUES (?,?,'ATVPDKIKX0DER',?,now(),'Unshipped','MFN','LIVE')",tenant,connection,id);jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,?,?,?,?)",tenant,connection,id,id,sku,qty);}
 @Test void dryRunStateAndStaleRevisionCannotConfirmNewQuantity(){tx.executeWithoutResult(s->{repo.plan(tenant);var old=repo.next(tenant).orElseThrow();stock(item,-12,40);repo.plan(tenant);repo.result(old,"CONFIRMED",0,900,null,old.quantity());assertThat(jdbc.queryForObject("SELECT status FROM inventory_publications WHERE tenant_id=? AND seller_sku=?",String.class,tenant,old.sku())).isEqualTo("PENDING");repo.simulate(tenant);assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_publications WHERE tenant_id=? AND status='DRY_RUN'",Integer.class,tenant)).isEqualTo(3);});}
 @Test void rollbackDoesNotLeakDirtyEvents(){tx.executeWithoutResult(s->repo.plan(tenant));tx.executeWithoutResult(s->{repo.scope(tenant);stock(item,-2,40);s.setRollbackOnly();});tx.executeWithoutResult(s->{repo.scope(tenant);assertThat(repo.plan(tenant)).isZero();assertThat(quantity("SINGLE")).isEqualTo(12);});}
 @Test void oldPendingMissingOrderKeepsReservationAndAuditIsIdempotent(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("old-pending","SINGLE",4);
  jdbc.update("UPDATE marketplace_connections SET inventory_activated_at=now()-interval '60 days' WHERE id=?",connection);
  jdbc.update("UPDATE amazon_orders SET purchase_date=now()-interval '35 days',order_status='Pending' WHERE tenant_id=?",tenant);
  var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  var checks=new UnresolvedOrderRepository(jdbc,orders);var check=checks.claim(tenant).orElseThrow();
  checks.failed(check,"UNCONFIRMED","Amazon could not find this order.",60);
  checks.failed(check,"UNCONFIRMED","Amazon could not find this order.",60);
  orders.reconcile(tenant,connection);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(8);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='ORDER_STATUS_REVIEW'",Integer.class,tenant)).isEqualTo(1);
  checks.confirmed(check,"Pending",java.time.Instant.now());
  assertThat(jdbc.queryForObject("SELECT state FROM amazon_order_status_checks WHERE tenant_id=?",String.class,tenant)).isEqualTo("OVERDUE");
  repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(8);
 });}
 @Test void directConfirmedCancellationReleasesOnceAndOlderResponseCannotReopen(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("pending","PACK",2);
  var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(4);
  var checks=new UnresolvedOrderRepository(jdbc,orders);var check=checks.claim(tenant).orElseThrow();var now=java.time.Instant.now();
  checks.confirmed(check,"Canceled",now);checks.confirmed(check,"Canceled",now);checks.confirmed(check,"Pending",now.minusSeconds(60));
  repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);assertThat(quantity("PACK")).isEqualTo(3);
  assertThat(jdbc.queryForObject("SELECT order_status FROM amazon_orders WHERE tenant_id=?",String.class,tenant)).isEqualTo("Canceled");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='RESERVATION_RELEASED'",Integer.class,tenant)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",Integer.class,tenant,item)).isEqualTo(12);
 });}
 @Test void cancellationAfterPackedOrderDoesNotReturnShelfStock(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("packed","SINGLE",4);var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  orders.setPickupOverride(tenant,connection,"packed",true,"test");repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(8);
  var checks=new UnresolvedOrderRepository(jdbc,orders);var check=checks.claim(tenant).orElseThrow();checks.confirmed(check,"Canceled",java.time.Instant.now());
  repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(8);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",Integer.class,tenant,item)).isEqualTo(8);
 });}
 @Test void directCancellationWithUnimportedShipmentDetailsCannotReleaseStock(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("not-yet-imported","SINGLE",3);
  var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  var checks=new UnresolvedOrderRepository(jdbc,orders);var check=checks.claim(tenant).orElseThrow();
  assertThatThrownBy(()->checks.confirmed(check,"Canceled",java.time.Instant.now(),1)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->checks.confirmed(check,"Canceled",java.time.Instant.now(),null)).isInstanceOf(IllegalStateException.class);
  repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(9);
  checks.confirmed(check,"Canceled",java.time.Instant.now(),0);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);
 });}
 @Test void partialPackShipmentKeepsRemainderReservedAndCancellationNeverReturnsShippedStock(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("partial","PACK",2);var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  jdbc.update("UPDATE amazon_orders SET order_status='Partially shipped' WHERE tenant_id=?",tenant);
  jdbc.update("UPDATE amazon_order_items SET quantity_shipped=1 WHERE tenant_id=?",tenant);
  orders.reconcile(tenant,connection);orders.reconcile(tenant,connection);repo.plan(tenant);
  assertThat(quantity("SINGLE")).isEqualTo(4);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isEqualTo(4);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='SHIPMENT'",Integer.class,tenant)).isEqualTo(-4);
  jdbc.update("UPDATE amazon_orders SET order_status='Canceled' WHERE tenant_id=?",tenant);
  orders.reconcile(tenant,connection);orders.reconcile(tenant,connection);repo.plan(tenant);
  assertThat(quantity("SINGLE")).isEqualTo(8);assertThat(quantity("PACK")).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='RESERVATION_RELEASED'",Integer.class,tenant)).isEqualTo(1);
 });}
 @Test void repeatedPartialBundleShipmentsAndFinalShipmentDeductEachComponentExactlyOnce(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("partial","BUNDLE",3);var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,connection);
  for(int shipped=1;shipped<=3;shipped++){
   jdbc.update("UPDATE amazon_orders SET order_status=? WHERE tenant_id=?",shipped==3?"Shipped":"PartiallyShipped",tenant);
   jdbc.update("UPDATE amazon_order_items SET quantity_shipped=? WHERE tenant_id=?",shipped,tenant);
   orders.reconcile(tenant,connection);orders.reconcile(tenant,connection);repo.plan(tenant);
   assertThat(quantity("SINGLE")).isEqualTo(6);assertThat(quantity("BUNDLE")).isZero();
   assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND entry_type='SHIPMENT'",Integer.class,tenant,item)).isEqualTo(-2*shipped);
   assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND entry_type='SHIPMENT'",Integer.class,tenant,second)).isEqualTo(-shipped);
  }
 });}
 @Test void missingPartialAllocationHoldsAllRelatedSkusWithoutConsumingRemainderOnRepeatedSync(){tx.executeWithoutResult(s->{
  repo.scope(tenant);order("missing-partial","SINGLE",3);
  jdbc.update("UPDATE amazon_orders SET order_status='PartiallyShipped' WHERE tenant_id=?",tenant);
  jdbc.update("UPDATE amazon_order_items SET quantity_shipped=1 WHERE tenant_id=?",tenant);
  var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
  for(int i=0;i<3;i++){orders.reconcile(tenant,connection);repo.plan(tenant);}
  assertThat(quantity("SINGLE")).isZero();assertThat(quantity("PACK")).isZero();assertThat(quantity("BUNDLE")).isZero();
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",Integer.class,tenant,item)).isEqualTo(12);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_unrecorded_shipments WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='ORDER_STATUS_REVIEW'",Integer.class,tenant)).isEqualTo(1);
  stock(item,2,50);
  jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,entry_type,quantity,expiration_date,source_type,occurred_at,idempotency_key) VALUES (?,?,'ADJUSTMENT',0,current_date+40,'PHYSICAL_COUNT',now(),?)",tenant,item,UUID.randomUUID().toString());
  orders.reconcileTenantAfterPhysicalCount(tenant);repo.plan(tenant);assertThat(quantity("SINGLE")).isZero();
  jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,entry_type,quantity,expiration_date,source_type,occurred_at,idempotency_key) VALUES (?,?,'ADJUSTMENT',0,current_date+50,'PHYSICAL_COUNT',now(),?)",tenant,item,UUID.randomUUID().toString());
  orders.reconcileTenantAfterPhysicalCount(tenant);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);
  orders.reconcile(tenant,connection);repo.plan(tenant);assertThat(quantity("SINGLE")).isEqualTo(12);
 });}
 @Test void livePublishingRequiresFreshCompletedReconciliationAndNoImportInProgress(){tx.executeWithoutResult(s->{
  repo.plan(tenant);var startup=java.time.Instant.now().minusSeconds(1);
  assertThat(repo.next(tenant,startup)).isEmpty();
  UUID run=UUID.randomUUID(),job=UUID.randomUUID();
  jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES (?,?,?,'INCREMENTAL','ORDER_CHANGES','COMPLETED',now()-interval '10 minutes',now())",run,tenant,connection);
  jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,status,completed_at) VALUES (?,?,?,?,'FINAL_RECONCILIATION',1,'COMPLETED',now())",job,tenant,connection,run);
  assertThat(repo.next(tenant,startup)).isPresent();
  assertThat(repo.next(tenant,startup,Set.of(UUID.randomUUID()))).isEmpty();
  assertThat(repo.next(tenant,startup,Set.of())).isEmpty();
  assertThat(repo.next(tenant,startup,Set.of(connection))).isPresent();
  assertThat(repo.next(tenant,startup,Set.of(connection),"missing-sku")).isEmpty();
  assertThat(repo.next(tenant,startup,Set.of(connection),"PACK").orElseThrow().sku()).isEqualTo("PACK");
  jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,status) VALUES (?,?,?,'ORDERS_API_DELTA',2,'RUNNING')",tenant,connection,run);
  assertThat(repo.next(tenant,startup)).isEmpty();
  jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED' WHERE tenant_id=?",tenant);
  jdbc.update("UPDATE marketplace_sync_runs SET window_end=now()-interval '16 minutes' WHERE tenant_id=?",tenant);
  assertThat(repo.next(tenant,startup)).isEmpty();
 });}
}
