package com.nextaicommerce.platform.catalog;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

class CatalogImportDatabaseTest {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static String schema;
    static UUID migratedProduct,migratedVendor;
    CatalogImportService service;
    UUID tenant,vendor,user;
    String email;
    final Map<String,String> mapping=Map.of("productName","Description","vendorItemCode","ItemCode",
        "listCost","Cost","identifier","UPC","identifierType","UPC");

    @BeforeAll static void setup() throws Exception {
        schema="catalog_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();
        }else{
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");
        }
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("81").load().migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("ALTER FUNCTION "+schema+".ensure_item_default_location() SET search_path TO "+schema);
        UUID ibcore=UUID.fromString("d018e963-2c60-4b41-9331-a6232e1981b2");migratedProduct=UUID.randomUUID();migratedVendor=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,'ibcore-migration-test','Ibcore')",ibcore);
        jdbc.update("INSERT INTO vendors(id,tenant_id,name,vendor_code,currency) VALUES (?,?,'KEHE','KEHE','USD')",migratedVendor,ibcore);
        jdbc.update("INSERT INTO vendors(tenant_id,name,vendor_code,currency) VALUES (?,'Outer Aisle','OA','USD')",ibcore);
        jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Retained product')",migratedProduct);
        jdbc.update("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'UNCHANGED')",ibcore,migratedProduct);
        jdbc.update("INSERT INTO global_product_vendor_codes(global_product_id,vendor_key,vendor_name,vendor_item_code,normalized_item_code,source_tenant_id) VALUES (?,'KEHE','KEHE','100040','100040',?)",migratedProduct,ibcore);
        jdbc.update("INSERT INTO global_product_packaging_versions(global_product_id,vendor_key,normalized_vendor_item_code,unit_of_measure,units_per_case) VALUES (?,'KEHE','100040','EA',12)",migratedProduct);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("82").load().migrate();
        // Reproduce an application-role migration where tenant RLS hid the vendor row.
        jdbc.update("UPDATE vendors SET distribution_center='' WHERE id=?",migratedVendor);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbc!=null&&schema.matches("catalog_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }
    @BeforeEach void fixture(){
        service=new CatalogImportService(jdbc,new ObjectMapper(),null);
        tenant=UUID.randomUUID();vendor=UUID.randomUUID();user=UUID.randomUUID();email=user+"@example.test";
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Import test')",tenant,tenant.toString());
        jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Test')",user,email);
        jdbc.update("INSERT INTO vendors(id,tenant_id,name,vendor_code,currency) VALUES (?,?,'Test vendor',?,'USD')",vendor,tenant,vendor.toString());
    }
    UUID stage(String rows){return tx.execute(s->service.stage(tenant,email,vendor,new MockMultipartFile("file","catalog.csv","text/csv",
        ("Description,ItemCode,Cost,UPC\n"+rows).getBytes(StandardCharsets.UTF_8))));}
    void approve(UUID id){tx.executeWithoutResult(s->service.approve(tenant,email,id,mapping));}
    UUID product(String barcode){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Existing product')",id);
        if(barcode!=null)jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,'UPC',?,true)",id,barcode);
        return id;}
    String normalized(String value){return value.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);}
    void code(UUID product,String code){jdbc.update("INSERT INTO global_product_vendor_codes(global_product_id,vendor_key,catalog_scope,vendor_name,vendor_item_code,normalized_item_code,source_tenant_id) VALUES (?,?,?,'Test vendor',?,?,?)",product,normalized(vendor.toString()),CatalogIdentity.scope(tenant,""),code,code,tenant);}

    /** Incident feedback loop: exercise the real approved bulk importer, never production. */
    @Test void anotherAccountImportPreservesExistingIdentityAndManualBundleMappings(){
        jdbc.update("UPDATE vendors SET vendor_code='KEHE',distribution_center='41' WHERE id=?",vendor);
        Map<String,String> fields=new HashMap<>(mapping);fields.put("accountSku","AccountSKU");
        java.util.function.BiFunction<String,UUID,UUID> importFor=(rows,ignored)->{
            UUID staged=tx.execute(t->service.stage(tenant,email,vendor,new MockMultipartFile("file","incident.csv","text/csv",("Description,ItemCode,Cost,UPC,AccountSKU\n"+rows).getBytes(StandardCharsets.UTF_8))));
            var progress=new CatalogImportProgress(jdbc);
            tx.executeWithoutResult(t->{service.validate(tenant,staged,fields);progress.queue(tenant,staged,service.load(tenant,staged).totalRows());});
            var worker=new CatalogImportWorker(service,progress,new com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper(jdbc,tx));
            // Invoke the real worker synchronously with captured account arguments; no scheduler or Amazon calls.
            UUID capturedTenant=tenant;String capturedActor=email;
            tx.executeWithoutResult(t->worker.start(capturedTenant,capturedActor,staged,fields));
            assertThat(service.load(capturedTenant,staged).processState()).isEqualTo("COMPLETED");return staged;
        };
        UUID accountA=tenant,vendorA=vendor;String emailA=email;
        importFor.apply("Folios Cheddar,299402,4.50,042100005264,IBCORE-FOLIOS\nDrumroll Chocolate,397990,3.00,036000291452,IBCORE-CHOC\n",vendor);
        var repo=new CatalogRepository(jdbc);var products=repo.listAccountItems(accountA);
        UUID folios=products.stream().filter(x->"IBCORE-FOLIOS".equals(x.accountSku())).findFirst().orElseThrow().id();
        UUID chocolate=products.stream().filter(x->"IBCORE-CHOC".equals(x.accountSku())).findFirst().orElseThrow().id();
        UUID store=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,display_name,seller_identifier,marketplace_identifier,status,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','Synthetic','seller','ATVPDKIKX0DER','ACTIVE','test','America/Los_Angeles',now())",store,accountA);
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,item_name,listing_status,fulfillment_channel) VALUES (?,?,'ATVPDKIKX0DER','IB-MKH-299402-CHEDDAR-FOLIOS-3xEA','ASIN','Folios','ACTIVE','AMAZON')",accountA,store);
        var mappings=new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository(jdbc);
        tx.executeWithoutResult(t->mappings.saveMapping(accountA,store,"IB-MKH-299402-CHEDDAR-FOLIOS-3xEA",List.of(folios.toString(),chocolate.toString()),List.of("3","1")));
        jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,source_type,occurred_at,idempotency_key) SELECT ?,?,location_id,'RECEIPT',12,'MANUAL',now(),? FROM account_catalog_item_locations WHERE tenant_id=? AND account_catalog_item_id=? LIMIT 1",accountA,folios,"incident-"+folios,accountA,folios);
        String stockBefore=incidentStockSnapshot(accountA);
        String snapshot=incidentAccountSnapshot(accountA);
        tenant=UUID.randomUUID();vendor=UUID.randomUUID();user=UUID.randomUUID();email=user+"@example.test";
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Other import account')",tenant,tenant.toString());
        jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Other user')",user,email);
        jdbc.update("INSERT INTO vendors(id,tenant_id,name,vendor_code,currency,distribution_center) VALUES (?,?,'KEHE','KEHE','USD','19')",vendor,tenant);
        // Different tenant code for the same physical barcode must not change A's code or manual bundle.
        importFor.apply("Folios alternate account label,ALT-FOLIOS,6.00,042100005264,MKP-FOLIOS\n",vendor);
        assertThat(incidentAccountSnapshot(accountA)).as("different code / same barcode preserves original account").isEqualTo(snapshot);
        // The same vendor code and same barcode in a different account is also isolated.
        importFor.apply("Folios alternate account label,299402,6.01,042100005264,MKP-FOLIOS\n",vendor);
        assertThat(incidentAccountSnapshot(accountA)).as("same code / same barcode preserves original account").isEqualTo(snapshot);
        // Same vendor code with a different physical barcode must not repoint A's item or mapping.
        UUID conflict=tx.execute(t->service.stage(tenant,email,vendor,new MockMultipartFile("file","conflict.csv","text/csv","Description,ItemCode,Cost,UPC,AccountSKU\nSauce Mole Enchilada,299402,7.00,850032994026,MKP-MOLE\n".getBytes(StandardCharsets.UTF_8))));
        assertThat(service.load(tenant,conflict).status()).isEqualTo("MAPPED");
        assertThat(incidentAccountSnapshot(accountA)).as("same code / different barcode preserves original account").isEqualTo(snapshot);
        // The other account may change its own supplier code; A remains unchanged.
        importFor.apply("Sauce Mole Enchilada,399644,7.01,850032994026,MKP-MOLE\n",vendor);
        assertThat(incidentAccountSnapshot(accountA)).isEqualTo(snapshot);
        // The visible screenshot result is reproducible through barcode substring search.
        var search=repo.searchAccountItems(tenant,"299402",20);
        assertThat(search).extracting(CatalogRepository.AccountItemView::accountSku).containsExactly("MKP-FOLIOS","MKP-MOLE");
        var mole=search.stream().filter(x->"MKP-MOLE".equals(x.accountSku())).findFirst().orElseThrow();
        assertThat(mole.vendorItemCode()).isEqualTo("399644");
        assertThat(mole.identifier()).isEqualTo("850032994026");
        UUID accountB=tenant,vendorB=vendor;String emailB=email;
        String snapshotB=incidentAccountSnapshot(accountB);
        tenant=accountA;vendor=vendorA;email=emailA;
        importFor.apply("Folios Cheddar,299402,4.60,042100005264,IBCORE-FOLIOS\nDrumroll Chocolate,397990,3.10,036000291452,IBCORE-CHOC\n",vendor);
        assertThat(incidentAccountSnapshot(accountB)).as("reverse re-import preserves other account").isEqualTo(snapshotB);
        assertThat(incidentStockSnapshot(accountA)).isEqualTo(stockBefore);
        String updatedA=incidentAccountSnapshot(accountA);
        tenant=accountB;vendor=vendorB;email=emailB;
        importFor.apply("Folios alternate account label,299402,6.11,042100005264,MKP-FOLIOS\nSauce Mole Enchilada,399644,7.11,850032994026,MKP-MOLE\n",vendor);
        assertThat(incidentAccountSnapshot(accountA)).as("repeat import in original direction preserves account").isEqualTo(updatedA);
        assertThat(incidentStockSnapshot(accountA)).isEqualTo(stockBefore);
        tenant=accountA;vendor=vendorA;email=emailA;
        assertThat(mappings.mappingComponents(accountA,store,List.of("IB-MKH-299402-CHEDDAR-FOLIOS-3xEA")).get("IB-MKH-299402-CHEDDAR-FOLIOS-3xEA"))
            .extracting(com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository.MappingComponentView::itemId).containsExactly(folios,chocolate);
    }
    UUID incidentProduct(String barcode){UUID existing=jdbc.query("SELECT global_product_id FROM global_product_identifiers WHERE identifier_type='UPC' AND identifier_value=?",rs->rs.next()?rs.getObject(1,UUID.class):null,barcode);return existing==null?product(barcode):existing;}
    String incidentStockSnapshot(UUID account){return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]'::jsonb)::text FROM inventory_ledger_entries e WHERE tenant_id=?",String.class,account);}

    @Test void switchedAccountCannotPreviewValidateOrApproveAnotherAccountsBatch(){
        UUID batch=stage("Synthetic Folios,299402,4.50,042100005264\n");
        UUID original=tenant,other=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Switched account')",other,other.toString());
        String before=jdbc.queryForObject("SELECT to_jsonb(c)::text FROM catalog_imports c WHERE id=?",String.class,batch);
        assertThatThrownBy(()->service.load(other,batch)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(t->service.validate(other,batch,mapping))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(t->service.approve(other,email,batch,mapping))).isInstanceOf(IllegalArgumentException.class);
        var controller=new CatalogController(new CatalogRepository(jdbc),service);
        var worker=org.mockito.Mockito.mock(CatalogImportWorker.class);
        var progress=org.mockito.Mockito.mock(CatalogImportProgress.class);
        controller.configureImportWorker(worker,progress);
        var session=new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("selectedTenantId",other);session.setAttribute("selectedTenantName","Switched account");
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(email,"unused",List.of());
        assertThatThrownBy(()->controller.mapImport(batch,auth,session,new org.springframework.ui.ExtendedModelMap())).isInstanceOf(IllegalArgumentException.class);
        var redirect=new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        assertThat(controller.approveImport(batch,mapping,auth,session,redirect)).isEqualTo("redirect:/app/catalog/imports/"+batch);
        assertThat(redirect.getFlashAttributes()).containsKey("catalogError");
        org.mockito.Mockito.verifyNoInteractions(worker,progress);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM catalog_imports c WHERE id=?",String.class,batch)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM catalog_imports WHERE id=?",UUID.class,batch)).isEqualTo(original);
    }
    String incidentAccountSnapshot(UUID account){
        return jdbc.queryForObject("""
            SELECT jsonb_build_object(
              'items',(SELECT jsonb_agg(to_jsonb(i) ORDER BY i.id) FROM account_catalog_items i WHERE i.tenant_id=?),
              'offers',(SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM vendor_catalog_offers o WHERE o.tenant_id=?),
              'mappings',(SELECT jsonb_agg(to_jsonb(m) ORDER BY m.id) FROM marketplace_sku_mappings m WHERE m.tenant_id=?),
              'components',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM marketplace_sku_mapping_components c WHERE c.tenant_id=?),
              'products',(SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM global_catalog_products p WHERE p.id IN(SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?)),
              'identifiers',(SELECT jsonb_agg(to_jsonb(b) ORDER BY b.id) FROM global_product_identifiers b WHERE b.global_product_id IN(SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?)))::text
            """,String.class,account,account,account,account,account,account);
    }

    @Test void conflictingActiveVendorCodeIsRejectedBeforeAnyCatalogueOrStockMutation(){
        approve(stage("Original product,299402,4.50,042100005264\n"));
        String before=incidentAccountSnapshot(tenant),stock=incidentStockSnapshot(tenant);
        UUID conflicting=stage("Different product,299402,5.50,036000291452\n");
        tx.executeWithoutResult(t->service.validate(tenant,conflicting,mapping));
        assertThat(service.load(tenant,conflicting).status()).isEqualTo("MAPPED");
        assertThat(service.load(tenant,conflicting).validationMessages()).anyMatch(x->x.contains("different product"));
        // The publish path must recheck even if a batch was validated before a competing import.
        jdbc.update("UPDATE catalog_imports SET status='VALIDATED' WHERE id=?",conflicting);
        assertThatThrownBy(()->approve(conflicting)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different product");
        assertThat(incidentAccountSnapshot(tenant)).isEqualTo(before);
        assertThat(incidentStockSnapshot(tenant)).isEqualTo(stock);
    }

    @Test void manualOfferBindingsPreserveHistoryAndRejectAnotherProduct(){
        var repo=new CatalogRepository(jdbc);
        UUID first=incidentProduct("042100005264"),second=incidentProduct("036000291452");
        UUID a=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id) VALUES (?,?) RETURNING id",UUID.class,tenant,first);
        UUID b=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id) VALUES (?,?) RETURNING id",UUID.class,tenant,second);
        tx.executeWithoutResult(t->repo.saveVendorOffer(tenant,email,a,vendor,"00012-34",java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,"USD"));
        String before=incidentAccountSnapshot(tenant);
        assertThatThrownBy(()->tx.executeWithoutResult(t->repo.saveVendorOffer(tenant,email,b,vendor,"1234",java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,"USD"))).hasMessageContaining("different product");
        assertThat(incidentAccountSnapshot(tenant)).isEqualTo(before);
        jdbc.update("UPDATE vendor_catalog_offers SET effective_from=current_date-1 WHERE tenant_id=? AND account_catalog_item_id=?",tenant,a);
        tx.executeWithoutResult(t->repo.saveVendorOffer(tenant,email,a,vendor,"00012-34",java.math.BigDecimal.TEN,java.math.BigDecimal.ZERO,"USD"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor_catalog_offers WHERE tenant_id=? AND account_catalog_item_id=?",Integer.class,tenant,a)).isEqualTo(2);
        UUID branch=UUID.randomUUID();jdbc.update("INSERT INTO vendors(id,tenant_id,name,vendor_code,currency,distribution_center) VALUES (?,?,'Other branch',?,'USD','19')",branch,tenant,vendor.toString());
        tx.executeWithoutResult(t->repo.saveVendorOffer(tenant,email,b,branch,"1234",java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,"USD"));
        tx.executeWithoutResult(t->repo.saveVendorOffer(tenant,email,b,vendor,null,java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,"USD"));
        assertThat(jdbc.queryForObject("SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=? AND account_catalog_item_id=? AND vendor_id=?",String.class,tenant,b,vendor)).isNull();
    }

    @Test void existingDuplicateCodeIsReadableButCannotChooseAnArbitraryProduct(){
        var repo=new CatalogRepository(jdbc);
        UUID first=incidentProduct("042100005264"),second=product(null);
        for(UUID global:List.of(first,second)){
            UUID item=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id) VALUES (?,?) RETURNING id",UUID.class,tenant,global);
            jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency) VALUES (?,?,?,'342934',1,'USD')",tenant,vendor,item);
        }
        String before=incidentAccountSnapshot(tenant);
        assertThat(repo.searchAccountItems(tenant,"342934",20)).hasSize(2);
        assertThatThrownBy(()->tx.execute(s->repo.addImportedVendorProduct(tenant,email,vendor,"342934","Muesli",null,"UPC","042100005264",null,false))).hasMessageContaining("multiple products");
        UUID batch=stage("Muesli,342934,4.50,042100005264\n");
        assertThat(service.load(tenant,batch).status()).isEqualTo("MAPPED");
        assertThat(service.load(tenant,batch).validationMessages()).anyMatch(x->x.contains("multiple products"));
        assertThat(incidentAccountSnapshot(tenant)).isEqualTo(before);
    }

    @Test void simultaneousImportsCannotPublishDifferentProductsForOneVendorCode() throws Exception {
        UUID a=stage("First product,299402,4.50,042100005264\n"),b=stage("Second product,299402,5.50,036000291452\n");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            java.util.function.Function<UUID,java.util.concurrent.Callable<Boolean>> task=id->()->{start.await();try{approve(id);return true;}catch(IllegalArgumentException conflict){assertThat(conflict).hasMessageContaining("different product");return false;}};
            var one=pool.submit(task.apply(a));var two=pool.submit(task.apply(b));start.countDown();
            assertThat(List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT account_catalog_item_id) FROM vendor_catalog_offers WHERE tenant_id=? AND vendor_id=? AND vendor_item_code='299402'",Integer.class,tenant,vendor)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test void knownBarcodeCannotBeRepointedByInvoiceVendorCode(){
        var repo=new CatalogRepository(jdbc);UUID first=incidentProduct("042100005264"),second=incidentProduct("036000291452");
        UUID item=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id) VALUES (?,?) RETURNING id",UUID.class,tenant,first);
        jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency) VALUES (?,?,?,'299402',1,'USD')",tenant,vendor,item);
        String before=incidentAccountSnapshot(tenant);
        assertThatThrownBy(()->tx.execute(t->repo.addImportedVendorProduct(tenant,email,vendor,"299402","Different product",null,"UPC","036000291452",null,false))).hasMessageContaining("Barcode differs");
        assertThat(incidentAccountSnapshot(tenant)).isEqualTo(before);
    }

    @Test void sameBatchConflictingCodesCannotBypassApproval(){
        UUID batch=stage("First,299402,4.50,042100005264\nSecond,299402,5.50,036000291452\n");
        assertThat(service.load(tenant,batch).status()).isEqualTo("MAPPED");
        String before=incidentAccountSnapshot(tenant);
        jdbc.update("UPDATE catalog_imports SET status='VALIDATED' WHERE id=?",batch);
        assertThatThrownBy(()->approve(batch)).hasMessageContaining("duplicated in this file");
        assertThat(incidentAccountSnapshot(tenant)).isEqualTo(before);
    }

    @Test void equalVendorCodeAndAccountSkuRemainDistinctReadableFields(){
        UUID batch=tx.execute(t->service.stage(tenant,email,vendor,new MockMultipartFile("file","equal-fields.csv","text/csv","Description,ItemCode,Cost,UPC,AccountSKU\nProduct,299402,4.50,042100005264,299402\n".getBytes(StandardCharsets.UTF_8))));
        var fields=new HashMap<>(mapping);fields.put("accountSku","AccountSKU");
        tx.executeWithoutResult(t->service.approve(tenant,email,batch,fields));
        var repo=new CatalogRepository(jdbc);
        assertThat(repo.listAccountItems(tenant)).singleElement().satisfies(item->{assertThat(item.vendorItemCode()).isEqualTo("299402");assertThat(item.accountSku()).isEqualTo("299402");});
        assertThat(repo.pageAccountItems(tenant,"",0,25).rows()).singleElement().satisfies(item->assertThat(item.accountSku()).isEqualTo("299402"));
        assertThat(repo.searchAccountItems(tenant,"299402",20)).singleElement().satisfies(item->assertThat(item.accountSku()).isEqualTo("299402"));
    }

    @Test void migrationBackfillsOnlyIbcoreKeheWithoutChangingProductIdentity(){
        assertThat(jdbc.queryForObject("SELECT distribution_center FROM vendors WHERE id=?",String.class,migratedVendor)).isEqualTo("41");
        assertThat(jdbc.queryForObject("SELECT distribution_center FROM vendors WHERE vendor_code='OA'",String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE account_sku='UNCHANGED'",UUID.class)).isEqualTo(migratedProduct);
        assertThat(jdbc.queryForObject("SELECT catalog_scope FROM global_product_vendor_codes WHERE global_product_id=?",String.class,migratedProduct)).isEqualTo("DC:41");
        assertThat(jdbc.queryForObject("SELECT catalog_scope FROM global_product_packaging_versions WHERE global_product_id=?",String.class,migratedProduct)).isEqualTo("DC:41");
    }

    @Test void supplierCodeNeverOverridesGlobalBarcodeIdentity(){
        UUID original=product(normalized("old"+user));code(original,"789800");
        jdbc.update("UPDATE global_product_vendor_codes SET catalog_scope='DC:41' WHERE global_product_id=?",original);
        UUID staged=stage("Coconut,789800,3.71,"+normalized("new"+user)+"\n");
        approve(staged);
        UUID imported=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?",UUID.class,tenant);
        assertThat(imported).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=?",String.class,imported)).isEqualTo(normalized("new"+user));
        assertThat(jdbc.queryForObject("SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=?",String.class,tenant)).isEqualTo("789800");
        /* A branch/DC remains packaging metadata, never product identity. */
        UUID secondTenant=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?, 'Second account')",secondTenant,secondTenant.toString());
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM global_product_vendor_codes WHERE vendor_key=? AND catalog_scope='DC:41'",UUID.class,normalized(vendor.toString()))).isEqualTo(original);
        /* Keep the old evidence row to prove it did not control the match. */
        assertThat(imported).isNotEqualTo(original);
    }

    @Test void equivalentUpcEanAcrossBranchesReusesProductWithoutChangingItsCasePack(){
        UUID original=product("076371012317");code(original,"789800");
        jdbc.update("UPDATE global_catalog_products SET units_per_case=12 WHERE id=?",original);
        jdbc.update("UPDATE global_product_vendor_codes SET catalog_scope='DC:41' WHERE global_product_id=?",original);
        var dcMapping=new HashMap<>(mapping);dcMapping.put("distributionCenter","19");dcMapping.put("identifierType","EAN");
        UUID staged=stage("Tofu,425223,2.00,0076371012317\n");
        tx.executeWithoutResult(s->service.approve(tenant,email,staged,dcMapping));
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?",UUID.class,tenant)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_product_identifiers WHERE global_product_id=?",Integer.class,original)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT units_per_case FROM global_catalog_products WHERE id=?",Integer.class,original)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT catalog_scope FROM global_product_packaging_versions WHERE global_product_id=?",String.class,original)).isEqualTo("DC:19");
    }

    @Test void equivalentBarcodeRowsInSameFileAreRejectedAtomically(){
        UUID staged=stage("One,111,1.00,041331090988\nTwo,112,1.00,0041331090988\n");
        assertThatThrownBy(()->approve(staged)).hasMessageContaining("Duplicate equivalent UPC / EAN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }

    @Test void manualReceivingProductPathUsesBranchAndBarcodeIdentity(){
        var repository=new CatalogRepository(jdbc);
        UUID original=product(normalized("existing"+user));code(original,"99");
        jdbc.update("UPDATE global_product_vendor_codes SET catalog_scope='DC:41' WHERE global_product_id=?",original);
        jdbc.update("UPDATE vendors SET distribution_center='19' WHERE id=?",vendor);
        UUID item=tx.execute(s->repository.addImportedVendorProduct(tenant,email,vendor,"99","Other product",null,"MPN","new"+user,null,false));
        tx.executeWithoutResult(s->{
            repository.updateImportedProductAttributes(tenant,item,vendor,"99",null,"EA","6 pack",new java.math.BigDecimal("6"),null,null);
            repository.saveVendorOffer(tenant,email,item,vendor,"99",java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,"USD","MANUAL",null,null,null,null,null);
        });
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE id=?",UUID.class,item)).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT p.catalog_scope FROM vendor_catalog_offers o JOIN global_product_packaging_versions p ON p.id=o.packaging_version_id WHERE o.account_catalog_item_id=?",String.class,item)).isEqualTo("DC:19");
        assertThatThrownBy(()->tx.execute(s->repository.addImportedVendorProduct(tenant,email,vendor,"99","Wrong product",null,"MPN","wrong"+user,null,false)))
            .hasMessageContaining("Barcode differs");
        UUID repeated=tx.execute(s->repository.addImportedVendorProduct(tenant,email,vendor,"99","Other product",null,"MPN","new"+user,null,false));
        assertThat(repeated).isEqualTo(item);
    }

    @Test void invoiceRelinksLegacyVendorCodeProductToCanonicalUpcProduct(){
        var repository=new CatalogRepository(jdbc);
        UUID legacy=product(null),canonical=product("891613000224");
        UUID item=jdbc.queryForObject("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,?) RETURNING id",UUID.class,tenant,legacy,"94018");
        jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency) VALUES (?,?,?,?,1,'USD')",tenant,vendor,item,"94018");
        UUID resolved=tx.execute(s->repository.addImportedVendorProduct(tenant,email,vendor,"94018","Cheese Sharp Chdr","Old Croc","UPC","891613000224","94018",false));
        assertThat(resolved).isEqualTo(item);
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",UUID.class,tenant,item)).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=? AND account_catalog_item_id=?",String.class,tenant,item)).isEqualTo("94018");
    }

    @Test void sharedProductKeepsPrimaryAndRecognizesKnownAlternateBarcodeWithoutDuplication(){
        String old=normalized("old-"+user),newCode=normalized("new-"+user);UUID product=product(old);code(product,"123");
        jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,'UPC',?,false)",product,newCode);
        UUID otherTenant=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Original account')",otherTenant,otherTenant.toString());
        jdbc.update("INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku) VALUES (?,?,'ORIGINAL')",otherTenant,product);
        approve(stage("Product,123,4.50,"+newCode+"\n"));
        assertThat(jdbc.queryForObject("SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=? AND is_primary",String.class,product)).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT is_primary FROM global_product_identifiers WHERE identifier_value=?",Boolean.class,newCode)).isFalse();
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?",UUID.class,tenant)).isEqualTo(product);
        assertThat(jdbc.queryForObject("SELECT account_sku FROM account_catalog_items WHERE tenant_id=?",String.class,otherTenant)).isEqualTo("ORIGINAL");
        approve(stage("Product,123,5.50,"+newCode+"\n"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_product_identifiers WHERE global_product_id=?",Integer.class,product)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }
    @Test void importRejectsRowsWithoutGlobalIdentifierAtomically(){
        UUID existing=product(null);code(existing,"124");
        UUID staged=stage("Existing,124,1.00,a-"+user+"\nNew,125,2.00,b-"+user+"\nWithout barcode,126,3.00,\n");
        assertThatThrownBy(()->approve(staged)).hasMessageContaining("missing UPC/EAN/GTIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }
    @Test void barcodeIdentityWinsWhenLegacyVendorCodePointsElsewhere(){
        UUID first=product(normalized("first-"+user));code(first,"127");product(normalized("second-"+user));
        UUID staged=stage("Conflict,127,1.00,second-"+user+"\n");
        approve(staged);
        UUID selected=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?",UUID.class,tenant);
        assertThat(selected).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=?",String.class,selected)).isEqualTo(normalized("second-"+user));
    }

    @Test void imports25845RowsWithAnExistingPrimaryBarcode(){
        UUID existing=product(normalized("original"+user));code(existing,"100000");
        StringBuilder rows=new StringBuilder();
        for(int n=0;n<25845;n++)rows.append("Product ").append(n).append(',').append(100000+n)
            .append(",1.00,").append(n==0?normalized("original"+user):normalized(user.toString())+n).append('\n');
        UUID staged=stage(rows.toString());approve(staged);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(25845);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_import_rows WHERE catalog_import_id=? AND validation_status='IMPORTED'",Integer.class,staged)).isEqualTo(25845);
        assertThat(jdbc.queryForObject("SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=? AND is_primary",String.class,existing)).isEqualTo(normalized("original"+user));
    }

    @Test void changedBarcodeOnVendorCodeCreatesBarcodeProductAndKeepsOfferCode(){
        UUID existing=product(normalized("old"+user));code(existing,"789800");
        UUID staged=stage("Different product,789800,3.71,"+normalized("new"+user)+"\n");
        approve(staged);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_product_identifiers WHERE global_product_id=?",Integer.class,existing)).isEqualTo(1);
        UUID selected=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?",UUID.class,tenant);
        assertThat(selected).isNotEqualTo(existing);
        assertThat(jdbc.queryForObject("SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=?",String.class,tenant)).isEqualTo("789800");
    }
}
