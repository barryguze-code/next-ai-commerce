package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.catalog.CatalogRepository;

/** Real PostgreSQL in an isolated disposable local cluster; never reads deployment credentials. */
class ReceivingWorkflowDatabaseTest {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    static String schema;
    static DataSource source;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static ReceivingWorkflowRepository work;
    static ReceivingRepository receiving;
    static InventoryRepository inventory;
    UUID tenant,user,vendor;
    String actor;
    LocalDate expiry=LocalDate.now().plusDays(60);
    record Fixture(UUID session,UUID document,UUID line,UUID product,UUID location){}
    @Configuration @EnableTransactionManagement
    @Import(com.nextaicommerce.platform.config.ReadCacheConfiguration.class)
    static class Config{
        @Bean DataSource dataSource(){return source;}
        @Bean com.nextaicommerce.platform.config.PlatformReadCache readCache(){return new com.nextaicommerce.platform.config.PlatformReadCache(true,java.time.Duration.ofSeconds(10),256);}
        @Bean com.nextaicommerce.platform.catalog.CatalogReadService catalogReads(CatalogRepository c,com.nextaicommerce.platform.config.PlatformReadCache cache){return new com.nextaicommerce.platform.catalog.CatalogReadService(c,cache);}
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(source);}
        @Bean DataSourceTransactionManager transactionManager(){return new DataSourceTransactionManager(source);}
        @Bean CatalogRepository catalog(JdbcTemplate j){return new CatalogRepository(j);}
        @Bean InventoryRepository inventory(JdbcTemplate j){return new InventoryRepository(j);}
        @Bean ReceivingRepository receiving(JdbcTemplate j,CatalogRepository c){return new ReceivingRepository(j,c);}
        @Bean ReceivingWorkflowRepository work(JdbcTemplate j,ReceivingRepository r,InventoryRepository i){return new ReceivingWorkflowRepository(j,r,i);}
    }
    @BeforeAll static void setup() throws Exception{
        schema="receiving_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){
            // Fixed loopback destination, separate database. Never accepts DB_URL or a production credential.
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();
        }else{
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");
        }
        var pool=new com.zaxxer.hikari.HikariConfig();
        pool.setJdbcUrl(url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        pool.setUsername("postgres");pool.setPassword(password);
        pool.setMaximumPoolSize(4);pool.setMinimumIdle(1);source=new com.zaxxer.hikari.HikariDataSource(pool);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);
        // Production triggers explicitly select public; bind their isolated test copies to this test schema.
        for(String fn:List.of("ensure_item_default_location","fill_inventory_ledger_location","fill_receiving_receipt_location","fill_order_reservation_location"))
            jdbc.execute("ALTER FUNCTION "+schema+"."+fn+"() SET search_path TO "+schema);
        context=new AnnotationConfigApplicationContext(Config.class);
        tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        work=context.getBean(ReceivingWorkflowRepository.class);receiving=context.getBean(ReceivingRepository.class);inventory=context.getBean(InventoryRepository.class);
    }
    @AfterAll static void cleanup(){
        if(schema!=null&&schema.matches("receiving_test_[a-f0-9]{32}")&&source!=null)
            new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        if(context!=null)context.close();
        else if(source instanceof com.zaxxer.hikari.HikariDataSource pool)pool.close();
        if(postgres!=null)try{postgres.close();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    @BeforeEach void account(){
        tenant=UUID.randomUUID();user=UUID.randomUUID();vendor=UUID.randomUUID();actor="qa-"+user+"@example.test";
        tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,?)",tenant,"qa-"+tenant,"Receiving QA");
            jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,?)",user,actor,"Receiving tester");
            setTenant();jdbc.update("INSERT INTO vendors(id,tenant_id,name) VALUES (?,?,?)",vendor,tenant,"Synthetic supplier");
        });
    }
    void setTenant(){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    @Test void emptyStockItemsCanBeAdjustedAndOptionsAreTenantScoped(){
        var f=fixture("INVOICE");
        var options=inventory.adjustmentItems(tenant,List.of(f.product()));
        assertThat(options).hasSize(1);assertThat(options.getFirst().expirationRequired()).isTrue();
        assertThat(inventory.adjustmentItems(UUID.randomUUID(),List.of(f.product()))).isEmpty();
        assertThatThrownBy(()->inventory.adjustInventory(tenant,actor,f.product(),null,f.location(),BigDecimal.ONE,"COUNT_CORRECTION","Found stock"))
            .hasMessageContaining("expiration");
        inventory.adjustInventory(tenant,actor,f.product(),expiry,f.location(),new BigDecimal("24"),"COUNT_CORRECTION","Found stock");
        assertThat(stock(f)).isEqualByComparingTo("24");
        assertThat(inventory.inventory(tenant,List.of(f.product()))).hasSize(1);
        assertThat(inventory.inventory(tenant,List.of(UUID.randomUUID()))).isEmpty();
        assertThat(inventory.inventory(tenant)).hasSize(1);
    }
    @Test void manualReceiptRecordsItemDateLocationAndExplanation(){
        var f=fixture("INVOICE");
        inventory.receiveUninvoicedItem(tenant,actor,f.product(),new BigDecimal("5"),expiry,f.location(),"Received extra delivery without invoice");
        assertThat(stock(f)).isEqualByComparingTo("5");
        tx.executeWithoutResult(s->{setTenant();assertThat(jdbc.queryForObject("SELECT notes FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",String.class,tenant,f.product())).contains("extra delivery");});
        assertThat(work.documents(tenant,List.of(f.document())).getFirst().received()).isEqualByComparingTo("0");
    }
    @Test void adjustmentCreatesTheEnteredExpirationWithoutChangingAnotherBatch(){
        var f=fixture("INVOICE");receive(f,6);LocalDate entered=expiry.plusDays(29);
        inventory.adjustInventory(tenant,actor,f.product(),entered,f.location(),new BigDecimal("6"),"COUNT_CORRECTION","New date adjustment");
        tx.executeWithoutResult(s->{setTenant();
            for(LocalDate date:List.of(expiry,entered))assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date=?",BigDecimal.class,tenant,f.product(),f.location(),date)).isEqualByComparingTo("6");
        });
        inventory.adjustInventory(tenant,actor,f.product(),entered,f.location(),new BigDecimal("2"),"COUNT_CORRECTION","Same date adjustment");
        tx.executeWithoutResult(s->{setTenant();assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date=?",BigDecimal.class,tenant,f.product(),f.location(),entered)).isEqualByComparingTo("8");});
    }
    @Test void expirationCorrectionPreservesQuantityAndHistory(){
        var f=fixture("INVOICE");receive(f,10);
        var repo=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
        UUID connection=mappedOrder(f,4,"Unshipped");tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        LocalDate corrected=expiry.plusDays(5);
        inventory.changeExpiration(tenant,actor,f.product(),f.location(),expiry,corrected,BigDecimal.TEN,repo);
        tx.executeWithoutResult(s->{setTenant();
            assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",BigDecimal.class,tenant,f.product())).isEqualByComparingTo("10");
            assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=?",BigDecimal.class,tenant,f.product(),expiry)).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND source_type='EXPIRATION_CORRECTION'",Long.class,tenant,f.product())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_inventory_reservations WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=? AND status='ACTIVE'",BigDecimal.class,tenant,f.product(),corrected)).isEqualByComparingTo("4");
        });
        assertThatThrownBy(()->inventory.changeExpiration(tenant,actor,f.product(),f.location(),corrected,expiry,BigDecimal.ONE,repo)).hasMessageContaining("quantity changed");
        assertThatThrownBy(()->inventory.changeExpiration(UUID.randomUUID(),actor,f.product(),f.location(),corrected,expiry,BigDecimal.TEN,repo)).hasMessageContaining("unavailable");
        LocalDate existing=corrected.plusDays(5);
        inventory.adjustInventory(tenant,actor,f.product(),existing,f.location(),new BigDecimal("2"),"COUNT_CORRECTION","Merge target");
        inventory.changeExpiration(tenant,actor,f.product(),f.location(),corrected,existing,BigDecimal.TEN,repo);
        tx.executeWithoutResult(s->{setTenant();assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=?",BigDecimal.class,tenant,f.product(),existing)).isEqualByComparingTo("12");});
    }
    @Test void imageSyncWithoutAnEligibleMappingKeepsImagesUnchanged(){
        var client=org.mockito.Mockito.mock(com.nextaicommerce.platform.sync.AmazonSpApiClient.class);
        var controller=new com.nextaicommerce.platform.sync.CatalogImageSyncController(jdbc,tx,client,context.getBean(CatalogRepository.class));
        var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("selectedTenantId",tenant);
        var response=controller.sync(fixture("INVOICE").product(),session,org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error")).contains("No active Amazon SKU");
        org.mockito.Mockito.verifyNoInteractions(client);
        assertThat(controller.globalImage(UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
    }
    @Test void locationsCanOnlyBeChangedWhenUnused(){
        var catalog=context.getBean(CatalogRepository.class);
        UUID unused=catalog.addLocation(tenant,"QA-EMPTY","Empty shelf");
        catalog.changeLocation(tenant,unused,"QA-RENAMED","Renamed shelf",false);
        assertThat(catalog.listLocations(tenant)).anyMatch(l->l.id().equals(unused)&&l.code().equals("QA-RENAMED"));
        assertThatThrownBy(()->catalog.changeLocation(UUID.randomUUID(),unused,"OTHER","Other",true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("this account");
        UUID duplicate=catalog.addLocation(tenant,"QA-DUPLICATE","Duplicate");
        assertThatThrownBy(()->catalog.changeLocation(tenant,unused,"QA-DUPLICATE","Other",false)).hasMessageContaining("already exists");
        assertThatThrownBy(()->catalog.changeLocation(tenant,unused,"bad code","Other",false)).hasMessageContaining("location code");
        var f=fixture("INVOICE");
        catalog.setDefaultLocation(tenant,f.product(),unused);
        assertThatThrownBy(()->catalog.changeLocation(tenant,unused,"QA-NEW","Other",false)).hasMessageContaining("1 catalogue location assignments");
        assertThatThrownBy(()->catalog.changeLocation(tenant,unused,"","",true)).hasMessageContaining("catalogue location assignments");
        assertThatThrownBy(()->catalog.changeLocation(tenant,f.location(),"","",true)).hasMessageContaining("MAIN");
        UUID historical=catalog.addLocation(tenant,"QA-HISTORY","Historical shelf");
        tx.executeWithoutResult(s->{setTenant();jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,entry_type,quantity,source_type,notes,location_id,idempotency_key,occurred_at) VALUES (?,?,'ADJUSTMENT',1,'MANUAL','Location protection test',?,?,now())",tenant,f.product(),historical,"location-test-"+UUID.randomUUID());});
        assertThatThrownBy(()->catalog.changeLocation(tenant,historical,"NEW","New",false)).hasMessageContaining("1 inventory ledger records");
        assertThatThrownBy(()->catalog.changeLocation(tenant,historical,"","",true)).hasMessageContaining("Historical records");
        catalog.changeLocation(tenant,duplicate,"","",true);
        assertThat(catalog.listLocations(tenant)).noneMatch(l->l.id().equals(duplicate));
    }
    Fixture fixture(String type){
        return tx.execute(s->{
            setTenant();UUID product=UUID.randomUUID(),global=UUID.randomUUID(),document=UUID.randomUUID(),sourceLine=UUID.randomUUID(),po=UUID.randomUUID(),line=UUID.randomUUID();
            UUID session=receiving.createSession(tenant,actor,"QA-"+document,"USD",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"VALUE");
            jdbc.update("INSERT INTO global_catalog_products(id,canonical_name,requires_expiration_date) VALUES (?,'QA yogurt',true)",global);
            jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,?)",product,tenant,global,"QA-"+product);
            UUID location=jdbc.queryForObject("SELECT location_id FROM account_catalog_item_locations WHERE tenant_id=? AND account_catalog_item_id=? AND is_default",UUID.class,tenant,product);
            jdbc.update("INSERT INTO receiving_documents(id,tenant_id,receiving_session_id,vendor_id,document_type,document_number,original_filename,file_sha256,currency) VALUES (?,?,?,?,?,?,? ,?,'USD')",document,tenant,session,vendor,type,"QA-"+document,"qa.csv",document.toString().replace("-","").repeat(2));
            jdbc.update("INSERT INTO receiving_document_lines(id,tenant_id,receiving_document_id,account_catalog_item_id,source_description,ordered_quantity,invoice_unit_cost,currency) VALUES (?,?,?,?,'QA yogurt',10,2,'USD')",sourceLine,tenant,document,product);
            jdbc.update("INSERT INTO purchase_orders(id,tenant_id,receiving_session_id,receiving_document_id,vendor_id,po_number,created_by) VALUES (?,?,?,?,?,?,?)",po,tenant,session,document,vendor,"QA-"+po,user);
            jdbc.update("INSERT INTO purchase_order_items(id,tenant_id,purchase_order_id,receiving_line_id,account_catalog_item_id,description,ordered_quantity,unit_cost,currency,invoice_unit,units_per_case) VALUES (?,?,?,?,?,'QA yogurt',10,2,'USD','EACH',1)",line,tenant,po,sourceLine,product);
            return new Fixture(session,document,line,product,location);
        });
    }
    void receive(Fixture f,int amount){
        long start=System.nanoTime();
        work.receive(tenant,actor,f.line(),BigDecimal.valueOf(amount),expiry,"SELLABLE",f.location(),"QA receipt");
        System.out.println("RECEIVE_OPERATION_MS="+((System.nanoTime()-start)/1_000_000));
    }
    BigDecimal stock(Fixture f){return tx.execute(s->{setTenant();return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",BigDecimal.class,tenant,f.product());});}
    UUID receipt(Fixture f){return work.receipts(tenant,f.line()).getFirst().id();}

    @Test void separateDocumentsPartialContinuationAndIdempotency(){
        var a=fixture("INVOICE");var b=fixture("PACKING_LIST");
        assertThat(work.documentPage(tenant,"packing list",0,25).items()).extracting(ReceivingWorkflowRepository.Document::id).containsExactly(b.document());
        assertThat(work.lines(tenant,List.of(a.document(),b.document()))).hasSize(2);
        UUID request=UUID.randomUUID();
        assertThat(work.execute(tenant,request,"receive",a.line(),"test",()->receive(a,3))).isTrue();
        assertThat(work.execute(tenant,request,"receive",a.line(),"test",()->receive(a,3))).isFalse();
        assertThatThrownBy(()->work.execute(tenant,request,"receive",b.line(),"other",()->receive(b,3))).isInstanceOf(IllegalArgumentException.class);
        receive(a,2);receive(b,4);
        assertThat(stock(a)).isEqualByComparingTo("5");assertThat(stock(b)).isEqualByComparingTo("4");
        assertThatThrownBy(()->receive(a,6)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock(a)).isEqualByComparingTo("5");
        assertThat(work.documents(UUID.randomUUID(),List.of(a.document()))).isEmpty();
        assertThatThrownBy(()->work.receipts(UUID.randomUUID(),a.line())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void untouchedReceiptUndoPreservesHistoryAndCannotBeRepublished(){
        var f=fixture("INVOICE");receive(f,4);UUID receipt=receipt(f);
        work.undo(tenant,actor,f.line(),receipt,"Entered wrong quantity");
        assertThat(stock(f)).isEqualByComparingTo("0");
        assertThat(work.receipts(tenant,f.line())).hasSize(1).first().extracting(ReceivingWorkflowRepository.Receipt::undone).isEqualTo(true);
        receiving.publishReceipts(tenant,f.session());assertThat(stock(f)).isEqualByComparingTo("0");
        assertThatThrownBy(()->work.undo(tenant,actor,f.line(),receipt,"Again")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->work.removeDocument(tenant,actor,f.document())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->receiving.receive(tenant,f.session(),f.line(),actor,BigDecimal.ZERO,BigDecimal.ONE,
            new BigDecimal("2"),expiry,"SELLABLE","",BigDecimal.ZERO,BigDecimal.ZERO,f.location(),false))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked");
        receive(f,2);assertThat(stock(f)).isEqualByComparingTo("2");
    }
    @Test void usedStockCannotBeUndoneButAdjustmentsRemainTraceable(){
        var f=fixture("INVOICE");receive(f,6);UUID receipt=receipt(f);
        inventory.adjustInventory(tenant,actor,f.product(),expiry,f.location(),new BigDecimal("-1"),"DAMAGE","Damaged after receipt");
        assertThat(work.receipts(tenant,f.line()).getFirst().blockedReason()).contains("adjusted");
        assertThatThrownBy(()->work.undo(tenant,actor,f.line(),receipt,"Cannot rewrite")).isInstanceOf(IllegalArgumentException.class);
        work.adjust(tenant,actor,f.line(),receipt,BigDecimal.ONE,"DECREASE","DONATION","One donated");
        assertThat(stock(f)).isEqualByComparingTo("4");
        assertThat(work.lines(tenant,List.of(f.document())).getFirst().received()).isEqualByComparingTo("6");
    }

    @Test void itemHistoryIncludesAllDatedAndFifoBatchesAndIsTenantScoped(){
        var f=fixture("INVOICE");receive(f,3);
        tx.executeWithoutResult(s->{setTenant();jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,entry_type,quantity,expiration_date,source_type,notes,location_id,occurred_at,idempotency_key) VALUES (?,?,'ADJUSTMENT',2,NULL,'MANUAL','FIFO test',?,now(),?)",tenant,f.product(),f.location(),"test-"+UUID.randomUUID());});
        assertThat(inventory.movements(tenant,f.product(),null,null,true,0)).hasSize(2);
        assertThat(inventory.movements(tenant,f.product(),null,f.location(),false,0)).hasSize(1);
        assertThat(inventory.movements(tenant,f.product(),expiry,f.location(),false,0)).hasSize(1);
        assertThat(inventory.movements(UUID.randomUUID(),f.product(),null,null,true,0)).isEmpty();
    }
    @Test void pricingCooldownPersistsAndIsTenantScoped(){
        UUID connection=UUID.randomUUID();
        tx.executeWithoutResult(s->{setTenant();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test-only','ACTIVE','Pricing test','America/Los_Angeles',now())",connection,tenant,"test-"+connection);});
        var amazon=org.mockito.Mockito.mock(com.nextaicommerce.platform.sync.AmazonSpApiClient.class);
        var pricing=new com.nextaicommerce.platform.sync.AmazonCompetitivePricingService(jdbc,amazon,tx,new tools.jackson.databind.ObjectMapper());
        pricing.refresh(tenant,connection,"ATVPDKIKX0DER");
        java.util.function.Supplier<java.time.Instant> deadline=()->tx.execute(s->{setTenant();return jdbc.queryForObject("SELECT buy_box_refresh_after FROM marketplace_connections WHERE tenant_id=? AND id=?",java.sql.Timestamp.class,tenant,connection).toInstant();});
        var first=deadline.get();
        assertThat(first).isAfter(java.time.Instant.now().plusSeconds(10790));
        pricing.refresh(tenant,connection,"ATVPDKIKX0DER");
        pricing.refresh(UUID.randomUUID(),connection,"ATVPDKIKX0DER");
        assertThat(deadline.get()).isEqualTo(first);
        org.mockito.Mockito.verifyNoInteractions(amazon);
    }

    @Test void orderTabsCountUnitsAndCombinePendingWithoutIncludingShippedOrders(){
        UUID connection=UUID.randomUUID();
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test-only','ACTIVE','Tab test','America/Los_Angeles',now())",connection,tenant,"test-"+connection);
            var statuses=List.of("Pending","Unshipped","Shipped - Waiting for Pick Up","Shipped","Partially shipped","Cancelled");
            for(int n=0;n<statuses.size();n++){
                jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,fulfillment_state) VALUES (?,?,'ATVPDKIKX0DER',?,now(),?,?)",tenant,connection,"TAB-"+n,statuses.get(n),n<2?"READY_TO_SHIP":"SHIPPED");
                jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,?,?,?,3)",tenant,connection,"TAB-"+n,"ITEM-"+n,"SKU-"+n);
            }
            jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,'TAB-0','SECOND-ITEM','SECOND-SKU',1)",tenant,connection);
        });
        var repo=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
        var tabs=repo.tabs(tenant,connection).stream().collect(java.util.stream.Collectors.toMap(com.nextaicommerce.platform.orders.OrderRepository.OrderTab::key,t->t));
        assertThat(tabs.get("ALL").orders()).isEqualTo(6);assertThat(tabs.get("ALL").units()).isEqualTo(19);
        assertThat(tabs.get("UNSHIPPED").orders()).isEqualTo(2);assertThat(tabs.get("UNSHIPPED").units()).isEqualTo(7);
        assertThat(tabs).doesNotContainKeys("PENDING","READY_TO_SHIP");
        for(String key:List.of("UNSHIPPED","WAITING_FOR_PICKUP","SHIPPED")){
            var page=repo.orders(tenant,connection,key,"",0,25);
            assertThat(page.total()).isEqualTo(tabs.get(key).orders()).isEqualTo(key.equals("UNSHIPPED")?2:1);
        }
        assertThat(repo.orders(tenant,connection,"WAITING_FOR_PICKUP","",0,25).rows().getFirst().amazonOrderId()).isEqualTo("TAB-2");
        assertThat(repo.orders(tenant,connection,"SHIPPED","",0,25).rows().getFirst().amazonOrderId()).isEqualTo("TAB-3");
        assertThat(repo.tabs(tenant,UUID.randomUUID())).allMatch(t->t.orders()==0&&t.units()==0);
        String before=repo.streamVersion(tenant,connection);
        assertThat(repo.setPickupOverride(tenant,connection,"TAB-0",true,"operator@test")).isTrue();
        assertThat(repo.pickupOverrides(tenant,connection)).containsExactly("TAB-0");
        assertThat(repo.streamVersion(tenant,connection)).isNotEqualTo(before);
        assertThat(repo.orders(tenant,connection,"UNSHIPPED","",0,25).total()).isEqualTo(1);
        assertThat(repo.orders(tenant,connection,"WAITING_FOR_PICKUP","",0,25).total()).isEqualTo(2);
        // Simulate an Amazon refresh with its unchanged Pending status.
        tx.executeWithoutResult(s->{setTenant();jdbc.update("UPDATE amazon_orders SET order_status='Pending',updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id='TAB-0'",tenant,connection);});
        var restarted=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
        assertThat(restarted.pickupOverrides(tenant,connection)).contains("TAB-0");
        assertThat(restarted.orders(tenant,connection,"WAITING_FOR_PICKUP","TAB-0",0,25).rows().getFirst().amazonStatus()).isEqualTo("Pending");
        assertThat(repo.setPickupOverride(tenant,UUID.randomUUID(),"TAB-0",false,"other")).isFalse();
        assertThat(repo.setPickupOverride(tenant,connection,"TAB-3",true,"operator@test")).isFalse();
        assertThat(repo.setPickupOverride(tenant,connection,"TAB-0",false,"operator@test")).isTrue();
        assertThat(repo.pickupOverrides(tenant,connection)).isEmpty();
        assertThat(repo.orders(tenant,connection,"UNSHIPPED","",0,25).total()).isEqualTo(2);
    }

    @Test void orderReportAndApiReuseItemsWithoutDuplicatingSales() throws Exception {
        UUID connection=UUID.randomUUID();
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test-only','ACTIVE','Import test','America/Los_Angeles',now())",connection,tenant,"test-"+connection);
        });
        var json=new tools.jackson.databind.ObjectMapper();
        var normalizer=new com.nextaicommerce.platform.sync.AmazonReportNormalizer(jdbc,tx,json,null);
        var now=java.time.Instant.now();
        var job=new com.nextaicommerce.platform.sync.AmazonSyncStore.Job(UUID.randomUUID(),tenant,UUID.randomUUID(),connection,"ORDERS_30_DAY","STARTUP_ORDERS","ATVPDKIKX0DER",now.minusSeconds(2592000),now,null,0,0,false,null);
        for(boolean reportFirst:List.of(true,false)){
            String order=reportFirst?"REPORT-FIRST":"API-FIRST";
            String report="amazon-order-id\tpurchase-date\torder-status\tfulfillment-channel\tsku\tasin\tquantity\titem-price\tcurrency\n"+order+"\t2026-09-13T19:00:00Z\tUnshipped\tMerchant\tSKU\tASIN\t1\t10.00\tUSD\n";
            normalizer.normalizeOrderPage(job,json.readTree("{\"payload\":{\"Orders\":[{\"AmazonOrderId\":\""+order+"\",\"PurchaseDate\":\"2026-09-13T19:00:00Z\",\"OrderStatus\":\"Unshipped\",\"FulfillmentChannel\":\"MFN\"}]}}"),null);
            var items=json.readTree("{\"payload\":{\"OrderItems\":[{\"OrderItemId\":\""+order+"-ITEM\",\"SellerSKU\":\"SKU\",\"ASIN\":\"ASIN\",\"QuantityOrdered\":1,\"ItemPrice\":{\"Amount\":\"10.00\",\"CurrencyCode\":\"USD\"}}]}}");
            if(reportFirst)normalizer.normalize(job,report,null);
            normalizer.normalizeOrderItems(job,order,items,null);
            normalizer.normalize(job,report,null);
            normalizer.normalizeOrderItems(job,order,items,null);
            tx.executeWithoutResult(s->{setTenant();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",Integer.class,tenant,connection,order)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT sum(item_price) FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",BigDecimal.class,tenant,connection,order)).isEqualByComparingTo("10.00");
            });
            var twoItems=json.readTree("{\"payload\":{\"OrderItems\":[{\"OrderItemId\":\""+order+"-ITEM\",\"SellerSKU\":\"SKU\",\"ASIN\":\"ASIN\",\"QuantityOrdered\":1,\"ItemPrice\":{\"Amount\":\"10.00\",\"CurrencyCode\":\"USD\"}},{\"OrderItemId\":\""+order+"-ITEM-2\",\"SellerSKU\":\"SKU\",\"ASIN\":\"ASIN\",\"QuantityOrdered\":1,\"ItemPrice\":{\"Amount\":\"10.00\",\"CurrencyCode\":\"USD\"}}]}}");
            normalizer.normalizeOrderItems(job,order,twoItems,null);
            normalizer.normalize(job,report.replace("\t1\t10.00\t","\t2\t20.00\t"),null);
            normalizer.normalizeOrderItems(job,order,twoItems,null);
            tx.executeWithoutResult(s->{setTenant();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",Integer.class,tenant,connection,order)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT sum(item_price) FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",BigDecimal.class,tenant,connection,order)).isEqualByComparingTo("20.00");
            });
        }
    }

    @Test void skippedSyncDoesNotAdvanceWatermarkAndFinishedRunRepairIsGuarded() throws Exception {
        UUID connection=UUID.randomUUID();
        var old=java.time.Instant.parse("2026-09-10T00:00:00Z");
        var end=old.plusSeconds(86400);
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test-only','ACTIVE','Recovery test','America/Los_Angeles',now())",connection,tenant,"test-"+connection);
            jdbc.update("INSERT INTO amazon_sync_watermarks(tenant_id,marketplace_connection_id,dataset,high_watermark,last_success_at) VALUES (?,?,'ORDER_CHANGES',?,?)",tenant,connection,java.sql.Timestamp.from(old),java.sql.Timestamp.from(old));
        });
        var store=new com.nextaicommerce.platform.sync.AmazonSyncStore(jdbc,tx);
        for(String status:List.of("SKIPPED","COMPLETED")){
            UUID run=UUID.randomUUID(),finalJob=UUID.randomUUID();
            tx.executeWithoutResult(s->{setTenant();
                jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end,status) VALUES (?,?,?,'INCREMENTAL','ORDER_CHANGES',?,?,'RUNNING')",run,tenant,connection,java.sql.Timestamp.from(old),java.sql.Timestamp.from(end));
                jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status,required_for_ready) VALUES (?,?,?,'ORDERS_API_DELTA',1,?,false)",tenant,run,connection,status);
                jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,required_for_ready) VALUES (?,?,?,?,'FINAL_RECONCILIATION',2,false)",finalJob,tenant,run,connection);
            });
            store.finishRun(new com.nextaicommerce.platform.sync.AmazonSyncStore.Job(finalJob,tenant,run,connection,"FINAL_RECONCILIATION","ORDER_CHANGES","ATVPDKIKX0DER",old,end,null,0,0,false,null),"Done");
            var actual=tx.execute(s->{setTenant();return jdbc.queryForObject("SELECT high_watermark FROM amazon_sync_watermarks WHERE tenant_id=? AND marketplace_connection_id=? AND dataset='ORDER_CHANGES'",java.sql.Timestamp.class,tenant,connection).toInstant();});
            assertThat(actual).isEqualTo(status.equals("SKIPPED")?old:end);
        }
        var repair=new String(getClass().getResourceAsStream("/db/migration/V68__repair_finished_sync_run_tracking.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("UPDATE marketplace_sync_runs SET status='RUNNING' WHERE tenant_id=? AND marketplace_connection_id=?",tenant,connection);
            jdbc.execute(repair);setTenant();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_runs WHERE tenant_id=? AND marketplace_connection_id=? AND status='RUNNING'",Integer.class,tenant,connection)).isZero();
        });
    }

    @Test void catalogueCountsAndRowsAgreeForSecondaryIdentifiersAndMappedAsins(){
        var first=fixture("INVOICE");var other=fixture("INVOICE");UUID connection=UUID.randomUUID(),mapping=UUID.randomUUID();
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) SELECT global_product_id,'MPN','PRIMARY-'||id,true FROM account_catalog_items WHERE id=?",first.product());
            jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) SELECT global_product_id,'MPN','SECONDARY-NEEDLE',false FROM account_catalog_items WHERE id=?",first.product());
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','synthetic','ATVPDKIKX0DER','test-only','ACTIVE','Test store','America/Los_Angeles',now())",connection,tenant);
            jdbc.update("INSERT INTO marketplace_sku_mappings(id,tenant_id,marketplace_connection_id,marketplace_sku,asin,account_catalog_item_id,quantity_per_marketplace_unit,status) VALUES (?,?,?,'QA-MAPPED-SKU','B0TESTASIN1',?,1,'ACTIVE')",mapping,tenant,connection,first.product());
        });
        var catalog=context.getBean(CatalogRepository.class);
        for(String query:List.of("SECONDARY-NEEDLE","B0TESTASIN1","QA-MAPPED-SKU")){
            var result=catalog.pageAccountItems(tenant,query,0,25);
            assertThat(result.total()).isEqualTo(1);assertThat(result.rows()).extracting(CatalogRepository.AccountItemView::id).containsExactly(first.product());
        }
        assertThat(catalog.searchAccountItems(tenant,"B0TESTASIN1",20)).extracting(CatalogRepository.AccountItemView::id).containsExactly(first.product());
        assertThat(catalog.pageAccountItems(tenant,"%",0,25).rows()).isEmpty();
    }
    @Test void catalogueCacheInvalidatesOnlyAfterCommittedWrites(){
        var f=fixture("INVOICE");var reads=context.getBean(com.nextaicommerce.platform.catalog.CatalogReadService.class);
        var first=reads.page(tenant,"",0,25);
        assertThat(reads.page(tenant,"",0,25)).isSameAs(first);
        tx.executeWithoutResult(s->{setTenant();jdbc.update("UPDATE account_catalog_items SET display_name='Rolled back' WHERE id=?",f.product());s.setRollbackOnly();});
        assertThat(reads.page(tenant,"",0,25)).isSameAs(first);
        tx.executeWithoutResult(s->{setTenant();jdbc.update("UPDATE account_catalog_items SET display_name='Updated catalogue item' WHERE id=?",f.product());});
        assertThat(reads.page(tenant,"",0,25).page().rows().getFirst().name()).isEqualTo("Updated catalogue item");
    }
    @Test void catalogueTwentyThousandItemsMeetsTheLocalQueryBudget(){
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("INSERT INTO global_catalog_products(id,canonical_name,brand) SELECT md5(?||n)::uuid,'Grocery item '||lpad(n::text,5,'0'),CASE WHEN n%250=0 THEN 'Egglife' ELSE 'Synthetic QA' END FROM generate_series(1,20000) n",tenant.toString());
            jdbc.update("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) SELECT ?,md5(?||n)::uuid,'PERF-'||n FROM generate_series(1,20000) n",tenant,tenant.toString());
            jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,is_default) SELECT ?,?,id,account_sku,2.50,true FROM account_catalog_items WHERE tenant_id=?",tenant,vendor,tenant);
        });
        jdbc.execute("ANALYZE");
        var reads=context.getBean(com.nextaicommerce.platform.catalog.CatalogReadService.class);
        long start=System.nanoTime();var all=reads.page(tenant,"",0,25);long firstMs=(System.nanoTime()-start)/1_000_000;
        start=System.nanoTime();var search=reads.page(tenant,"Egglife",0,25);long searchMs=(System.nanoTime()-start)/1_000_000;
        start=System.nanoTime();reads.page(tenant,"Egglife",0,25);long cachedMicros=(System.nanoTime()-start)/1_000;
        System.out.printf("CATALOGUE_BENCHMARK products=20000 page_ms=%d search_ms=%d cached_us=%d%n",firstMs,searchMs,cachedMicros);
        assertThat(all.page().total()).isEqualTo(20000);assertThat(all.page().rows()).hasSize(25);
        assertThat(search.page().total()).isEqualTo(80);assertThat(search.page().rows()).hasSize(25);
        assertThat(firstMs).isLessThan(1000);assertThat(searchMs).isLessThan(1000);
        var catalog=context.getBean(CatalogRepository.class);
        start=System.nanoTime();var picker=catalog.searchAccountItems(tenant,"Egglife",20);catalog.pickerImages(tenant,picker.stream().map(CatalogRepository.AccountItemView::id).toList());long pickerMs=(System.nanoTime()-start)/1_000_000;
        System.out.printf("CATALOGUE_PICKER_BENCHMARK products=20000 search_ms=%d%n",pickerMs);
        assertThat(picker).hasSize(20);assertThat(pickerMs).isLessThan(1000);
    }
    @Test void closeAllowsAlreadyPostedHistoricalUndatedReceiptWithoutAddingStock(){
        var f=fixture("INVOICE");receive(f,3);
        tx.executeWithoutResult(s->{setTenant();jdbc.update("UPDATE receiving_line_receipts SET expiration_date=NULL WHERE tenant_id=? AND id=?",tenant,receipt(f));});
        work.closeDocument(tenant,actor,f.document(),"Physical count completed; no further receipts",false,new BigDecimal("3"),new BigDecimal("7"));
        assertThat(work.documents(tenant,List.of(f.document())).getFirst().closed()).isTrue();
        assertThat(stock(f)).isEqualByComparingTo("3");
    }
    @Test void closeStillRejectsUnpostedReceiptMissingRequiredExpiration(){
        var f=fixture("INVOICE");receive(f,3);UUID receipt=receipt(f);
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("UPDATE receiving_line_receipts SET expiration_date=NULL WHERE tenant_id=? AND id=?",tenant,receipt);
            jdbc.update("DELETE FROM inventory_ledger_entries WHERE tenant_id=? AND source_type='RECEIVING' AND source_id=?",tenant,receipt);
        });
        assertThatThrownBy(()->work.closeDocument(tenant,actor,f.document(),"No further receipts",false,new BigDecimal("3"),new BigDecimal("7")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required expiration date");
        assertThat(work.documents(tenant,List.of(f.document())).getFirst().closed()).isFalse();
        assertThat(stock(f)).isEqualByComparingTo("0");
    }
    @Test void physicalCountKeepsDistinctItemCodesSeparateAtTheSameExpiration(){
        var a=fixture("INVOICE");var b=fixture("INVOICE");
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("UPDATE account_catalog_items SET account_sku='381003' WHERE tenant_id=? AND id=?",tenant,a.product());
            jdbc.update("UPDATE account_catalog_items SET account_sku='381005' WHERE tenant_id=? AND id=?",tenant,b.product());
        });
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,List.of(
            new InventoryRepository.PhysicalCountRow(2,"381005",new BigDecimal("60"),expiry,"MAIN"),
            new InventoryRepository.PhysicalCountRow(3,"381003",new BigDecimal("156"),expiry,"MAIN"),
            new InventoryRepository.PhysicalCountRow(4,"381003",new BigDecimal("144"),expiry.plusDays(22),"MAIN")));
        assertThat(stock(a)).isEqualByComparingTo("300");assertThat(stock(b)).isEqualByComparingTo("60");
        tx.executeWithoutResult(s->{setTenant();assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=?",BigDecimal.class,tenant,a.product(),expiry)).isEqualByComparingTo("156");});
    }
    @Test void closePartialRequiresCurrentPreviewAndLocksOriginalReceipts(){
        var f=fixture("INVOICE");receive(f,3);
        assertThatThrownBy(()->work.closeDocument(tenant,actor,f.document(),"Balance cancelled",false,BigDecimal.ZERO,BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        work.closeDocument(tenant,actor,f.document(),"Supplier cannot deliver remainder",true,new BigDecimal("3"),new BigDecimal("7"));
        var d=work.documents(tenant,List.of(f.document())).getFirst();
        assertThat(d.closed()).isTrue();assertThat(d.partial()).isTrue();assertThat(stock(f)).isEqualByComparingTo("3");
        assertThatThrownBy(()->receive(f,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->receiving.reopenLine(tenant,f.session(),f.line(),actor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->work.undo(tenant,actor,f.line(),receipt(f),"No")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->work.removeDocument(tenant,actor,f.document())).isInstanceOf(IllegalArgumentException.class);
        work.adjust(tenant,actor,f.line(),receipt(f),BigDecimal.ONE,"DECREASE","LOSS","Stock correction");
        assertThat(stock(f)).isEqualByComparingTo("2");
    }
    @Test void removeOnlyNeverReceivedDocumentsAndValidateExpiredReceipts(){
        var unused=fixture("INVOICE");work.removeDocument(tenant,actor,unused.document());
        assertThat(work.documents(tenant,List.of(unused.document()))).isEmpty();
        assertThatThrownBy(()->receive(unused,1)).isInstanceOf(IllegalArgumentException.class);
        var expired=fixture("PACKING_LIST");
        assertThatThrownBy(()->work.receive(tenant,actor,expired.line(),BigDecimal.ONE,LocalDate.now().minusDays(1),"SELLABLE",expired.location(),"")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock(expired)).isEqualByComparingTo("0");
        work.receive(tenant,actor,expired.line(),BigDecimal.ONE,LocalDate.now().minusDays(1),"EXPIRED",expired.location(),"Expired stock visible");
        assertThat(stock(expired)).isEqualByComparingTo("1");
    }
    UUID reserve(Fixture f,int quantity){
        return tx.execute(s->{
            setTenant();UUID connection=UUID.randomUUID(),orderItem=UUID.randomUUID(),reservation=UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'QA','not-a-real-credential','QA only','America/Los_Angeles',now())",connection,tenant,"qa-"+connection);
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status) VALUES (?,?,'QA','QA-ORDER','Unshipped')",tenant,connection);
            jdbc.update("INSERT INTO amazon_order_items(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku) VALUES (?,?,?,'QA-ORDER','QA-ITEM','QA-SKU')",orderItem,tenant,connection);
            jdbc.update("INSERT INTO order_inventory_reservations(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,account_catalog_item_id,location_id,expiration_date,quantity) VALUES (?,?,?,'QA-ORDER',?,?,?,?,?)",reservation,tenant,connection,orderItem,f.product(),f.location(),expiry,quantity);
            return reservation;
        });
    }
    @Test void activeAndShippedOrdersLockReceiptsAndProtectAdjustmentFloor(){
        var f=fixture("INVOICE");receive(f,8);UUID r=receipt(f),reservation=reserve(f,3);
        assertThat(work.receipts(tenant,f.line()).getFirst().blockedReason()).contains("active orders");
        assertThatThrownBy(()->work.undo(tenant,actor,f.line(),r,"Blocked")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->work.adjust(tenant,actor,f.line(),r,new BigDecimal("6"),"DECREASE","LOSS","Blocked")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock(f)).isEqualByComparingTo("8");
        tx.executeWithoutResult(s->{setTenant();jdbc.update("UPDATE order_inventory_reservations SET status='SHIPPED' WHERE tenant_id=? AND id=?",tenant,reservation);});
        assertThatThrownBy(()->work.undo(tenant,actor,f.line(),r,"Sold")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void expiredPhysicalCountWritesDeltaAndLeavesUnlistedBatches(){
        var f=fixture("INVOICE");receive(f,4);LocalDate expired=LocalDate.now().minusDays(4);
        var rows=List.of(new InventoryRepository.PhysicalCountRow(2,"QA-"+f.product(),new BigDecimal("2"),expired,"MAIN"));
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,rows);
        assertThat(stock(f)).isEqualByComparingTo("6");
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,rows);
        assertThat(stock(f)).isEqualByComparingTo("6");
        reserve(f,2);
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,rows,true);
        assertThat(stock(f)).isEqualByComparingTo("2");
    }
    UUID mappedOrder(Fixture f,int quantity,String status){
        UUID connection=UUID.randomUUID(),mapping=UUID.randomUUID();
        tx.executeWithoutResult(s->{setTenant();
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'QA','test-only','ACTIVE','Shelf test','America/Los_Angeles',now()-interval '2 days')",connection,tenant,"shelf-"+connection);
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,last_update_date,order_status,fulfillment_channel,fulfillment_state) VALUES (?,?,'QA','SHELF-ORDER',now()-interval '1 day',now()-interval '1 hour',?,'MFN','INVENTORY_SHORTAGE')",tenant,connection,status);
            jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,'SHELF-ORDER','SHELF-ITEM','SHELF-SKU',?)",tenant,connection,quantity);
            jdbc.update("INSERT INTO marketplace_sku_mappings(id,tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku) VALUES (?,?,?,?,'SHELF-SKU')",mapping,tenant,connection,f.product());
            jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) VALUES (?,?,?,1)",tenant,mapping,f.product());
        });
        return connection;
    }
    @Test void completedOrderGetsOneZeroAuditAndNeverConsumesNewShelfStock(){
        var f=fixture("INVOICE");receive(f,10);UUID connection=mappedOrder(f,4,"Shipped - Delivered to Buyer");
        var repo=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
        tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        assertThat(stock(f)).isEqualByComparingTo("10");
        tx.executeWithoutResult(s->{setTenant();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND entry_type='SHIPMENT_UNRECORDED' AND quantity=0",Integer.class,tenant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT fulfillment_state FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=?",String.class,tenant,connection)).isEqualTo("SHIPPED");
        });
        assertThat(repo.tabs(tenant,connection).stream().filter(t->t.key().equals("INVENTORY_SHORTAGE")).findFirst().orElseThrow().orders()).isZero();
    }
    @Test void packingDeductsOnceCountExcludesPackedAndUndoRestoresRecordedStock(){
        var f=fixture("INVOICE");receive(f,10);UUID connection=mappedOrder(f,4,"Pending");
        var repo=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
        tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        tx.executeWithoutResult(s->assertThat(repo.setPickupOverride(tenant,connection,"SHELF-ORDER",true,actor)).isTrue());
        assertThat(stock(f)).isEqualByComparingTo("6");
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,List.of(new InventoryRepository.PhysicalCountRow(1,"QA-"+f.product(),new BigDecimal("6"),expiry,"MAIN")));
        tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        tx.executeWithoutResult(s->repo.setPickupOverride(tenant,connection,"SHELF-ORDER",true,actor));
        assertThat(stock(f)).isEqualByComparingTo("6");
        tx.executeWithoutResult(s->{setTenant();assertThat(jdbc.queryForObject("SELECT count(*) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",Integer.class,tenant)).isZero();});
        tx.executeWithoutResult(s->repo.setPickupOverride(tenant,connection,"SHELF-ORDER",false,actor));
        assertThat(stock(f)).isEqualByComparingTo("10");
        tx.executeWithoutResult(s->repo.setPickupOverride(tenant,connection,"SHELF-ORDER",true,actor));
        assertThat(stock(f)).isEqualByComparingTo("6");
    }
    @Test void lowerShelfCountRebuildsOpenDemandAndKeepsUnchangedCountEvidence(){
        var f=fixture("INVOICE");receive(f,10);UUID connection=mappedOrder(f,8,"Unshipped");
        var repo=new com.nextaicommerce.platform.orders.OrderRepository(jdbc);
        tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        var rows=List.of(new InventoryRepository.PhysicalCountRow(1,"QA-"+f.product(),new BigDecimal("3"),expiry,"MAIN"));
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,rows);
        tx.executeWithoutResult(s->repo.reconcile(tenant,connection));
        assertThat(stock(f)).isEqualByComparingTo("3");
        assertThat(repo.tabs(tenant,connection).stream().filter(t->t.key().equals("INVENTORY_SHORTAGE")).findFirst().orElseThrow().orders()).isEqualTo(1);
        inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,rows);
        tx.executeWithoutResult(s->{setTenant();assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=? AND source_type='PHYSICAL_COUNT' AND quantity=0",Integer.class,tenant)).isEqualTo(1);});
    }
    @Test void packFeesOverageAndShortageStayConsistent(){
        var f=fixture("INVOICE");
        tx.executeWithoutResult(s->{setTenant();jdbc.update("UPDATE purchase_order_items SET invoice_unit='CASE',ordered_quantity=2 WHERE tenant_id=? AND id=?",tenant,f.line());});
        work.prepare(tenant,actor,f.line(),new BigDecimal("6"),new BigDecimal(".1"),new BigDecimal(".2"));
        assertThat(work.lines(tenant,List.of(f.document())).getFirst().expected()).isEqualByComparingTo("12");
        work.receive(tenant,actor,f.line(),new BigDecimal("14"),expiry,"OVER_SHIPPED",f.location(),"Two extra");
        assertThat(stock(f)).isEqualByComparingTo("14");
        assertThat(work.receipts(tenant,f.line())).hasSize(2);
        BigDecimal value=tx.execute(s->{setTenant();return jdbc.queryForObject("SELECT sum(quantity*unit_cost) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",BigDecimal.class,tenant,f.product());});
        assertThat(value).isEqualByComparingTo("27.6");
        assertThatThrownBy(()->work.prepare(tenant,actor,f.line(),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        var shortage=fixture("PACKING_LIST");
        work.receive(tenant,actor,shortage.line(),new BigDecimal("4"),null,"SHORT_SHIPPED",shortage.location(),"Not delivered");
        assertThat(stock(shortage)).isEqualByComparingTo("0");
        assertThat(work.lines(tenant,List.of(shortage.document())).getFirst().remaining()).isEqualByComparingTo("6");
        assertThat(work.documents(tenant,List.of(shortage.document())).getFirst().hasHistory()).isTrue();
        assertThatThrownBy(()->work.removeDocument(tenant,actor,shortage.document())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->work.receive(tenant,actor,shortage.line(),new BigDecimal(".5"),expiry,"SELLABLE",shortage.location(),"")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pagedDocumentReadsStayBoundedAndFast(){
        var f=fixture("INVOICE");
        tx.executeWithoutResult(s->{setTenant();jdbc.update("""
            INSERT INTO receiving_documents(tenant_id,receiving_session_id,vendor_id,document_type,document_number,original_filename,file_sha256,currency)
            SELECT ?,?,?,'INVOICE','PERF-'||n,'synthetic-perf.csv',md5(n::text)||md5(n::text),'USD'
            FROM generate_series(1,2000) n
            """,tenant,f.session(),vendor);});
        List<Long> times=new ArrayList<>();
        for(int i=0;i<5;i++){
            long started=System.nanoTime();var page=work.documentPage(tenant,"PERF",i,25);
            times.add((System.nanoTime()-started)/1_000_000);
            assertThat(page.total()).isEqualTo(2000);assertThat(page.items()).hasSize(25);assertThat(page.page()).isEqualTo(i);
        }
        System.out.println("DOCUMENT_PAGE_2000_ROWS_MS="+times);
        assertThat(Collections.max(times)).isLessThan(1000L);
    }
    @Test void concurrentInventoryWriterFailsFastWithoutMutation() throws Exception{
        var f=fixture("INVOICE");
        try(var connection=source.getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))")){
                statement.setString(1,tenant.toString());statement.execute();
            }
            long started=System.nanoTime();
            assertThatThrownBy(()->receive(f,2)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another operation");
            assertThat((System.nanoTime()-started)/1_000_000).isLessThan(1000L);
            assertThat(stock(f)).isEqualByComparingTo("0");connection.rollback();
        }
        receive(f,2);assertThat(stock(f)).isEqualByComparingTo("2");
    }
    @Test void fullReceiptCloseAndDamagedUndoPreserveQuantities(){
        var f=fixture("INVOICE");receive(f,10);
        work.closeDocument(tenant,actor,f.document(),null,false,BigDecimal.TEN,BigDecimal.ZERO);
        assertThat(work.documents(tenant,List.of(f.document())).getFirst().partial()).isFalse();
        assertThat(stock(f)).isEqualByComparingTo("10");
        var damaged=fixture("INVOICE");
        work.receive(tenant,actor,damaged.line(),new BigDecimal("2"),null,"DAMAGED",damaged.location(),"");
        UUID first=receipt(damaged);
        work.receive(tenant,actor,damaged.line(),new BigDecimal("3"),null,"DAMAGED",damaged.location(),"");
        work.undo(tenant,actor,damaged.line(),first,"Wrong condition");
        assertThat(stock(damaged)).isEqualByComparingTo("0");
        BigDecimal claim=tx.execute(s->{setTenant();return jdbc.queryForObject("SELECT quantity FROM vendor_credit_requests WHERE tenant_id=? AND purchase_order_item_id=? AND status='OPEN'",BigDecimal.class,tenant,damaged.line());});
        assertThat(claim).isEqualByComparingTo("3");
    }
}
