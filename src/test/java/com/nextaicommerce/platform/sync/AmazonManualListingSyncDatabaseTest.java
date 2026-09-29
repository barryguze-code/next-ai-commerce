package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class AmazonManualListingSyncDatabaseTest {
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static String schema;
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    UUID tenant,connection;
    AmazonManualListingSync sync;
    @BeforeAll static void setup() throws Exception {
        schema="listing_sync_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")) {
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();
        } else {
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");
        }
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbc!=null&&schema.matches("listing_sync_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }
    @BeforeEach void fixture() {
        tenant=UUID.randomUUID();connection=UUID.randomUUID();sync=new AmazonManualListingSync(jdbc);
        tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Listing sync test')",tenant,tenant.toString());
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','ACTIVE','Test','America/Los_Angeles',now())",connection,tenant);
        });
    }
    @Test void queuesOnceWithoutPublishingOrCreatingAnotherRun() { tx.executeWithoutResult(s->{
        assertThat(sync.availability(tenant,connection).allowed()).isTrue();
        sync.request(tenant,connection);
        assertThat(sync.availability(tenant,connection).allowed()).isFalse();
        assertThatThrownBy(()->sync.request(tenant,connection)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_schedules WHERE tenant_id=? AND schedule_key='LISTINGS' AND next_run_at<=now()",Integer.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_runs WHERE tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_publications WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }); }
    @Test void rejectsInactiveAndWrongTenant() { tx.executeWithoutResult(s->{
        assertThat(sync.availability(UUID.randomUUID(),connection).allowed()).isFalse();
        jdbc.update("UPDATE marketplace_connections SET status='PENDING' WHERE id=?",connection);
        assertThatThrownBy(()->sync.request(tenant,connection)).isInstanceOf(IllegalStateException.class);
    }); }
    @Test void respectsActiveReportsAndCompletionCooldown() { tx.executeWithoutResult(s->{
        UUID run=jdbc.queryForObject("INSERT INTO marketplace_sync_runs(tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end) VALUES (?,?,'RECONCILIATION','LISTINGS',now(),now()) RETURNING id",UUID.class,tenant,connection);
        jdbc.update("INSERT INTO marketplace_sync_jobs(tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status) VALUES (?,?,?,'LISTINGS_SNAPSHOT',1,'WAITING')",tenant,run,connection);
        assertThat(sync.availability(tenant,connection).allowed()).isFalse();
        jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED',completed_at=now() WHERE sync_run_id=?",run);
        assertThat(sync.availability(tenant,connection).allowed()).isFalse();
        jdbc.update("UPDATE marketplace_sync_jobs SET completed_at=now()-interval '6 minutes' WHERE sync_run_id=?",run);
        assertThat(sync.availability(tenant,connection).allowed()).isTrue();
    }); }
}
