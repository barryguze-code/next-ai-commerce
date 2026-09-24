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
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("ALTER FUNCTION "+schema+".ensure_item_default_location() SET search_path TO "+schema);
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
    void code(UUID product,String code){jdbc.update("INSERT INTO global_product_vendor_codes(global_product_id,vendor_key,vendor_name,vendor_item_code,normalized_item_code,source_tenant_id) VALUES (?,?,'Test vendor',?,?,?)",product,normalized(vendor.toString()),code,code,tenant);}

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
