package com.nextaicommerce.platform.catalog;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class CatalogRemovalDatabaseTest {
 static io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg;
 static JdbcTemplate db;static TransactionTemplate tx;static CatalogRemovalService service;
 UUID tenant=UUID.randomUUID(),global=UUID.randomUUID(),item=UUID.randomUUID();
 @BeforeAll static void start()throws Exception{
  pg=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
  var source=new DriverManagerDataSource(pg.getJdbcUrl("postgres","postgres"),"postgres",null);db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));service=new CatalogRemovalService(db);
  db.execute("CREATE TABLE global_catalog_products(id uuid primary key,canonical_name text,status text default 'ACTIVE',updated_at timestamptz)");
  db.execute("CREATE TABLE account_catalog_items(id uuid primary key,tenant_id uuid,global_product_id uuid references global_catalog_products(id),display_name text,account_sku text,status text default 'ACTIVE',updated_at timestamptz)");
  db.execute("CREATE TABLE global_product_identifiers(id uuid primary key,global_product_id uuid references global_catalog_products(id) on delete cascade)");
  db.execute("CREATE TABLE inventory_history(item_id uuid references account_catalog_items(id) on delete cascade,qty int)");
  db.execute("CREATE TABLE collaboration_reviews(subject_key text)");
 }
 @AfterAll static void stop()throws Exception{if(pg!=null)pg.close();}
 @BeforeEach void seed(){db.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Product')",global);db.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,display_name) VALUES (?,?,?,'Account product')",item,tenant,global);}
 CatalogRemovalService.Review review(boolean shared){return tx.execute(s->service.review(tenant,shared?global:item,shared));}
 String remove(boolean shared,String op){return tx.execute(s->service.remove(tenant,shared?global:item,shared,op));}
 @Test void unusedAccountCanBeDeletedWithoutDeletingGlobal(){assertThat(review(false).operation()).isEqualTo("DELETE");remove(false,"DELETE");assertThat(db.queryForObject("SELECT count(*) FROM account_catalog_items WHERE id=?",Integer.class,item)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM global_catalog_products WHERE id=?",Integer.class,global)).isOne();}
 @Test void cascadeHistoryBlocksDeletionAndInactivePreservesRows(){db.update("INSERT INTO inventory_history VALUES (?,12)",item);assertThat(review(false).operation()).isEqualTo("INACTIVE");remove(false,"INACTIVE");assertThat(db.queryForObject("SELECT qty FROM inventory_history WHERE item_id=?",Integer.class,item)).isEqualTo(12);assertThat(db.queryForObject("SELECT status FROM account_catalog_items WHERE id=?",String.class,item)).isEqualTo("INACTIVE");}
 @Test void evenZeroHistoricalStockIsDependency(){db.update("INSERT INTO inventory_history VALUES (?,0)",item);assertThat(review(false).operation()).isEqualTo("INACTIVE");}
 @Test void logicalConversationBlocksDeletion(){db.update("INSERT INTO collaboration_reviews VALUES (?)",item.toString());assertThat(review(false).operation()).isEqualTo("INACTIVE");}
 @Test void linkedAccountBlocksSharedDeletion(){assertThat(review(true).operation()).isEqualTo("ARCHIVE");remove(true,"ARCHIVE");assertThat(db.queryForObject("SELECT status FROM global_catalog_products WHERE id=?",String.class,global)).isEqualTo("ARCHIVED");assertThat(db.queryForObject("SELECT status FROM account_catalog_items WHERE id=?",String.class,item)).isEqualTo("ACTIVE");}
 @Test void unusedGlobalCanDeleteItsOwnIdentifiers(){remove(false,"DELETE");db.update("INSERT INTO global_product_identifiers VALUES (?,?)",UUID.randomUUID(),global);assertThat(review(true).operation()).isEqualTo("DELETE");remove(true,"DELETE");assertThat(db.queryForObject("SELECT count(*) FROM global_product_identifiers WHERE global_product_id=?",Integer.class,global)).isZero();}
 @Test void staleDeleteDoesNotSilentlyChangeStatus(){db.update("INSERT INTO inventory_history VALUES (?,1)",item);assertThatThrownBy(()->remove(false,"DELETE")).hasMessageContaining("dependencies changed");assertThat(db.queryForObject("SELECT status FROM account_catalog_items WHERE id=?",String.class,item)).isEqualTo("ACTIVE");}
 @Test void foreignAccountCannotReviewOrRemove(){UUID foreign=UUID.randomUUID();assertThatThrownBy(()->tx.execute(s->service.remove(foreign,item,false,"DELETE"))).hasMessageContaining("not found");}
 @Test void unknownOperationCannotWrite(){assertThatThrownBy(()->remove(false,"ARCHIVE")).hasMessageContaining("dependencies changed");assertThat(review(false).operation()).isEqualTo("DELETE");}
 @Test void rlsHiddenDependencyFailsClosed(){
  db.execute("CREATE ROLE limited_catalog_role");db.execute("GRANT SELECT,UPDATE,DELETE ON account_catalog_items TO limited_catalog_role");db.execute("GRANT SELECT ON inventory_history,collaboration_reviews TO limited_catalog_role");
  db.execute("ALTER TABLE inventory_history ENABLE ROW LEVEL SECURITY");db.execute("CREATE POLICY invisible ON inventory_history USING(false)");db.update("INSERT INTO inventory_history VALUES (?,5)",item);
  try{var result=tx.execute(s->{db.execute("SET LOCAL ROLE limited_catalog_role");return service.review(tenant,item,false);});assertThat(result.operation()).isEqualTo("INACTIVE");}finally{db.execute("ALTER TABLE inventory_history DISABLE ROW LEVEL SECURITY");}
 }
}
