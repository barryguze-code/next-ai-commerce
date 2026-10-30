package com.nextaicommerce.platform.fees;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Disposable upgrade using a non-superuser schema owner, without manual new-table grants. */
class AmazonFeeMigrationDatabaseTest {
 @Test void v112UpgradePreservesHistoryAndGrantsOwnerRuntimeIdentityAccess()throws Exception {
  try(var pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start()){
   String suffix=UUID.randomUUID().toString().replace("-",""),schema="public",role="fee_owner_"+suffix;
   var admin=new JdbcTemplate(pg.getPostgresDatabase());
   admin.execute("CREATE SCHEMA test_extensions");
   admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA test_extensions");
   admin.execute("CREATE ROLE "+role+" LOGIN PASSWORD 'synthetic-test-only' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
   admin.execute("ALTER SCHEMA "+schema+" OWNER TO "+role);
   String baseUrl=pg.getJdbcUrl("postgres","postgres").split("\\?",2)[0],url=baseUrl+"?currentSchema="+schema;
   var ds=new DriverManagerDataSource(url,role,"synthetic-test-only");var jdbc=new JdbcTemplate(ds);
   org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).createSchemas(false).target("112").locations("classpath:db/migration").load().migrate();
   UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),source=UUID.randomUUID(),item=UUID.randomUUID();
   var scopedAdmin=new JdbcTemplate(new DriverManagerDataSource(baseUrl+"?currentSchema="+schema,"postgres",null));
   scopedAdmin.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Synthetic migration')",tenant,tenant.toString());
   scopedAdmin.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','SYNTHETIC','US','ACTIVE','Synthetic','fixture','UTC',now())",store,tenant);
   scopedAdmin.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,source_key,content_type,sha256,payload) VALUES (?,?,?,'FINANCES_30_DAY','fixture','application/json',?,'{}')",source,tenant,store,source.toString());
   scopedAdmin.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,currency,fulfillment_channel) VALUES (?,?,'US','ORDER','USD','AFN')",tenant,store);
   scopedAdmin.update("INSERT INTO amazon_order_items(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,quantity_shipped,item_price,currency) VALUES (?,?,?,'ORDER','ITEM','00001 / Exact',2,2,60,'USD')",item,tenant,store);
   scopedAdmin.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,inbound_shipped_quantity,snapshot_at) VALUES (?,?,'US','00001 / Exact','SYNTHETIC','SYN-FN',23,160,now())",tenant,store);
   var protectedBefore=new LinkedHashMap<String,String>();
   for(String table:List.of("amazon_source_documents","amazon_orders","amazon_order_items","amazon_inventory_snapshots","marketplace_sku_mappings"))protectedBefore.put(table,digest(scopedAdmin,table));
   org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).createSchemas(false).locations("classpath:db/migration").load().migrate();
   assertThat(jdbc.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(124);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=current_user AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole",Integer.class)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace=?::regnamespace AND relname IN ('amazon_fee_observations','amazon_order_fee_charge_observations','amazon_fee_comparisons','amazon_fee_approvals') AND relrowsecurity AND relforcerowsecurity",Integer.class,schema)).isEqualTo(4);
   var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
   tx.executeWithoutResult(t->{
    jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    UUID charge=jdbc.queryForObject("INSERT INTO amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,transaction_key,revision,component_path,state,reason,source_document_id,raw_evidence,fee_type) VALUES (?,?,'US','SYN-TX','v1','fee','UNRESOLVED','synthetic migration',?,'{}','UNKNOWN') RETURNING id",UUID.class,tenant,store,source);
    jdbc.update("INSERT INTO amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,order_item_id,order_id,seller_sku,fee_type,currency,quantity,purchase_at,state,reason,revision_key) VALUES (?,?,'US',?,'ORDER','00001 / Exact','FBA','USD',2,now(),'NOT_COMPARABLE','synthetic migration','v1')",tenant,store,item);
    assertThat(jdbc.queryForObject("SELECT observation_seq FROM amazon_order_fee_charge_observations WHERE id=?",Long.class,charge)).isPositive();
    assertThat(jdbc.queryForObject("SELECT revision_seq FROM amazon_fee_comparisons WHERE tenant_id=?",Long.class,tenant)).isPositive();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_approvals",Integer.class)).isZero();
   });
   tx.executeWithoutResult(t->{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_order_fee_charge_observations",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons",Integer.class)).isZero();});
   for(var entry:protectedBefore.entrySet())assertThat(digest(scopedAdmin,entry.getKey())).isEqualTo(entry.getValue());
  }
 }
 private static String digest(JdbcTemplate jdbc,String table){return jdbc.queryForObject("SELECT md5(coalesce(string_agg(j::text,'' ORDER BY j::text),'')) FROM (SELECT row_to_json(t) j FROM "+table+" t) x",String.class);}
}
