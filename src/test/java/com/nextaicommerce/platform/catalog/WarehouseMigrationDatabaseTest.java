package com.nextaicommerce.platform.catalog;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WarehouseMigrationDatabaseTest {
 @Test void populatedUpgradePreservesLocationsAndRestrictedEmptyScopeIsSafe() throws Exception {
  try(var pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setPort(0).start()){
   String schema="warehouse_upgrade_"+UUID.randomUUID().toString().replace("-","");
   var ds=new DriverManagerDataSource(pg.getJdbcUrl("postgres","postgres")+"&currentSchema="+schema,"postgres",null);
   var jdbc=new JdbcTemplate(ds);
   Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).target("129").load().migrate();
   UUID a=UUID.randomUUID(),b=UUID.randomUUID(),location=UUID.randomUUID();
   jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'First')",a,"a-"+a);
   jdbc.execute("SELECT set_config('app.tenant_id','"+a+"',false)");
   jdbc.update("INSERT INTO warehouse_locations(id,tenant_id,code,name) VALUES (?,?,'MAIN','Existing shelf')",location,a);
   String before=jdbc.queryForObject("SELECT (to_jsonb(l)-'updated_at')::text FROM warehouse_locations l WHERE id=?",String.class,location);
   Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
   assertThat(jdbc.queryForObject("SELECT (to_jsonb(l)-'warehouse_id'-'updated_at')::text FROM warehouse_locations l WHERE id=?",String.class,location)).isEqualTo(before);
   jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Second')",b,"b-"+b);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouses WHERE tenant_id=? AND is_default",Long.class,b)).isEqualTo(1);
   var scopedConnection=new org.springframework.jdbc.datasource.SingleConnectionDataSource(ds.getConnection(),true);
   jdbc=new JdbcTemplate(scopedConnection);
   String role="warehouse_reader_"+UUID.randomUUID().toString().replace("-","");
   jdbc.execute("CREATE ROLE "+role+" NOLOGIN");jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON "+schema+".warehouses,"+schema+".warehouse_contacts TO "+role);
   jdbc.execute("SET ROLE "+role);jdbc.execute("SELECT set_config('app.tenant_id','',false)");
   assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouses",Long.class)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse_contacts",Long.class)).isZero();
   jdbc.execute("SELECT set_config('app.tenant_id','"+a+"',false)");
   assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouses",Long.class)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouses WHERE tenant_id='"+b+"'",Long.class)).isZero();
   scopedConnection.destroy();
  }
 }
}
