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
            schema="public"; // This branch owns a disposable cluster; match production function lookup.
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");
        }
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
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
    @Test void unusedGlobalRemovalUsesFullMigratedSchemaSafely(){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Disposable removal fixture')",id);
        jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,'UPC','850017604087',true)",id);
        UUID admin=UUID.randomUUID();
        jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,'removal-admin@example.test','Removal admin')",admin);
        jdbc.update("INSERT INTO platform_administrators(user_id) VALUES (?)",admin);
        jdbc.queryForObject("SELECT set_config('app.user_email','removal-admin@example.test',false)",String.class);
        UUID parent=UUID.randomUUID(),task=UUID.randomUUID();
        jdbc.update("INSERT INTO parent_tenants(id,name,created_by) VALUES (?,'Removal parent',?)",parent,admin);
        jdbc.update("INSERT INTO parent_tenant_memberships(parent_id,user_id,role) VALUES (?,?,'OWNER')",parent,admin);
        jdbc.update("INSERT INTO collaboration_tasks(id,parent_id,title,created_by,queue_position,subject_key) VALUES (?,?,'Linked product',?,1,?)",task,parent,admin,id.toString());
        var service=new CatalogRemovalService(jdbc);
        jdbc.execute("CREATE ROLE catalogue_app NOINHERIT NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO catalogue_app");
        assertThat(transaction.execute(state->{jdbc.execute("SET LOCAL ROLE catalogue_app");jdbc.queryForObject("SELECT set_config('app.user_email','removal-admin@example.test',true)",String.class);var review=service.review(null,id,true);assertThat(jdbc.queryForObject("SELECT current_setting('app.user_email',true)",String.class)).isEqualTo("removal-admin@example.test");assertThat(jdbc.queryForObject("SELECT current_setting('app.collaboration_queue_mutation',true)",String.class)).isNotEqualTo("true");return review;}).operation()).isEqualTo("ARCHIVE");
        jdbc.update("DELETE FROM collaboration_tasks WHERE id=?",task);
        assertThat(transaction.execute(state->{jdbc.execute("SET LOCAL ROLE catalogue_app");jdbc.queryForObject("SELECT set_config('app.user_email','removal-admin@example.test',true)",String.class);return service.review(null,id,true);}).operation()).isEqualTo("DELETE");
        transaction.executeWithoutResult(state->{jdbc.execute("SET LOCAL ROLE catalogue_app");jdbc.queryForObject("SELECT set_config('app.user_email','removal-admin@example.test',true)",String.class);service.remove(null,id,true,"DELETE");});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_catalog_products WHERE id=?",Integer.class,id)).isZero();
    }
}
