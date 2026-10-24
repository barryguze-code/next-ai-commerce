package com.nextaicommerce.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GlobalCatalogDatabaseTest {
    static JdbcTemplate jdbc;
    static String schema;
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;

    @BeforeAll static void setup() throws Exception {
        schema="global_catalog_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=Files.readString(Path.of(".local/database-password")).trim();
        }else{
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");
        }
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbc!=null&&schema.matches("global_catalog_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }
    @Test void globalReadPreservesIdentifierTypePriorityAndMissingIdentity(){
        UUID multiple=UUID.fromString("00000000-0000-0000-0000-000000000101"),missing=UUID.fromString("00000000-0000-0000-0000-000000000102");
        jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Alpha product'),(?,'Beta without identifier')",multiple,missing);
        jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary,created_at) VALUES (?,'UPC','012345678905',false,'2020-01-01'),(?,'EAN','8683650091325',true,'2021-01-01')",multiple,multiple);
        var repo=new CatalogRepository(jdbc);
        var rows=repo.listGlobalProducts();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().id()).isEqualTo(multiple);
        assertThat(rows.getFirst().identifierType()).isEqualTo("EAN");
        assertThat(rows.getFirst().identifier()).isEqualTo("8683650091325");
        assertThat(rows.getLast().id()).isEqualTo(missing);
        assertThat(rows.getLast().identifierType()).isNull();
        assertThat(rows.getLast().identifier()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_product_identifiers",Integer.class)).isEqualTo(2);
    }
}
