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

    @Test void migrationBackfillsOnlyIbcoreKeheWithoutChangingProductIdentity(){
        assertThat(jdbc.queryForObject("SELECT distribution_center FROM vendors WHERE id=?",String.class,migratedVendor)).isEqualTo("41");
        assertThat(jdbc.queryForObject("SELECT distribution_center FROM vendors WHERE vendor_code='OA'",String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE account_sku='UNCHANGED'",UUID.class)).isEqualTo(migratedProduct);
        assertThat(jdbc.queryForObject("SELECT catalog_scope FROM global_product_vendor_codes WHERE global_product_id=?",String.class,migratedProduct)).isEqualTo("DC:41");
        assertThat(jdbc.queryForObject("SELECT catalog_scope FROM global_product_packaging_versions WHERE global_product_id=?",String.class,migratedProduct)).isEqualTo("DC:41");
    }

    @Test void differentBranchesMayReuseSupplierCodeForDifferentProducts(){
        UUID original=product(normalized("old"+user));code(original,"789800");
        jdbc.update("UPDATE global_product_vendor_codes SET catalog_scope='DC:41' WHERE global_product_id=?",original);
        UUID staged=stage("Coconut,789800,3.71,"+normalized("new"+user)+"\n");
        assertThatThrownBy(()->approve(staged)).hasMessageContaining("Enter the supplier DC / branch");
        var dcMapping=new HashMap<>(mapping);dcMapping.put("distributionCenter","DC 19");
        tx.executeWithoutResult(s->service.approve(tenant,email,staged,dcMapping));
        UUID imported=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=?",UUID.class,tenant);
        assertThat(imported).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT global_product_id FROM global_product_vendor_codes WHERE vendor_key=? AND catalog_scope='DC:41'",UUID.class,normalized(vendor.toString()))).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT distribution_center FROM vendors WHERE id=?",String.class,vendor)).isEqualTo("19");
        UUID next=stage("Coconut,789800,4.71,"+normalized("new"+user)+"\n");dcMapping.put("distributionCenter","41");
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.approve(tenant,email,next,dcMapping))).hasMessageContaining("separate vendor entry");
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
    @Test void newAndPreviouslyUnidentifiedProductsReceivePrimaryBarcodes(){
        UUID existing=product(null);code(existing,"124");
        approve(stage("Existing,124,1.00,a-"+user+"\nNew,125,2.00,b-"+user+"\nWithout barcode,126,3.00,\n"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_product_identifiers i JOIN account_catalog_items a ON a.global_product_id=i.global_product_id WHERE a.tenant_id=? AND i.is_primary",Integer.class,tenant)).isEqualTo(2);
    }
    @Test void conflictingIdentitiesRollbackInsteadOfMergingUnrelatedProducts(){
        UUID first=product(normalized("first-"+user));code(first,"127");product(normalized("second-"+user));
        UUID staged=stage("Conflict,127,1.00,second-"+user+"\n");
        assertThatThrownBy(()->approve(staged)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different global products");
        assertThat(jdbc.queryForObject("SELECT status FROM catalog_imports WHERE id=?",String.class,staged)).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isZero();
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

    @Test void changedBarcodeOnVendorCodeRequiresReviewAndRollsBack(){
        UUID existing=product(normalized("old"+user));code(existing,"789800");
        UUID staged=stage("Different product,789800,3.71,"+normalized("new"+user)+"\n");
        assertThatThrownBy(()->approve(staged)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Barcode differs","row 2 (item 789800)","No catalogue changes were saved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_product_identifiers WHERE global_product_id=?",Integer.class,existing)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM catalog_imports WHERE id=?",String.class,staged)).isEqualTo("VALIDATED");
    }
}
