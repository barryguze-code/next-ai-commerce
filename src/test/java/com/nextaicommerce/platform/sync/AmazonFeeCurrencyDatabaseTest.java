package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class AmazonFeeCurrencyDatabaseTest {
 static String schema;static JdbcTemplate jdbc;static TransactionTemplate tx;
 @BeforeAll static void setup()throws Exception{
  schema="fee_currency_"+UUID.randomUUID().toString().replace("-","");
  var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",Files.readString(Path.of(".local/database-password")).trim());
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
 }
 @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("fee_currency_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 @Test void mixedRegionalReportCannotOverwriteUsdFeesWithAnotherCurrency(){
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
  tx.executeWithoutResult(s->{
   jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Fixture')",tenant,tenant.toString());
   jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','fixture','ATVPDKIKX0DER','fixture','ACTIVE','Fixture','UTC',now())",connection,tenant);
  });
  var now=Instant.now();var job=new AmazonSyncStore.Job(UUID.randomUUID(),tenant,UUID.randomUUID(),connection,"FEES_SNAPSHOT","RECONCILIATION","ATVPDKIKX0DER",now,now,null,0,0,false,null);
  var normalizer=new AmazonReportNormalizer(jdbc,tx,new ObjectMapper(),null);
  String report="sku\tcurrency\testimated-fee-total\testimated-referral-fee-per-unit\texpected-fulfillment-fee-per-unit\n"
    +"00001\tMXN\t200\t50\t150\n00001\tUSD\t8\t3\t5\n00001\tCAD\t20\t6\t14\n";
  assertThat(normalizer.normalize(job,report,null)).isEqualTo(1);
  var row=jdbc.queryForMap("SELECT seller_sku,currency,estimated_fee_total,raw_payload->>'currency' source_currency FROM amazon_fee_estimates WHERE tenant_id=? AND marketplace_connection_id=?",tenant,connection);
  assertThat(row).containsEntry("seller_sku","00001").containsEntry("currency","USD").containsEntry("source_currency","USD");
  assertThat((java.math.BigDecimal)row.get("estimated_fee_total")).isEqualByComparingTo("8");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_import_rejections WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Integer.class,tenant)).isZero();
 }
}
