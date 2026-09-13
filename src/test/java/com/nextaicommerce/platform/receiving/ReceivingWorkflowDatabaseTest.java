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
        assertThatThrownBy(()->inventory.reconcilePhysicalCountSnapshot(tenant,actor,UUID.randomUUID(),null,rows,true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("committed");
        assertThat(stock(f)).isEqualByComparingTo("6");
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
