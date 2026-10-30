package com.nextaicommerce.platform.shopify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.marketplace.ShopifySkuRepository;
import com.nextaicommerce.platform.profit.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** CI-only disposable database. Never connects to or migrates Local UAT/production. */
@EnabledIfEnvironmentVariable(named="CI",matches="true")
class ShopifyPublicationDatabaseTest {
 static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
 UUID tenant,connection,item,variant,binding,main,other,run,job;Instant startup;
 ShopifyInventoryPublications repository;
 @BeforeAll static void database()throws Exception{
  postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
  schema="public"; // This fixture owns and closes its disposable cluster.
  String url=postgres.getJdbcUrl("postgres","postgres");
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",null);
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  for(String f:List.of("ensure_item_default_location","fill_inventory_ledger_location","fill_receiving_receipt_location","fill_order_reservation_location"))jdbc.execute("ALTER FUNCTION "+schema+"."+f+"() SET search_path TO "+schema);
  jdbc.execute("CREATE ROLE publication_reader NOLOGIN NOSUPERUSER NOBYPASSRLS");
  jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO publication_reader");
  jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+schema+" TO publication_reader");
 }
 @AfterAll static void close()throws Exception{if(postgres!=null)postgres.close();}
 void scope(){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 @BeforeEach void fixture(){
  tenant=UUID.randomUUID();connection=UUID.randomUUID();item=UUID.randomUUID();run=UUID.randomUUID();job=UUID.randomUUID();binding=UUID.randomUUID();startup=Instant.now().minusSeconds(60);repository=new ShopifyInventoryPublications(jdbc,tx);
  tx.executeWithoutResult(s->{
   jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES(?,?,'Publication fixture')",tenant,tenant.toString());scope();
   jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES(?,?,'SHOPIFY','fixture.myshopify.com','fixture.myshopify.com','ACTIVE','Fixture','fixture://none','UTC',now())",connection,tenant);
   UUID global=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name,unit_of_measure) VALUES(?,'Fixture product','EA')",global);
   jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES(?,?,?,'000001')",item,tenant,global);
   main=jdbc.queryForObject("SELECT location_id FROM account_catalog_item_locations WHERE tenant_id=? AND account_catalog_item_id=? AND is_default",UUID.class,tenant,item);
   other=UUID.randomUUID();jdbc.update("INSERT INTO warehouse_locations(id,tenant_id,code,name) VALUES(?,?,'EXCLUDED','Excluded source')",other,tenant);
   jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES(?,?,?,'INCREMENTAL','SHOPIFY_ORDERS','COMPLETED',now()-interval '30 days',now())",run,tenant,connection);
   jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,status,completed_at) VALUES(?,?,?,?,'SHOPIFY_ORDERS',1,'COMPLETED',now())",job,tenant,connection,run);
   var product=new ShopifyCatalogClient.Product("gid://shopify/Product/1","Product","Brand","ACTIVE",startup,null,null);
   var page=new ShopifyCatalogClient.Page(List.of(new ShopifyCatalogClient.Variant("gid://shopify/ProductVariant/1","gid://shopify/InventoryItem/2","000001-EAx3","Default Title",BigDecimal.TEN,startup,"[]","[]",true,null,product)),null,false,1,1000,100);
   new ShopifyCatalogRepository(jdbc).publish(tenant,connection,job,run,null,"{\"fixture\":true}",page,"USD");
   variant=jdbc.queryForObject("SELECT id FROM shopify_variants WHERE tenant_id=? AND marketplace_connection_id=?",UUID.class,tenant,connection);
   String hash=jdbc.queryForObject("SELECT observation_hash FROM shopify_variants WHERE tenant_id=? AND id=?",String.class,tenant,variant);
   new ShopifySkuRepository(jdbc,true).save(tenant,connection,variant,hash,List.of(item.toString()),List.of("3"),()->{});
   stock(main,12);stock(other,300);
   jdbc.update("INSERT INTO shopify_inventory_bindings(id,tenant_id,marketplace_connection_id,shop_gid,location_gid,approved_by) VALUES(?,?,?,'gid://shopify/Shop/1','gid://shopify/Location/3','fixture')",binding,tenant,connection);
   jdbc.update("INSERT INTO shopify_inventory_binding_sources VALUES(?,?,?,?)",tenant,connection,binding,main);
   jdbc.update("UPDATE shopify_inventory_bindings SET enabled=true WHERE tenant_id=? AND id=?",tenant,binding);
   jdbc.update("UPDATE inventory_publication_rate_slot SET ready_at=now()-interval '1 second' WHERE id=1");
  });
 }
 void stock(UUID location,int units){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,unit_cost,currency,source_type,source_id,idempotency_key,occurred_at) VALUES(?,?,?,?,'RECEIPT',?,current_date+80,2,'USD','MANUAL',?,?,now())",id,tenant,item,location,units,id,id.toString());}
 @Test void sourceSubsetDuplicatePreventionAndNoInventoryMutation(){
  String before=jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant);
  assertThat(repository.plan(tenant,connection,startup)).isEqualTo(1);
  assertThat(repository.plan(tenant,connection,startup)).isZero();
  var operation=repository.next(tenant,connection).orElseThrow();assertThat(operation.desired()).isEqualTo(4);
  assertThat(repository.next(UUID.randomUUID(),connection)).isEmpty();assertThat(repository.next(tenant,UUID.randomUUID())).isEmpty();
  var client=mock(ShopifyInventoryClient.class);when(client.available(eq(operation.target()),any())).thenReturn(7);
  repository.prepare(operation,client,startup);
  jdbc.update("UPDATE shopify_inventory_publications SET available_at=now() WHERE id=?",operation.id());
  var ready=repository.next(tenant,connection).orElseThrow();assertThat(ready.expected()).isEqualTo(7);assertThat(ready.state()).isEqualTo("READY");
  jdbc.update("UPDATE inventory_publication_rate_slot SET ready_at=now()-interval '1 second' WHERE id=1");
  assertThat(repository.markSubmitting(ready,startup)).isTrue();
  repository.submit(ready,client,startup);verify(client).setAvailable(eq(ready.target()),eq(7),eq(4),eq(ready.id()),any());
  assertThat(jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant)).isEqualTo(before);
 }
 // Fresh import completion alone must not turn historical open commitments into sellable stock.
 private UUID historicalOpenOrder(boolean complete){
  UUID order=UUID.randomUUID();
  tx.executeWithoutResult(t->{scope();
   UUID source=jdbc.queryForObject("SELECT id FROM shopify_source_documents WHERE tenant_id=? AND marketplace_connection_id=? LIMIT 1",UUID.class,tenant,connection);
   jdbc.update("INSERT INTO shopify_orders(id,tenant_id,marketplace_connection_id,order_gid,order_name,source_created_at,source_updated_at,is_test,payment_status,fulfillment_status,currency,total,evidence_complete,observation_hash,source_document_id) VALUES(?,?,?,'gid://shopify/Order/1009','#1009',now()-interval '26 days',now(),false,'PAID','PARTIALLY_FULFILLED','USD',10,?,repeat('a',64),?)",order,tenant,connection,complete,source);
   jdbc.update("INSERT INTO shopify_order_lines(id,tenant_id,marketplace_connection_id,order_id,line_gid,title,ordered_quantity,current_quantity,unfulfilled_quantity,requires_shipping,ownership,source_updated_at,observation_hash,stock_scope,inventory_state,reserve_units) VALUES(?,?,?,?, 'gid://shopify/LineItem/1009','Open physical item',1,1,1,true,'MERCHANT',now(),repeat('b',64),'REPORTING_ONLY','REPORTING_ONLY',0)",UUID.randomUUID(),tenant,connection,order);
  });return order;
 }
 @Test void historicalOpenCommitmentBlocksPublicationWithoutReplayingShipments(){
  UUID order=historicalOpenOrder(true);
  String before=jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant);
  assertThat(repository.plan(tenant,connection,startup)).isZero();
  assertThat(jdbc.queryForObject("SELECT reserve_units FROM shopify_order_lines WHERE tenant_id=? AND order_id=?",Integer.class,tenant,order)).isZero();
  assertThat(jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant)).isEqualTo(before);
  tx.executeWithoutResult(t->{scope();jdbc.update("UPDATE shopify_order_lines SET unfulfilled_quantity=0 WHERE tenant_id=? AND order_id=?",tenant,order);});
  assertThat(repository.plan(tenant,connection,startup)).isEqualTo(1);
 }
 @Test void newlyObservedOpenCommitmentFencesPreparedPublication(){
  repository.plan(tenant,connection,startup);var operation=repository.next(tenant,connection).orElseThrow();
  var client=mock(ShopifyInventoryClient.class);when(client.available(eq(operation.target()),any())).thenReturn(4);
  repository.prepare(operation,client,startup);
  jdbc.update("UPDATE shopify_inventory_publications SET available_at=now() WHERE id=?",operation.id());
  var ready=repository.next(tenant,connection).orElseThrow();
  historicalOpenOrder(true);
  assertThat(repository.markSubmitting(ready,startup)).isFalse();
  verify(client,never()).setAvailable(any(),anyInt(),anyInt(),any(),any());
 }
 @Test void incompleteNonCancelledOrderEvidenceBlocksPublication(){
  UUID order=historicalOpenOrder(false);
  assertThat(repository.plan(tenant,connection,startup)).isZero();
  tx.executeWithoutResult(t->{scope();jdbc.update("UPDATE shopify_orders SET cancelled_at=now() WHERE tenant_id=? AND id=?",tenant,order);});
  assertThat(repository.plan(tenant,connection,startup)).isEqualTo(1);
 }
 @Test void explicitCutoverReservesOnlyRemainingUnitsAndIsImmutableAndIdempotent(){
  UUID order=historicalOpenOrder(true);var inventory=new ShopifyOrderInventory(jdbc,true,true);
  UUID evidence=UUID.randomUUID();
  tx.executeWithoutResult(t->{scope();
   jdbc.update("INSERT INTO shopify_source_documents(id,tenant_id,marketplace_connection_id,sync_job_id,api_version,query_revision,sha256,response_body) VALUES(?,?,?,?,'2026-10',?,repeat('c',64),'{}')",evidence,tenant,connection,job,ShopifyOrderClient.REVISION);
   jdbc.update("UPDATE shopify_orders SET source_document_id=? WHERE tenant_id=? AND id=?",evidence,tenant,order);
   jdbc.update("INSERT INTO shopify_order_controls(tenant_id,marketplace_connection_id,inventory_enabled,activated_at,baseline_completed_at) VALUES(?,?,true,now(),now())",tenant,connection);
   jdbc.update("UPDATE shopify_order_lines SET variant_id=?,variant_gid='gid://shopify/ProductVariant/1',ordered_quantity=3,current_quantity=3,inventory_revision=source_updated_at,inventory_hash=observation_hash,success_checkpoint='{\"prior-shipment\":2}'::jsonb WHERE tenant_id=? AND order_id=?",variant,tenant,order);
   inventory.bootstrapRemaining(tenant,connection,job); // No configured scope means no cutover.
  });
  assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_order_cutovers WHERE tenant_id=?",Integer.class,tenant)).isZero();
  org.springframework.test.util.ReflectionTestUtils.setField(inventory,"bootstrapScopes",tenant+"/"+connection);
  String before=jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant);
  tx.executeWithoutResult(t->{scope();inventory.bootstrapRemaining(tenant,connection,UUID.randomUUID());});
  assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_order_cutovers WHERE tenant_id=?",Integer.class,tenant)).isZero();
  tx.executeWithoutResult(t->{scope();inventory.bootstrapRemaining(tenant,connection,job);inventory.bootstrapRemaining(tenant,connection,job);});
  assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_order_cutovers WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",BigDecimal.class,tenant)).isEqualByComparingTo("3");
  assertThat(jdbc.queryForObject("SELECT fulfillment_checkpoint->>'prior-shipment' FROM shopify_order_cutovers WHERE tenant_id=?",String.class,tenant)).isEqualTo("2");
  assertThat(jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant)).isEqualTo(before);
  assertThatThrownBy(()->jdbc.update("UPDATE shopify_order_cutovers SET remaining_units=99 WHERE tenant_id=?",tenant)).isInstanceOf(org.springframework.dao.DataAccessException.class);
  tx.executeWithoutResult(t->{jdbc.execute("SET LOCAL ROLE publication_reader");jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_order_cutovers",Integer.class)).isZero();});
 }
 @Test void newWarehouseLocationsAreNotAutomaticallyIncluded(){
  tx.executeWithoutResult(s->{scope();UUID added=UUID.randomUUID();
   jdbc.update("INSERT INTO warehouse_locations(id,tenant_id,code,name) VALUES(?,?,'NEW_BIN','New unselected bin')",added,tenant);
   stock(added,600);
  });
  assertThat(repository.plan(tenant,connection,startup)).isEqualTo(1);
  assertThat(repository.next(tenant,connection).orElseThrow().desired()).isEqualTo(4);
 }
 @Test void remoteDecreaseRequiresLaterOrderReconciliationBeforeIncrease(){
  repository.plan(tenant,connection,startup);var first=repository.next(tenant,connection).orElseThrow();
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE shopify_inventory_publications SET expected_quantity=4,state='CONFIRMED',confirmed_at=now()-interval '16 minutes' WHERE id=?",first.id());});
  repository.plan(tenant,connection,startup);var next=repository.next(tenant,connection).orElseThrow();
  var client=mock(ShopifyInventoryClient.class);when(client.available(eq(next.target()),any())).thenReturn(2);
  repository.prepare(next,client,startup);
  assertThat(jdbc.queryForObject("SELECT state FROM shopify_inventory_publications WHERE id=?",String.class,next.id())).isEqualTo("ATTENTION");
  assertThat(repository.requestReconciliation(tenant,next.id(),"fixture")).isFalse();
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE marketplace_sync_jobs SET completed_at=clock_timestamp() WHERE id=?",job);});
  assertThat(repository.requestReconciliation(tenant,next.id(),"fixture")).isFalse(); // completed job, but its order window predates the observation
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE marketplace_sync_runs SET window_end=clock_timestamp() WHERE id=?",run);});
  assertThat(repository.requestReconciliation(tenant,next.id(),"fixture")).isTrue();
  repository.plan(tenant,connection,startup);var reconciled=repository.next(tenant,connection).orElseThrow();
  jdbc.update("UPDATE inventory_publication_rate_slot SET ready_at=now()-interval '1 second' WHERE id=1");
  repository.prepare(reconciled,client,startup);
  assertThat(jdbc.queryForObject("SELECT state FROM shopify_inventory_publications WHERE id=?",String.class,reconciled.id())).isEqualTo("READY");
  verify(client,never()).setAvailable(any(),anyInt(),anyInt(),any(),any());
 }
 @Test void emptySelectionCannotPublishEvenIfBindingIsEnabled(){
  tx.executeWithoutResult(s->{scope();
   jdbc.update("DELETE FROM shopify_inventory_binding_sources WHERE tenant_id=? AND binding_id=?",tenant,binding);
   jdbc.update("UPDATE shopify_inventory_bindings SET enabled=true WHERE tenant_id=? AND id=?",tenant,binding);
  });
  assertThat(repository.plan(tenant,connection,startup)).isZero();
  assertThat(repository.next(tenant,connection)).isEmpty();
 }
 @Test void bindingChangesDisableAndFencePreparedWork(){
  repository.plan(tenant,connection,startup);var operation=repository.next(tenant,connection).orElseThrow();
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE shopify_inventory_bindings SET location_gid='gid://shopify/Location/99' WHERE id=?",binding);});
  assertThat(jdbc.queryForObject("SELECT enabled FROM shopify_inventory_bindings WHERE id=?",Boolean.class,binding)).isFalse();
  var client=mock(ShopifyInventoryClient.class);repository.prepare(operation,client,startup);verifyNoInteractions(client);
  assertThat(jdbc.queryForObject("SELECT state FROM shopify_inventory_publications WHERE id=?",String.class,operation.id())).isEqualTo("CANCELLED");
 }
 @Test void tenantRlsAndStoredZeroOverride(){
  var profit=new ShopifyOrderProfitRepository(jdbc,new ProfitCostRepository(jdbc));
  tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE publication_reader");scope();
   profit.saveOtherCosts(tenant,connection,List.of(new ShopifyOrderProfitRepository.OtherCostChange(variant,BigDecimal.ZERO)),"USD","fixture");
   assertThat(jdbc.queryForObject("SELECT profit_other_cost FROM shopify_variants WHERE id=?",BigDecimal.class,variant)).isEqualByComparingTo("0");
   jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());
   assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_bindings WHERE id=?",Integer.class,binding)).isZero();
  });
 }
 @Test void listingCalculatorUsesVariantScopeAndKeepsUnknownShippingPending(){
  var profit=new ShopifyOrderProfitRepository(jdbc,new ProfitCostRepository(jdbc));
  tx.executeWithoutResult(s->{scope();
   var view=profit.skus(tenant,connection,List.of(variant.toString())).get(variant.toString());
   assertThat(view.kind()).isEqualTo("SHOPIFY_SKU");
   assertThat(view.lines().getFirst().variantId()).isEqualTo(variant);
   assertThat(view.totals().revenue()).isEqualByComparingTo("10");
   assertThat(view.totals().productCost()).isNull();
   assertThat(view.totals().shippingCost()).isNull();
   assertThat(view.totals().profit()).isNull();
   assertThat(profit.skus(tenant,UUID.randomUUID(),List.of(variant.toString()))).isEmpty();
   jdbc.execute("SET LOCAL ROLE publication_reader");
   assertThat(profit.skus(UUID.randomUUID(),connection,List.of(variant.toString()))).isEmpty();
  });
 }
 @Test void inactiveItemsAndUnrecordedShopifyDeparturesPublishZero(){
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE account_catalog_items SET status='INACTIVE' WHERE id=?",item);});
  repository.plan(tenant,connection,startup);assertThat(repository.next(tenant,connection).orElseThrow().desired()).isZero();
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE shopify_inventory_publications SET state='CANCELLED' WHERE tenant_id=?",tenant);jdbc.update("UPDATE account_catalog_items SET status='ACTIVE' WHERE id=?",item);
   UUID id=UUID.randomUUID();jdbc.update("INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,location_id,entry_type,quantity,source_type,source_id,idempotency_key,occurred_at) VALUES(?,?,?,?,'SHIPMENT_UNRECORDED',0,'SHOPIFY_ORDER',?,?,now())",id,tenant,item,main,id,id.toString());
  });
  repository.plan(tenant,connection,startup);assertThat(repository.next(tenant,connection).orElseThrow().desired()).isZero();
 }
 @Test void previouslyManagedUnmappedVariantGetsTombstoneZero(){
  repository.plan(tenant,connection,startup);var first=repository.next(tenant,connection).orElseThrow();
  tx.executeWithoutResult(s->{scope();jdbc.update("UPDATE shopify_inventory_publications SET expected_quantity=4,state='CONFIRMED',confirmed_at=now() WHERE id=?",first.id());jdbc.update("UPDATE marketplace_sku_mappings SET status='INACTIVE' WHERE tenant_id=? AND shopify_variant_id=?",tenant,variant);});
  assertThat(repository.plan(tenant,connection,startup)).isEqualTo(1);
  assertThat(repository.next(tenant,connection).orElseThrow().desired()).isZero();
 }
}
