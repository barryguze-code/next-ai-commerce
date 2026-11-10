package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.receiving.ReplenishmentPurchasePlans.*;

class ReplenishmentPurchasePlansDatabaseTest {
 static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;static io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg;
 UUID tenant,other,item,item2,vendor,actor;String email;ReplenishmentPurchasePlans repo;
 @BeforeAll static void setup()throws Exception{
  schema="plans_test_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
  if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("testDatabasePasswordFile",".local/database-password"))).trim();}
  else{pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();url=pg.getJdbcUrl("postgres","postgres");schema="public";}
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  for(String fn:List.of("ensure_item_default_location","initialize_account_warehouse"))jdbc.execute("ALTER FUNCTION "+schema+"."+fn+"() SET search_path TO "+schema);
 }
 @AfterAll static void cleanup()throws Exception{if(jdbc!=null&&schema.matches("plans_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(pg!=null)pg.close();}
 @BeforeEach void fixture(){tenant=UUID.randomUUID();other=UUID.randomUUID();actor=UUID.randomUUID();vendor=UUID.randomUUID();email=actor+"@test.invalid";repo=new ReplenishmentPurchasePlans(jdbc);
  tx.executeWithoutResult(s->{for(UUID id:List.of(tenant,other))jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Planning fixture')",id,id.toString());jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Planner')",actor,email);jdbc.update("INSERT INTO vendors(id,tenant_id,name) VALUES (?,?,'Fixture vendor')",vendor,tenant);item=product(tenant,"000123");item2=product(tenant,"000456");offer(item,vendor,"1.2345");offer(item2,vendor,"2.0000");});
 }
 UUID product(UUID account,String code){UUID product=UUID.randomUUID(),id=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name,units_per_case) VALUES (?,'Fixture product',12)",product);jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,?)",id,account,product,code);return id;}
 void offer(UUID id,UUID v,String cost){jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,is_default) VALUES (?,?,?,'000123',?,true)",tenant,v,id,new BigDecimal(cost));}
 Command command(UUID id,long revision,List<InputLine> lines,String quote){return new Command(id,revision,"November plan",lines,quote);}
 Plan save(Command c,boolean submit){return tx.execute(s->repo.save(tenant,email,c,submit));}
 @Test void draftPersistsAcrossRepositoryInstancesAndEditsKeepCreationDate(){var lines=List.of(new InputLine(item,24));UUID id=UUID.randomUUID();var p=save(command(id,0,lines,null),false);assertThat(p.lines().getFirst().code()).isEqualTo("000123");assertThat(p.lines().getFirst().each()).isEqualTo(24);assertThat(p.lines().getFirst().pack()).isEqualByComparingTo("12");
  var read=tx.execute(s->new ReplenishmentPurchasePlans(jdbc).get(tenant,id));assertThat(read).isEqualTo(p);
  var changed=save(command(id,1,List.of(new InputLine(item,36)),null),false);assertThat(changed.createdAt()).isEqualTo(p.createdAt());assertThat(changed.revision()).isEqualTo(2);assertThat(changed.lines().getFirst().each()).isEqualTo(36);
  assertThatThrownBy(()->save(command(id,1,lines,null),false)).hasMessageContaining("draft changed");
 }
 @Test void submitProducesOnePoPerVendorAndIsIdempotentWithoutStockOrReceiptChanges(){UUID second=UUID.randomUUID();jdbc.update("INSERT INTO vendors(id,tenant_id,name) VALUES (?,?,'Second vendor')",second,tenant);jdbc.update("UPDATE vendor_catalog_offers SET vendor_id=? WHERE tenant_id=? AND account_catalog_item_id=?",second,tenant,item2);
  var lines=List.of(new InputLine(item,24),new InputLine(item2,3));var q=tx.execute(s->repo.quote(tenant,lines));UUID id=UUID.randomUUID();long before=jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries",Long.class),receipts=jdbc.queryForObject("SELECT count(*) FROM receiving_line_receipts",Long.class);
  var c=command(id,0,lines,q.revision());var p=save(c,true);assertThat(p.pos()).hasSize(2);assertThat(p.status()).isEqualTo("SUBMITTED");assertThat(save(c,true)).isEqualTo(p);assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries",Long.class)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM receiving_line_receipts",Long.class)).isEqualTo(receipts);
  assertThatThrownBy(()->save(command(id,1,List.of(new InputLine(item,2)),q.revision()),true)).hasMessageContaining("already submitted");
 }
 @Test void missingCostOrVendorAndChangedTermsCannotSubmitOrPartiallySave(){jdbc.update("DELETE FROM vendor_catalog_offers WHERE tenant_id=? AND account_catalog_item_id=?",tenant,item2);var lines=List.of(new InputLine(item,2),new InputLine(item2,4));var q=tx.execute(s->repo.quote(tenant,lines));UUID id=UUID.randomUUID();assertThatThrownBy(()->save(command(id,0,lines,q.revision()),true)).hasMessageContaining("vendor and catalogue cost");assertThat(jdbc.queryForObject("SELECT count(*) FROM replenishment_purchase_plans WHERE id=?",Integer.class,id)).isZero();
  var own=List.of(new InputLine(item,2));var old=tx.execute(s->repo.quote(tenant,own));jdbc.update("UPDATE vendor_catalog_offers SET list_cost=4 WHERE tenant_id=? AND account_catalog_item_id=?",tenant,item);assertThatThrownBy(()->save(command(id,0,own,old.revision()),true)).hasMessageContaining("Buying terms changed");
 }
 @Test void foreignAndInactiveItemsAndDuplicateLinesAreRejected(){UUID foreign=tx.execute(s->product(other,"000999"));assertThatThrownBy(()->save(command(UUID.randomUUID(),0,List.of(new InputLine(foreign,1)),null),false)).hasMessageContaining("unavailable in this account");assertThatThrownBy(()->save(command(UUID.randomUUID(),0,List.of(new InputLine(item,1),new InputLine(item,2)),null),false)).hasMessageContaining("unique items");jdbc.update("UPDATE account_catalog_items SET status='INACTIVE' WHERE id=?",item);assertThatThrownBy(()->save(command(UUID.randomUUID(),0,List.of(new InputLine(item,1)),null),false)).hasMessageContaining("inactive");}
 @Test void submittedLinesRejectInsertMoveUpdateDeleteAndCrossTenantFk(){var lines=List.of(new InputLine(item,2));var q=tx.execute(s->repo.quote(tenant,lines));var p=save(command(UUID.randomUUID(),0,lines,q.revision()),true);
  assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("UPDATE replenishment_purchase_plan_lines SET quantity=4 WHERE tenant_id=? AND plan_id=?",tenant,p.id()))).hasMessageContaining("immutable");
  assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("INSERT INTO replenishment_purchase_plan_lines SELECT tenant_id,plan_id,?,vendor_id,name,item_code,barcode,vendor_name,dc,units_per_case,quantity,unit_cost,currency FROM replenishment_purchase_plan_lines WHERE tenant_id=? AND plan_id=?",item2,tenant,p.id()))).hasMessageContaining("immutable");
  var draft=save(command(UUID.randomUUID(),0,List.of(new InputLine(item2,1)),null),false);
  assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("UPDATE replenishment_purchase_plan_lines SET plan_id=? WHERE tenant_id=? AND plan_id=?",p.id(),tenant,draft.id()))).hasMessageContaining("immutable");
  assertThatThrownBy(()->tx.executeWithoutResult(s->repo.get(other,p.id()))).hasMessageContaining("not found");
 }
 @Test void casePackPrecisionMatchesCatalogue(){jdbc.update("UPDATE global_catalog_products SET units_per_case=1.5000 WHERE id=(SELECT global_product_id FROM account_catalog_items WHERE id=?)",item);var p=save(command(UUID.randomUUID(),0,List.of(new InputLine(item,3)),null),false);assertThat(p.lines().getFirst().pack()).isEqualByComparingTo("1.5");}
 @Test void rlsIsForcedAndUsesTenantScope() {assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace=?::regnamespace AND relname IN ('replenishment_purchase_plans','replenishment_purchase_plan_lines','replenishment_vendor_pos') AND relrowsecurity AND relforcerowsecurity",Integer.class,schema)).isEqualTo(3);}
 @Test void deletesOnlyMatchingUnchangedDraftAndPreservesInventory(){
  UUID id=UUID.randomUUID();var lines=List.of(new InputLine(item,24));save(command(id,0,lines,null),false);
  long stock=jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries",Long.class);
  assertThatThrownBy(()->tx.executeWithoutResult(t->repo.deleteDraft(other,id,1))).hasMessageContaining("not found");
  assertThatThrownBy(()->tx.executeWithoutResult(t->repo.deleteDraft(tenant,id,2))).hasMessageContaining("draft changed");
  assertThat(tx.execute(t->repo.get(tenant,id)).lines()).hasSize(1);
  tx.executeWithoutResult(t->repo.deleteDraft(tenant,id,1));
  assertThatThrownBy(()->tx.execute(t->repo.get(tenant,id))).hasMessageContaining("not found");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries",Long.class)).isEqualTo(stock);
  assertThatThrownBy(()->save(command(id,0,lines,null),false)).hasMessageContaining("was deleted");
  var remainingDrafts=tx.execute(t->repo.list(tenant,"DRAFT"));assertThat(remainingDrafts).isEmpty();
  var q=tx.execute(t->repo.quote(tenant,lines));UUID submitted=UUID.randomUUID();save(command(submitted,0,lines,q.revision()),true);
  assertThatThrownBy(()->tx.executeWithoutResult(t->repo.deleteDraft(tenant,submitted,1))).hasMessageContaining("cannot be deleted");
  assertThat(tx.execute(t->repo.get(tenant,submitted)).pos()).hasSize(1);
 }
 @Test void manualCandidatesUseAccountTermsWithoutForecastOrInventoryMutation(){
 var choices=tx.execute(s->repo.catalogueChoices(tenant,List.of(item2),"Fixture vendor"));assertThat(choices).hasSize(1);assertThat(choices.getFirst().each()).isZero();assertThat(choices.getFirst().pack()).isEqualByComparingTo("12");assertThat(choices.getFirst().cost()).isEqualByComparingTo("2");assertThat(choices.getFirst().code()).isEqualTo("000123");
 var filtered=tx.execute(s->repo.catalogueChoices(tenant,List.of(item2),"Other vendor"));assertThat(filtered).isEmpty();
 UUID foreign=tx.execute(s->product(other,"foreign"));assertThatThrownBy(()->tx.execute(s->repo.catalogueChoices(tenant,List.of(foreign),""))).hasMessageContaining("unavailable in this account");
 var plan=save(command(UUID.randomUUID(),0,List.of(new InputLine(item2,36)),null),false);assertThat(plan.lines().getFirst().each()).isEqualTo(36);assertThat(plan.lines().getFirst().vendorId()).isEqualTo(vendor);
 }
}
