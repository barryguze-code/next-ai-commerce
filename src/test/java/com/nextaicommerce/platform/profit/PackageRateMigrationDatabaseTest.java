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
 @Test void v140To142FlattensAliasesWithoutChangingSavedPricesOrOrders() throws Exception {
  try(var pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start()){
   var admin=new JdbcTemplate(pg.getPostgresDatabase());String role="package_cleanup_"+UUID.randomUUID().toString().replace("-","");
   admin.execute("CREATE SCHEMA test_extensions");admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA test_extensions");admin.execute("CREATE ROLE "+role+" LOGIN PASSWORD 'synthetic-test-only' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");admin.execute("ALTER SCHEMA public OWNER TO "+role);
   var ds=new DriverManagerDataSource(pg.getJdbcUrl("postgres","postgres").split("\\?",2)[0]+"?currentSchema=public",role,"synthetic-test-only");
   Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("140").load().migrate();
   UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),other=UUID.randomUUID();
   admin.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Package fixture'),(?,?,'Other account')",tenant,tenant.toString(),other,other.toString());
   admin.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','FIXTURE','US','ACTIVE','Fixture','fixture','UTC',now())",store,tenant);
   admin.update("INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name) VALUES (?,'pak','Old bag','Pak'),(?,'old bag','02. PAK','Pak'),(?,'02. pak','02. PAK','Pak'),(?,'pak','Other account bag','Pak')",tenant,tenant,tenant,other);
   admin.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,profit_package_type) VALUES (?,?,'US','SKU','FEDEX_PAK')",tenant,store);
   admin.update("INSERT INTO profit_sku_packages(tenant_id,marketplace_connection_id,seller_sku,sequence,description,amount) VALUES (?,?,'SKU',1,'FEDEX_PAK',12.08)",tenant,store);
   admin.update("INSERT INTO packing_package_rates(tenant_id,package_key,currency,effective_from,amount,created_by) VALUES (?,'pak','USD','2026-11-01',12.41,'fixture')",tenant);
   admin.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,currency,fulfillment_channel) VALUES (?,?,'US','UNCHANGED','USD','MFN')",tenant,store);
   admin.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,profit_package_type) VALUES (?,?,'US','UPS-SKU','UPS')",tenant,store);
   admin.update("INSERT INTO profit_sku_packages(tenant_id,marketplace_connection_id,seller_sku,sequence,description,amount) VALUES (?,?,'UPS-SKU',1,'UPS',14.20)",tenant,store);
   admin.update("INSERT INTO packing_package_aliases(tenant_id,source_key,display_name,cost_name) VALUES (?,'ups ombox','08. UPS O.A.Box','UPS OMBox')",tenant);
   admin.update("INSERT INTO temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,amazon_order_id,packaging,order_sku_qty_list,order_item_summary) VALUES (?,?,'UPS-ORDER','UPS OMBox','UPS-SKU-1','UPS-ASIN-1')",tenant,store);
   String order=admin.queryForObject("SELECT row_to_json(o)::text FROM amazon_orders o WHERE tenant_id=?",String.class,tenant);
   var migration=Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("142").group(true).cleanDisabled(true).load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(2);migration.validate();
   assertThat(admin.queryForObject("SELECT display_name FROM packing_package_aliases WHERE tenant_id=? AND source_key='pak'",String.class,tenant)).isEqualTo("02. PAK");
   assertThat(admin.queryForObject("SELECT display_name FROM packing_package_aliases WHERE tenant_id=? AND source_key='fedex_pak'",String.class,other)).isEqualTo("Other account bag");
   assertThat(admin.queryForObject("SELECT description FROM profit_sku_packages WHERE tenant_id=? AND seller_sku='SKU'",String.class,tenant)).isEqualTo("02. PAK");assertThat(admin.queryForObject("SELECT amount FROM profit_sku_packages WHERE tenant_id=? AND seller_sku='SKU'",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("12.08");
   assertThat(admin.queryForObject("SELECT profit_package_type FROM amazon_listings WHERE tenant_id=? AND seller_sku='SKU'",String.class,tenant)).isNull();
   assertThat(admin.queryForObject("SELECT row_to_json(o)::text FROM amazon_orders o WHERE tenant_id=?",String.class,tenant)).isEqualTo(order);
   assertThat(admin.queryForObject("SELECT amount FROM packing_package_rates WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("12.41");
   assertThat(admin.queryForObject("SELECT description FROM profit_sku_packages WHERE tenant_id=? AND seller_sku='UPS-SKU'",String.class,tenant)).isEqualTo("08. UPS O.A.Box");
   assertThat(admin.queryForObject("SELECT amount FROM profit_sku_packages WHERE tenant_id=? AND seller_sku='UPS-SKU'",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("14.20");
   assertThat(admin.queryForObject("SELECT profit_package_type FROM amazon_listings WHERE tenant_id=? AND seller_sku='UPS-SKU'",String.class,tenant)).isNull();
   assertThat(migration.migrate().migrationsExecuted).isZero();
   assertThat(admin.queryForObject("SELECT count(*) FROM pg_class WHERE relname='packing_package_dimensions' AND relrowsecurity AND relforcerowsecurity",Integer.class)).isEqualTo(1);
  }
 }

}
