package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.marketplace.ShopifySkuRepository;
import com.nextaicommerce.platform.receiving.InventoryRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.nextaicommerce.platform.shopify.ShopifyOrderLifecycle.Ownership;

/** Explicit canonical-local verification; every synthetic fixture transaction is rolled back. No services or DDL. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="shopifyOrderLocalFixtures",matches="true")
class ShopifyOrdersDatabaseTest {
    static JdbcTemplate jdbc;static SingleConnectionDataSource restricted;static TransactionTemplate tx;
    UUID tenant,connection,otherConnection,item,variant,job,run,main,other,earlyLayer;String packingActor;
    Instant cutoff,created,revision;
    ShopifyOrderInventory inventory;ShopifyOrderRepository repo;
    @BeforeAll static void database()throws Exception{
        var p=new java.util.Properties();
        try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of(System.getProperty("shopifyLocalRuntime")))){p.load(in);}
        String url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_fee_readiness_20261024";
        var ds=new DriverManagerDataSource(url,"nextcommerce_fee_preview",p.getProperty("spring.datasource.password"));
        restricted=new SingleConnectionDataSource(ds.getConnection(),true);jdbc=new JdbcTemplate(restricted);
        assertThat(jdbc.queryForObject("SELECT current_database()||'|'||inet_server_port()",String.class)).isEqualTo("next_ai_commerce_fee_readiness_20261024|55432");
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(120);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('shopify_orders','shopify_order_lines','shopify_order_components','shopify_order_controls') AND relrowsecurity AND relforcerowsecurity",Integer.class)).isEqualTo(4);
        tx=new TransactionTemplate(new DataSourceTransactionManager(restricted));
    }
    @AfterAll static void cleanup(){if(restricted!=null)restricted.destroy();}
    void scope(UUID t){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,t.toString());}
    void fixture(){
        tenant=UUID.randomUUID();connection=UUID.randomUUID();otherConnection=UUID.randomUUID();item=UUID.randomUUID();run=UUID.randomUUID();job=UUID.randomUUID();
        packingActor="packing-"+tenant+"@example.invalid";jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Packing fixture','ACTIVE')",packingActor);
        cutoff=Instant.now().minusSeconds(120);created=cutoff.plusSeconds(1);revision=created.plusSeconds(10);scope(tenant);
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Orders fixture')",tenant,tenant.toString());
        for(UUID c:List.of(connection,otherConnection)){String domain="fixture-"+c+".myshopify.com";
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'SHOPIFY',?,?,'ACTIVE','Fixture','fixture://none','UTC',now())",c,tenant,domain,domain);}
        UUID product=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name,unit_of_measure) VALUES (?,'Fixture product','EA')",product);
        jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,'000001')",item,tenant,product);
        main=jdbc.queryForObject("SELECT location_id FROM account_catalog_item_locations WHERE tenant_id=? AND account_catalog_item_id=? AND is_default",UUID.class,tenant,item);
        other=UUID.randomUUID();jdbc.update("INSERT INTO warehouse_locations(id,tenant_id,code,name) VALUES (?,?,'OTHER','Earlier expiry warehouse')",other,tenant);
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES(?,?,?,'INCREMENTAL','SHOPIFY_ORDERS','RUNNING',now()-interval '30 days',now())",run,tenant,connection);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number) VALUES(?,?,?,?,'SHOPIFY_ORDERS',1)",job,tenant,connection,run);
        var evidence=new ShopifyCatalogRepository(jdbc);
        var p=new ShopifyCatalogClient.Product("gid://shopify/Product/1","Product","Brand","ACTIVE",created,null,null);
        evidence.publish(tenant,connection,job,run,null,"{\"fixture\":\"catalogue\"}",new ShopifyCatalogClient.Page(List.of(new ShopifyCatalogClient.Variant("gid://shopify/ProductVariant/1","gid://shopify/InventoryItem/1","000001-EAx3","Default Title",BigDecimal.TEN,created,"[]","[]",true,null,p)),null,false,1,1000,100),"USD");
        variant=jdbc.queryForObject("SELECT id FROM shopify_variants WHERE tenant_id=? AND marketplace_connection_id=?",UUID.class,tenant,connection);
        String hash=jdbc.queryForObject("SELECT observation_hash FROM shopify_variants WHERE tenant_id=? AND id=?",String.class,tenant,variant);
        new ShopifySkuRepository(jdbc,true).save(tenant,connection,variant,hash,List.of(item.toString()),List.of("3"),()->{});
        jdbc.update("INSERT INTO shopify_order_controls(tenant_id,marketplace_connection_id,inventory_enabled,activated_at,baseline_completed_at) VALUES(?,?,true,?,?)",tenant,connection,java.sql.Timestamp.from(cutoff),java.sql.Timestamp.from(cutoff.minusSeconds(1)));
        earlyLayer=stock(other,5,40,cutoff.minusSeconds(100));stock(main,10,80,cutoff.minusSeconds(1000));
        inventory=new ShopifyOrderInventory(jdbc,true,true);org.springframework.test.util.ReflectionTestUtils.setField(inventory,"inventoryScopes",tenant+"/"+connection);repo=new ShopifyOrderRepository(jdbc,inventory,true);
    }
    UUID stock(UUID location,int units,int expiryDays,Instant received){
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key) VALUES(?,?,?,?,'RECEIPT',?,current_date+?,2,'USD','MANUAL',?,?,?)",id,tenant,item,location,units,expiryDays,UUID.randomUUID(),java.sql.Timestamp.from(received),id.toString());return id;
    }
    ShopifyOrderClient.Order order(int open,boolean cancel,String payment,List<ShopifyOrderLifecycle.Fulfillment> fs){
        var money=new ShopifyOrderClient.Money(BigDecimal.valueOf(30),"USD");
        return new ShopifyOrderClient.Order("gid://shopify/Order/1","#1001",created,revision,cancel?revision:null,false,payment,open==0?"FULFILLED":"UNFULFILLED",money,true,null,
            List.of(new ShopifyOrderClient.Line("gid://shopify/LineItem/1","gid://shopify/ProductVariant/1","000001-EAx3","Product",null,3,3,open,true,Ownership.MERCHANT,money,fs)));
    }
    void publish(ShopifyOrderClient.Order order){repo.publish(tenant,connection,job,null,"{\"fixture\":\"order-"+revision+"\"}",new ShopifyOrderClient.Page(List.of(order),null,false,100,1000,100));}
    ShopifyOrderLifecycle.Fulfillment shipment(int units){return new ShopifyOrderLifecycle.Fulfillment("gid://shopify/Fulfillment/1","gid://shopify/FulfillmentLineItem/1",units,"SUCCESS",Ownership.MERCHANT,revision);}
    BigDecimal stock(){return jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",BigDecimal.class,tenant,item);}
    BigDecimal active(){return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",BigDecimal.class,tenant);}
    void rolled(Runnable action){tx.executeWithoutResult(s->{s.setRollbackOnly();fixture();action.run();});}
    ShopifyOrderJobs engine(ShopifyOrderRepository publisher,Clock clock){
        jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED' WHERE tenant_id=? AND id=?",tenant,job);
        var access=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var auth=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.ShopifyConnectionService.class);
        String actor="orders-fixture@example.invalid";
        org.mockito.Mockito.when(access.canOperateAccount(tenant,actor)).thenReturn(true);
        var store=new com.nextaicommerce.platform.web.WorkspaceAccessRepository.ConnectionView(connection,tenant,"Fixture","Fixture","SHOPIFY","fixture.myshopify.com","fixture.myshopify.com","Shopify","","ACTIVE",null,true);
        org.mockito.Mockito.when(access.listAccountOptions(actor,false)).thenReturn(List.of(new com.nextaicommerce.platform.web.WorkspaceAccessRepository.AccountOption(tenant,"Fixture","F",null,List.of(store))));
        org.mockito.Mockito.when(auth.setupState(tenant,connection)).thenReturn(new com.nextaicommerce.platform.web.ShopifyConnectionService.SetupState(true,true,true));
        var nested=new TransactionTemplate(new DataSourceTransactionManager(restricted));nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        return new ShopifyOrderJobs(jdbc,nested,access,auth,publisher,inventory,true,clock);
    }
    static final class MutableClock extends Clock {
        Instant current=Instant.now();public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return current;}
    }
    @Test void pendingReservesSharedStockByExpiryAcrossLocationsWithoutPhysicalMovement(){rolled(()->{
        publish(order(3,false,"PENDING",List.of()));assertThat(active()).isEqualByComparingTo("9");assertThat(stock()).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_inventory_reservations WHERE tenant_id=? AND cost_layer_id=? AND status='ACTIVE'",BigDecimal.class,tenant,earlyLayer)).isEqualByComparingTo("5");
        var rows=new InventoryRepository(jdbc).inventory(tenant,List.of(item));assertThat(rows).isNotEmpty();assertThat(rows.getFirst().totalReservedQuantity()).isEqualByComparingTo("9");
        assertThat(new InventoryRepository(jdbc).reservations(tenant,item,null,null)).hasSize(2);
    });}
    @Test void successfulPartialShipmentConsumesExistingLotsOnceAndRefundDoesNotReturnStock(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));revision=revision.plusSeconds(1);var shipped=shipment(1);var o=order(2,false,"PAID",List.of(shipped));publish(o);
        assertThat(stock()).isEqualByComparingTo("12");assertThat(active()).isEqualByComparingTo("6");publish(o);
        assertThat(stock()).isEqualByComparingTo("12");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND source_type='SHOPIFY_ORDER' AND quantity<0",Integer.class,tenant)).isEqualTo(1);
        revision=revision.plusSeconds(1);publish(order(2,false,"REFUNDED",List.of(shipped)));assertThat(stock()).isEqualByComparingTo("12");
        revision=revision.plusSeconds(1);publish(order(0,true,"REFUNDED",List.of(shipped)));assertThat(active()).isZero();assertThat(stock()).isEqualByComparingTo("12");
    });}
    @Test void firstSeenCompletedAndHistoricalOrdersNeverConsumeTodayStock(){rolled(()->{
        publish(order(0,false,"PAID",List.of(shipment(3))));assertThat(active()).isZero();assertThat(stock()).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT stock_scope FROM shopify_order_lines WHERE tenant_id=?",String.class,tenant)).isEqualTo("REPORTING_ONLY");
    });}
    @Test void incompleteEvidenceAndReversalKeepExistingAllocations(){rolled(()->{
        publish(order(3,false,"PENDING",List.of()));var o=order(3,false,"PENDING",List.of());revision=revision.plusSeconds(1);
        publish(new ShopifyOrderClient.Order(o.gid(),o.name(),o.created(),revision,null,false,o.payment(),o.fulfillment(),o.total(),false,"Incomplete",List.of()));
        assertThat(active()).isEqualByComparingTo("9");assertThat(stock()).isEqualByComparingTo("15");
    });}
    @Test void tenantAndConnectionForeignKeysRejectCrossStoreReservations(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID line=jdbc.queryForObject("SELECT id FROM shopify_order_lines WHERE tenant_id=?",UUID.class,tenant);
        jdbc.execute("SAVEPOINT isolation_probe");
        assertThatThrownBy(()->jdbc.update("UPDATE order_inventory_reservations SET marketplace_connection_id=? WHERE tenant_id=? AND shopify_order_line_id=?",otherConnection,tenant,line)).isInstanceOf(RuntimeException.class);
        jdbc.execute("ROLLBACK TO SAVEPOINT isolation_probe");jdbc.execute("RELEASE SAVEPOINT isolation_probe");
        scope(UUID.randomUUID());assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_orders",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM order_inventory_reservations",Integer.class)).isZero();scope(tenant);
        assertThat(active()).isEqualByComparingTo("9");
    });}
    @Test void warehouseDisablePreservesOperationalCheckpointForLaterReprocessing(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));revision=revision.plusSeconds(1);var o=order(2,false,"PAID",List.of(shipment(1)));
        var paused=new ShopifyOrderRepository(jdbc,new ShopifyOrderInventory(jdbc,false,true),true);
        paused.publish(tenant,connection,job,null,"{\"fixture\":\"paused\"}",new ShopifyOrderClient.Page(List.of(o),null,false,100,1000,100));
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isEqualByComparingTo("9");publish(o);
        assertThat(stock()).isEqualByComparingTo("12");assertThat(active()).isEqualByComparingTo("6");
    });}
    @Test void refundedOrRemovedOpenUnitsAreClosedWithoutInventingShipment(){rolled(()->{
        publish(order(3,false,"PENDING",List.of()));revision=revision.plusSeconds(1);publish(order(0,false,"REFUNDED",List.of()));
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isZero();
        assertThat(jdbc.queryForObject("SELECT inventory_state FROM shopify_order_lines WHERE tenant_id=?",String.class,tenant)).isEqualTo("CLOSED");
    });}
    @Test void amazonAndShopifyShareCostLayersAndPhysicalCountReconciliationRebuildsBoth(){rolled(()->{
        publish(order(3,false,"PENDING",List.of()));UUID amazon=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','fixture-amazon','ATVPDKIKX0DER','ACTIVE','Amazon fixture','fixture://none','UTC',?)",amazon,tenant,java.sql.Timestamp.from(cutoff));
        UUID mapping=jdbc.queryForObject("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,marketplace_sku,account_catalog_item_id,quantity_per_marketplace_unit,status) VALUES(?,?,'AMAZON-SINGLE',?,1,'ACTIVE') RETURNING id",UUID.class,tenant,amazon,item);
        jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order) VALUES(?,?,?,1,0)",tenant,mapping,item);
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,fulfillment_channel,purchase_date) VALUES(?,?,'ATVPDKIKX0DER','FIXTURE-ORDER','Pending','MFN',?)",tenant,amazon,java.sql.Timestamp.from(created));
        jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES(?,?,'FIXTURE-ORDER','FIXTURE-LINE','AMAZON-SINGLE',2)",tenant,amazon);
        var orders=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);orders.reconcile(tenant,amazon);
        assertThat(active()).isEqualByComparingTo("11");assertThat(stock()).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_inventory_reservations WHERE tenant_id=? AND shopify_order_line_id IS NOT NULL AND status='ACTIVE'",BigDecimal.class,tenant)).isEqualByComparingTo("9");
        org.springframework.test.util.ReflectionTestUtils.setField(orders,"shopifyInventory",inventory);
        jdbc.update("DELETE FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",tenant);orders.reconcileTenantAfterPhysicalCount(tenant);
        assertThat(active()).isEqualByComparingTo("11");assertThat(stock()).isEqualByComparingTo("15");
    });}
    @Test void increasedPartialReplayAndLateObservationNeverRewriteReceiptsOrDoubleConsume(){rolled(()->{
        String receipts=jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=?",String.class,tenant);
        publish(order(3,false,"PAID",List.of()));revision=revision.plusSeconds(1);var first=order(2,false,"PAID",List.of(shipment(1)));publish(first);
        revision=revision.plusSeconds(1);var second=order(1,false,"PAID",List.of(shipment(2)));publish(second);publish(second);publish(first);
        assertThat(stock()).isEqualByComparingTo("9");assertThat(active()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(l)::text,',' ORDER BY id)) FROM inventory_ledger_entries l WHERE tenant_id=? AND source_type='MANUAL'",String.class,tenant)).isEqualTo(receipts);
    });}
    @Test void duplicateJobsAndExpiredOrReplacedLeaseCannotPublish(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));var jobs=engine(repo,Clock.systemUTC());String actor="orders-fixture@example.invalid";
        UUID id=jobs.enqueue(tenant,connection,actor);assertThat(jobs.enqueue(tenant,connection,actor)).isEqualTo(id);
        var first=jobs.claim(tenant,connection,id);assertThat(first).isNotNull();assertThat(jobs.claim(tenant,connection,id)).isNull();jobs.reserve(first);
        jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND id=?",tenant,id);
        var page=new ShopifyOrderClient.Page(List.of(),null,false,100,1000,100);
        assertThatThrownBy(()->jobs.publish(first,"{\"expired\":true}",page)).isInstanceOf(ShopifyOrderJobs.LostLease.class);
        var second=jobs.claim(tenant,connection,id);assertThat(second.fence()).isGreaterThan(first.fence());
        assertThatThrownBy(()->jobs.publish(first,"{\"replaced\":true}",page)).isInstanceOf(ShopifyOrderJobs.LostLease.class);
        assertThat(jobs.publish(second,"{\"current\":true}",page)).isFalse();assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isEqualByComparingTo("9");
    });}
    @Test void deadlineExpiringDuringWarehouseWorkRollsTheWholePublicationBack(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));var clock=new MutableClock();var observer=org.mockito.Mockito.spy(repo);
        org.mockito.Mockito.doAnswer(call->{Object value=call.callRealMethod();clock.current=clock.current.plusSeconds(7200);return value;}).when(observer).publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var jobs=engine(observer,clock);UUID id=jobs.enqueue(tenant,connection,"orders-fixture@example.invalid");var leased=jobs.claim(tenant,connection,id);jobs.reserve(leased);
        revision=revision.plusSeconds(1);var page=new ShopifyOrderClient.Page(List.of(order(2,false,"PAID",List.of(shipment(1)))),null,false,100,1000,100);
        assertThatThrownBy(()->jobs.publish(leased,"{\"deadline\":true}",page)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deadline");
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isEqualByComparingTo("9");
    });}
    @Test void countBetweenFulfillmentCreationAndSuccessRequiresReviewWithoutAnotherDeduction(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));Instant fulfillmentCreated=revision;
        jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,source_type,source_id,occurred_at,idempotency_key) SELECT tenant_id,account_catalog_item_id,location_id,'PHYSICAL_COUNT',0,expiration_date,'PHYSICAL_COUNT',?,clock_timestamp(),? FROM inventory_ledger_entries WHERE tenant_id=? AND id=?",UUID.randomUUID(),UUID.randomUUID().toString(),tenant,earlyLayer);
        revision=revision.plusSeconds(1);var late=new ShopifyOrderLifecycle.Fulfillment("gid://shopify/Fulfillment/1","gid://shopify/FulfillmentLineItem/1",1,"SUCCESS",Ownership.MERCHANT,fulfillmentCreated);
        publish(order(2,false,"PAID",List.of(late)));assertThat(stock()).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT inventory_state FROM shopify_order_lines WHERE tenant_id=?",String.class,tenant)).isEqualTo("NEEDS_REVIEW");
    });}
    @Test void renderedProjectionSearchWeeklyCoverageAndIncompleteAlertsAreScoped(){rolled(()->{
        publish(order(3,false,"PENDING",List.of()));var reads=new ShopifyOrderReadRepository(jdbc,inventory);
        var page=reads.page(tenant,connection,"ALL","000001",0,25);assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows().getFirst().adminUrl()).endsWith("/admin/orders/1");assertThat(page.rows().getFirst().lines().getFirst().available()).isEqualByComparingTo("2");
        assertThat(reads.weekly(tenant,connection,List.of(variant))).containsEntry(variant,"0 | 0 | 0 | 3");
        assertThat(reads.page(tenant,otherConnection,"ALL","",0,25).rows()).isEmpty();
        var original=order(3,false,"PENDING",List.of());revision=revision.plusSeconds(1);
        publish(new ShopifyOrderClient.Order(original.gid(),original.name(),original.created(),revision,null,false,original.payment(),original.fulfillment(),original.total(),false,"Incomplete fulfillment evidence",List.of()));
        assertThat(reads.page(tenant,connection,"NEEDS_REVIEW","",0,25).total()).isEqualTo(1);
        assertThat(reads.page(tenant,connection,"ALL","",0,25).rows().getFirst().lines().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        assertThat(reads.weekly(tenant,connection,List.of(variant))).isEmpty();assertThat(active()).isEqualByComparingTo("9");
    });}
    @Test void completeBaselineArmsOneCutoverAndOnlySubsequentlyCreatedOrdersReserve(){rolled(()->{
        jdbc.update("UPDATE shopify_order_controls SET inventory_enabled=false,activated_at=NULL,baseline_completed_at=NULL WHERE tenant_id=? AND marketplace_connection_id=?",tenant,connection);
        var jobs=engine(repo,Clock.systemUTC());String actor="orders-fixture@example.invalid";UUID first=jobs.enqueue(tenant,connection,actor);var lease=jobs.claim(tenant,connection,first);jobs.reserve(lease);
        jobs.publish(lease,"{\"baseline\":true}",new ShopifyOrderClient.Page(List.of(order(3,false,"PENDING",List.of())),null,false,100,1000,100));
        assertThat(active()).isZero();assertThat(stock()).isEqualByComparingTo("15");
        var boundary=jdbc.queryForObject("SELECT activated_at FROM shopify_order_controls WHERE tenant_id=? AND marketplace_connection_id=? AND inventory_enabled AND baseline_completed_at IS NOT NULL",java.sql.Timestamp.class,tenant,connection);
        assertThat(boundary).isNotNull();assertThat(jobs.initialized(tenant,connection)).isTrue();
        Instant newTime=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();revision=newTime;
        UUID second=jobs.enqueue(tenant,connection,actor);var delta=jobs.claim(tenant,connection,second);jobs.reserve(delta);
        jobs.publish(delta,"{\"old-open\":true}",new ShopifyOrderClient.Page(List.of(order(3,false,"PENDING",List.of())),"after-old",true,100,1000,100));
        assertThat(active()).isZero();
        var money=new ShopifyOrderClient.Money(BigDecimal.TEN,"USD");
        var line=new ShopifyOrderClient.Line("gid://shopify/LineItem/2","gid://shopify/ProductVariant/1","000001-EAx3","New order",null,1,1,1,true,Ownership.MERCHANT,money,List.of());
        var fresh=new ShopifyOrderClient.Order("gid://shopify/Order/2","#1002",newTime,newTime,null,false,"PENDING","UNFULFILLED",money,true,null,List.of(line));
        jdbc.update("UPDATE marketplace_sync_jobs SET available_at=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,second);var next=jobs.claim(tenant,connection,second);jobs.reserve(next);
        jobs.publish(next,"{\"new-order\":true}",new ShopifyOrderClient.Page(List.of(fresh),null,false,100,1000,100));
        assertThat(active()).isEqualByComparingTo("3");assertThat(stock()).isEqualByComparingTo("15");
        assertThat(jdbc.queryForObject("SELECT activated_at FROM shopify_order_controls WHERE tenant_id=? AND marketplace_connection_id=?",java.sql.Timestamp.class,tenant,connection)).isEqualTo(boundary);
        assertThat(jdbc.queryForObject("SELECT stock_scope FROM shopify_order_lines WHERE tenant_id=? AND line_gid='gid://shopify/LineItem/1'",String.class,tenant)).isEqualTo("REPORTING_ONLY");
    });}
    UUID localOrder(){return jdbc.queryForObject("SELECT id FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=?",UUID.class,tenant,connection);}
    ShopifyOrderPacking packing(){jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED',window_end=clock_timestamp(),completed_at=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,run);return new ShopifyOrderPacking(jdbc,inventory);}
    @Test void localPackingConsumesOnlyReservedLotsAndLaterSuccessCannotDeductAgain(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();packing().mark(tenant,connection,id,true,packingActor);
        assertThat(stock()).isEqualByComparingTo("6");assertThat(active()).isEqualByComparingTo("0");
        packing().mark(tenant,connection,id,true,packingActor);inventory.reconcileTenant(tenant);
        assertThat(stock()).isEqualByComparingTo("6");assertThat(active()).isEqualByComparingTo("0");
        revision=revision.plusSeconds(1);publish(order(2,false,"PAID",List.of(shipment(1))));
        assertThat(stock()).isEqualByComparingTo("6");assertThat(active()).isEqualByComparingTo("0");
        revision=revision.plusSeconds(1);publish(order(0,false,"PAID",List.of(shipment(3))));
        publish(order(0,false,"PAID",List.of(shipment(3))));assertThat(stock()).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT local_pending_units FROM shopify_order_lines WHERE tenant_id=?",Integer.class,tenant)).isZero();
    });}
    @Test void localPackingUndoUsesExactMovementsAndIsIdempotent(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();packing().mark(tenant,connection,id,true,packingActor);
        packing().mark(tenant,connection,id,false,packingActor);packing().mark(tenant,connection,id,false,packingActor);
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='PACKING_REVERSAL'",Integer.class,tenant)).isEqualTo(2);
    });}
    @Test void sourceAcknowledgementBlocksUndoAndCancellationDoesNotReturnPackedStock(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();packing().mark(tenant,connection,id,true,packingActor);
        revision=revision.plusSeconds(1);publish(order(2,false,"PAID",List.of(shipment(1))));
        assertThatThrownBy(()->packing().mark(tenant,connection,id,false,packingActor)).isInstanceOf(IllegalArgumentException.class);
        revision=revision.plusSeconds(1);publish(order(2,true,"REFUNDED",List.of(shipment(1))));
        assertThat(stock()).isEqualByComparingTo("6");assertThat(active()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT inventory_state FROM shopify_order_lines WHERE tenant_id=?",String.class,tenant)).isEqualTo("NEEDS_REVIEW");
    });}
    @Test void physicalCountAfterLocalHandoffBlocksUndo(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();packing().mark(tenant,connection,id,true,packingActor);
        jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,source_type,source_id,occurred_at,idempotency_key) VALUES(?,?,?,'ADJUSTMENT',0,current_date+40,'PHYSICAL_COUNT',?,clock_timestamp()+interval '1 second',?)",tenant,item,other,UUID.randomUUID(),UUID.randomUUID().toString());
        assertThatThrownBy(()->packing().mark(tenant,connection,id,false,packingActor)).isInstanceOf(IllegalArgumentException.class);assertThat(stock()).isEqualByComparingTo("6");
    });}
    @Test void historyUnknownOwnershipAndOtherStoreCannotBeLocallyPacked(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();
        assertThatThrownBy(()->packing().mark(tenant,otherConnection,id,true,packingActor)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE shopify_order_lines SET stock_scope='REPORTING_ONLY',inventory_state='REPORTING_ONLY' WHERE tenant_id=?",tenant);
        assertThatThrownBy(()->packing().mark(tenant,connection,id,true,packingActor)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock()).isEqualByComparingTo("15");
    });}

    @Test void unchangedOrderUsesRecentCompleteDeltaWatermarkAndStaleOrFailedCoverageBlocks(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();var p=packing();
        jdbc.update("UPDATE shopify_orders SET observed_at=clock_timestamp()-interval '1 day' WHERE tenant_id=?",tenant);
        assertThat(p.eligibility(tenant,connection,id)).isNull();
        jdbc.update("UPDATE marketplace_sync_runs SET window_start=clock_timestamp()-interval '1 hour',window_end=clock_timestamp()-interval '20 minutes' WHERE tenant_id=? AND id=?",tenant,run);
        assertThat(p.eligibility(tenant,connection,id)).contains("recent complete read window");
        jdbc.update("UPDATE marketplace_sync_runs SET window_end=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,run);
        jdbc.update("UPDATE shopify_order_controls SET last_error='Authorization failed' WHERE tenant_id=?",tenant);
        assertThat(p.eligibility(tenant,connection,id)).contains("recent complete read window");
        jdbc.update("UPDATE shopify_order_controls SET last_error=NULL WHERE tenant_id=?",tenant);
        p.mark(tenant,connection,id,true,packingActor);assertThat(stock()).isEqualByComparingTo("6");
    });}

    @Test void packingSlipReadsCurrentHistoricalMappingAndFrozenLotLocations(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=localOrder();var p=packing();
        var slip=p.slip(tenant,connection,id);assertThat(slip.lines()).hasSize(2);
        UUID line=jdbc.queryForObject("SELECT id FROM shopify_order_lines WHERE tenant_id=?",UUID.class,tenant);
        var reads=new ShopifyOrderReadRepository(jdbc,inventory);
        assertThat(reads.frozenComponents(tenant,connection,variant,line)).singleElement().satisfies(c->assertThat(c.itemId()).isEqualTo(item));
        assertThatThrownBy(()->reads.frozenComponents(tenant,otherConnection,variant,line)).isInstanceOf(IllegalArgumentException.class);
        assertThat(slip.lines().stream().map(com.nextaicommerce.platform.orders.PackingSlipRepository.Line::quantity).reduce(0,Integer::sum)).isEqualTo(9);
        assertThat(slip.lines()).anySatisfy(l->assertThat(l.location()).isEqualTo("OTHER"));
        p.mark(tenant,connection,id,true,packingActor);assertThat(p.slip(tenant,connection,id).lines()).hasSize(2);
        packing().mark(tenant,connection,id,false,packingActor);
        jdbc.update("DELETE FROM shopify_order_components WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE shopify_order_lines SET stock_scope='REPORTING_ONLY',inventory_state='REPORTING_ONLY' WHERE tenant_id=?",tenant);
        assertThat(p.slip(tenant,connection,id).lines()).isNotEmpty().allSatisfy(l->assertThat(l.accountItemId()).isEqualTo(item));
    });}

    @Test void sharedSimulationFansShopifyReservationToAmazonAndShopifyWithoutPhysicalMovement(){rolled(()->{
        UUID amazon=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','fixture','ATVPDKIKX0DER','fixture://none','ACTIVE','Amazon fixture','UTC',now())",amazon,tenant);
        for(String sku:List.of("SINGLE","PACK","FBA")){
            int units=sku.equals("PACK")?3:1;UUID mapping=UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace_sku_mappings(id,tenant_id,marketplace_connection_id,marketplace_sku,account_catalog_item_id,quantity_per_marketplace_unit) VALUES (?,?,?,?,?,?)",mapping,tenant,amazon,sku,item,units);
            jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) VALUES (?,?,?,?)",tenant,mapping,item,units);
            jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_channel,listing_status) VALUES (?,?,'ATVPDKIKX0DER',?,?,'Active')",tenant,amazon,sku,sku.equals("FBA")?"AFN":"MFN");
        }
        var planner=new com.nextaicommerce.platform.sync.InventoryPublicationRepository(jdbc,true);
        publish(order(3,false,"PAID",List.of()));planner.plan(tenant);planner.simulate(tenant);
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT desired_quantity FROM inventory_publications WHERE tenant_id=? AND seller_sku='SINGLE'",Integer.class,tenant)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT desired_quantity FROM inventory_publications WHERE tenant_id=? AND seller_sku='PACK'",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_publications WHERE tenant_id=? AND seller_sku='FBA'",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT desired_quantity FROM shopify_inventory_simulations WHERE tenant_id=? AND variant_id=?",Integer.class,tenant,variant)).isEqualTo(2);
        long version=jdbc.queryForObject("SELECT revision FROM shopify_inventory_simulations WHERE tenant_id=?",Long.class,tenant);planner.plan(tenant);
        assertThat(jdbc.queryForObject("SELECT revision FROM shopify_inventory_simulations WHERE tenant_id=?",Long.class,tenant)).isEqualTo(version);
        revision=revision.plusSeconds(1);publish(order(0,true,"VOIDED",List.of()));planner.plan(tenant);planner.simulate(tenant);
        assertThat(jdbc.queryForObject("SELECT desired_quantity FROM shopify_inventory_simulations WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT desired_quantity FROM inventory_publications WHERE tenant_id=? AND seller_sku='SINGLE'",Integer.class,tenant)).isEqualTo(15);
        assertThat(stock()).isEqualByComparingTo("15");
        scope(UUID.randomUUID());assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_simulations",Integer.class)).isZero();scope(tenant);
    });}
    @Test void nativeCarrierAdapterUsesOwnAccountAndActualAllocationsWithoutCustomerImport(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=jdbc.queryForObject("SELECT id FROM shopify_orders WHERE tenant_id=?",UUID.class,tenant);
        var adapter=new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,org.mockito.Mockito.mock(com.nextaicommerce.platform.shipping.BuyShippingService.class),true);
        var context=adapter.context(tenant,connection,id.toString());assertThat(context.items()).hasSize(1);assertThat(context.items().getFirst().remaining()).isEqualTo(3);
        var lines=adapter.packing(tenant,connection,id.toString());assertThat(lines).hasSize(1);assertThat(lines.getFirst().quantity()).isEqualTo(3);assertThat(lines.getFirst().allocations()).hasSize(2);
        assertThat(lines.getFirst().allocations().stream().map(com.nextaicommerce.platform.shipping.CarrierLabelComposer.Allocation::quantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("9");
        assertThat(lines.getFirst().allocations()).extracting(com.nextaicommerce.platform.shipping.CarrierLabelComposer.Allocation::location).contains("OTHER");
        assertThatThrownBy(()->adapter.context(tenant,otherConnection,id.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,null,false).context(tenant,connection,id.toString())).isInstanceOf(IllegalStateException.class);
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isEqualByComparingTo("9");
    });}

    @Test void carrierAdapterRejectsVoidedRefundedClosedAndTestOrders(){rolled(()->{
        publish(order(3,false,"PAID",List.of()));UUID id=jdbc.queryForObject("SELECT id FROM shopify_orders WHERE tenant_id=?",UUID.class,tenant);
        var adapter=new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,null,true);
        for(String status:List.of("VOIDED","REFUNDED","EXPIRED")){
            jdbc.update("UPDATE shopify_orders SET payment_status=? WHERE tenant_id=? AND id=?",status,tenant,id);
            assertThatThrownBy(()->adapter.context(tenant,connection,id.toString())).isInstanceOf(IllegalArgumentException.class);
        }
        jdbc.update("UPDATE shopify_orders SET payment_status='PAID',is_test=true WHERE tenant_id=? AND id=?",tenant,id);
        assertThatThrownBy(()->adapter.context(tenant,connection,id.toString())).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE shopify_orders SET is_test=false,fulfillment_status='FULFILLED' WHERE tenant_id=? AND id=?",tenant,id);
        assertThatThrownBy(()->adapter.context(tenant,connection,id.toString())).isInstanceOf(IllegalArgumentException.class);
    });}

    @Test void simulationUsesNativeVariantIdentityAndHoldsChangedMappingEvidence(){rolled(()->{
        var planner=new com.nextaicommerce.platform.sync.InventoryPublicationRepository(jdbc,true);
        UUID duplicate=UUID.randomUUID();
        jdbc.update("INSERT INTO shopify_variants(id,tenant_id,marketplace_connection_id,product_id,variant_gid,inventory_item_gid,seller_sku,title,selected_options,barcodes,barcodes_complete,price,currency,source_updated_at,source_document_id,last_seen_run,observation_hash,mapping_hash) SELECT ?,tenant_id,marketplace_connection_id,product_id,'gid://shopify/ProductVariant/2','gid://shopify/InventoryItem/2',seller_sku,title,selected_options,barcodes,barcodes_complete,price,currency,source_updated_at,source_document_id,last_seen_run,observation_hash,mapping_hash FROM shopify_variants WHERE tenant_id=? AND id=?",duplicate,tenant,variant);
        planner.plan(tenant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_simulations WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM shopify_inventory_simulations WHERE tenant_id=? AND variant_id=?",String.class,tenant,duplicate)).isEqualTo("ATTENTION");
        String original=jdbc.queryForObject("SELECT shopify_mapping_hash FROM marketplace_sku_mappings WHERE tenant_id=? AND shopify_variant_id=?",String.class,tenant,variant);
        jdbc.update("UPDATE shopify_variants SET mapping_hash=? WHERE tenant_id=? AND id=?","b".repeat(64),tenant,variant);planner.plan(tenant);
        assertThat(jdbc.queryForObject("SELECT desired_quantity FROM shopify_inventory_simulations WHERE tenant_id=? AND variant_id=?",Integer.class,tenant,variant)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM shopify_inventory_simulations WHERE tenant_id=? AND variant_id=?",String.class,tenant,variant)).isEqualTo("ATTENTION");
        assertThat(jdbc.queryForObject("SELECT shopify_mapping_hash FROM marketplace_sku_mappings WHERE tenant_id=? AND shopify_variant_id=?",String.class,tenant,variant)).isEqualTo(original);
        scope(UUID.randomUUID());assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_order_packaging",Integer.class)).isZero();scope(tenant);
    });}

    @Test void shippedOrderLabelFixtureUsesOriginalQuantitiesOnlyUnderExplicitFlagAndCannotChangeStock(){rolled(()->{
        publish(order(0,false,"PAID",List.of(shipment(3))));UUID id=jdbc.queryForObject("SELECT id FROM shopify_orders WHERE tenant_id=?",UUID.class,tenant);
        String before=jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(o)::text,'' ORDER BY id)) FROM shopify_orders o WHERE tenant_id=?",String.class,tenant);
        assertThatThrownBy(()->new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,null,true).context(tenant,connection,id.toString())).isInstanceOf(IllegalArgumentException.class);
        var adapter=new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,null,true,true);
        var c=adapter.context(tenant,connection,id.toString());assertThat(c.order().status()).isEqualTo("SandboxLabelTest");assertThat(c.items().getFirst().remaining()).isEqualTo(3);
        var packing=adapter.packing(tenant,connection,id.toString());assertThat(packing).hasSize(1);assertThat(packing.getFirst().quantity()).isEqualTo(3);assertThat(packing.getFirst().allocations()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(o)::text,'' ORDER BY id)) FROM shopify_orders o WHERE tenant_id=?",String.class,tenant)).isEqualTo(before);
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isZero();
    });}
    @Test void separateSandboxQuotesCannotBuyDuplicateLabelsAndUncertainOutcomeHoldsOrder(){rolled(()->{
        publish(order(0,false,"PAID",List.of(shipment(3))));UUID id=jdbc.queryForObject("SELECT id FROM shopify_orders WHERE tenant_id=?",UUID.class,tenant);
        var mapper=new tools.jackson.databind.ObjectMapper();var carriers=new com.nextaicommerce.platform.shipping.CarrierConnectionRepository(jdbc);
        UUID carrier=carriers.save(tenant,null,"Sandbox fixture","999999999","SANDBOX","PLATFORM",null);var c=carriers.find(tenant,carrier);
        var archive=new com.nextaicommerce.platform.shipping.FedExShipmentRepository(jdbc,mapper);
        var encrypted=new com.nextaicommerce.platform.shipping.ShippingLabelCrypto.Encrypted(new byte[]{1},new byte[]{2});
        var rates=List.of(new com.nextaicommerce.platform.shipping.FedExPayloads.Rate("FEDEX_GROUND","Fixture",BigDecimal.ONE,"USD"));
        UUID first=archive.quote(tenant,connection,id.toString(),c,encrypted,rates,List.of(),packingActor),second=archive.quote(tenant,connection,id.toString(),c,encrypted,rates,List.of(),packingActor);
        assertThat(archive.claim(tenant,connection,id.toString(),first,"FEDEX_GROUND",c.revision())).isNotNull();
        assertThat(archive.claim(tenant,connection,id.toString(),second,"FEDEX_GROUND",c.revision())).isNull();
        archive.failure(tenant,first,true);assertThat(archive.protectedShipment(tenant,connection,id.toString()).state()).isEqualTo("UNKNOWN");
        assertThat(archive.claim(tenant,connection,id.toString(),second,"FEDEX_GROUND",c.revision())).isNull();
        assertThat(archive.protectedShipment(tenant,otherConnection,id.toString())).isNull();assertThat(archive.list(tenant,connection,UUID.randomUUID().toString())).isEmpty();
        assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isZero();
    });}

    @Test void amazonShippedPackingTestUsesOriginalUnitsAndLeavesSourceRowsUnchanged(){rolled(()->{
        UUID amazon=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','fixture','ATVPDKIKX0DER','fixture://none','ACTIVE','Amazon fixture','UTC',now())",amazon,tenant);
        String order="111-1111111-1111111";UUID line=UUID.randomUUID();
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,fulfillment_channel,operational_scope) VALUES (?,?,'ATVPDKIKX0DER',?,now(),'Shipped','MFN','HISTORICAL')",tenant,amazon,order);
        jdbc.update("INSERT INTO amazon_order_items(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,title,quantity_ordered,quantity_shipped) VALUES (?,?,?,?,?,'TEST-EAx3','Fixture pizza',2,2)",line,tenant,amazon,order,"fixture-line");
        var archive=new com.nextaicommerce.platform.shipping.FedExShipmentRepository(jdbc,new tools.jackson.databind.ObjectMapper());
        assertThat(archive.packing(tenant,amazon,order)).isEmpty();var preview=archive.packing(tenant,amazon,order,true);
        assertThat(preview).hasSize(1);assertThat(preview.getFirst().quantity()).isEqualTo(2);assertThat(preview.getFirst().allocations()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT quantity_shipped FROM amazon_order_items WHERE tenant_id=? AND id=?",Integer.class,tenant,line)).isEqualTo(2);assertThat(stock()).isEqualByComparingTo("15");assertThat(active()).isZero();
    });}

}
