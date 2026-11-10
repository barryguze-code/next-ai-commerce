package com.nextaicommerce.platform.profit;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.assertThat;

/** Exact production baseline upgrade on a disposable cluster with a restricted schema owner. */
class PackageRateMigrationDatabaseTest {
 @Test void v136To138PreservesSourceHistoryAndEnforcesPackageTenantIsolation() throws Exception {
  try(var pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start()){
   var admin=new JdbcTemplate(pg.getPostgresDatabase());String role="package_owner_"+UUID.randomUUID().toString().replace("-","");
   admin.execute("CREATE SCHEMA test_extensions");admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA test_extensions");
   admin.execute("CREATE ROLE "+role+" LOGIN PASSWORD 'synthetic-test-only' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");admin.execute("ALTER SCHEMA public OWNER TO "+role);
   var ds=new DriverManagerDataSource(pg.getJdbcUrl("postgres","postgres").split("\\?",2)[0]+"?currentSchema=public",role,"synthetic-test-only");
   Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("136").load().migrate();
   UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
   admin.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Package fixture')",tenant,tenant.toString());
   admin.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','FIXTURE','US','ACTIVE','Fixture','fixture','UTC',now())",store,tenant);
   admin.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,currency,fulfillment_channel) VALUES (?,?,'US','UNCHANGED','USD','MFN')",tenant,store);
   String before=admin.queryForObject("SELECT row_to_json(o)::text FROM amazon_orders o WHERE tenant_id=?",String.class,tenant);
   var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("138").group(true).cleanDisabled(true).load();
   assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);flyway.validate();
   assertThat(admin.queryForObject("SELECT row_to_json(o)::text FROM amazon_orders o WHERE tenant_id=?",String.class,tenant)).isEqualTo(before);
   assertThat(admin.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('packing_package_aliases','packing_package_name_events','packing_package_rates') AND relrowsecurity AND relforcerowsecurity",Integer.class)).isEqualTo(3);
   var jdbc=new JdbcTemplate(ds);var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
   tx.executeWithoutResult(s->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());jdbc.update("INSERT INTO packing_package_rates(tenant_id,package_key,currency,effective_from,amount,created_by) VALUES (?,'fixture','USD','2026-11-09',2.50,'fixture')",tenant);assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_rates",Integer.class)).isEqualTo(1);});
   tx.executeWithoutResult(s->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM packing_package_rates",Integer.class)).isZero();});
   assertThat(admin.queryForObject("SELECT count(*) FROM inventory_ledger_entries",Integer.class)).isZero();
   assertThat(admin.queryForObject("SELECT count(*) FROM order_inventory_reservations",Integer.class)).isZero();
  }
 }
}
