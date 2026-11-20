package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.catalog.*;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;
import com.nextaicommerce.platform.marketplace.*;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit local-only verification: every synthetic write is in one rolled-back transaction. Never starts a service. */
@EnabledIfSystemProperty(named="shopifyLocalFixtures",matches="true")
class ShopifyCatalogueLocalDatabaseTest {
    static UUID id(String name){return UUID.nameUUIDFromBytes(("SHOPIFY-ROLLBACK-FIXTURE:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static void scope(JdbcTemplate j,UUID tenant){j.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    static void rejected(JdbcTemplate j,Runnable action){j.execute("SAVEPOINT rejected_fixture");Throwable error=null;try{action.run();}catch(RuntimeException e){error=e;}finally{j.execute("ROLLBACK TO SAVEPOINT rejected_fixture");j.execute("RELEASE SAVEPOINT rejected_fixture");}assertThat(error).isNotNull();}
    @Test void rollbackOnlyScopedPublicationMappingAndConsumerContract()throws Exception{
        Properties p=new Properties();try(var in=Files.newInputStream(Path.of(System.getProperty("shopifyLocalRuntime")))){p.load(in);}
        String url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_fee_readiness_20261024";
        var ds=new DriverManagerDataSource(url,"nextcommerce_fee_preview",p.getProperty("spring.datasource.password"));var j=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThat(j.queryForObject("SELECT current_database()||'|'||inet_server_port()",String.class)).isEqualTo("next_ai_commerce_fee_readiness_20261024|55432");
        assertThat(j.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
        assertThat(j.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(117);
        UUID tenant=id("tenant"),other=id("other"),conn=id("store"),second=id("second"),amazon=id("amazon"),owner=id("owner"),viewer=id("viewer"),product=id("product"),item=id("item"),foreign=id("foreign");
        assertThat(j.queryForObject("SELECT count(*) FROM tenants WHERE id IN (?,?)",Integer.class,tenant,other)).isZero();
        tx.executeWithoutResult(status->{try{
            j.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Rollback Shopify fixture'),(?,?,'Rollback foreign fixture')",tenant,"fixture-"+tenant,other,"fixture-"+other);
            j.update("INSERT INTO app_users(id,email,display_name) VALUES (?,'shopify-owner@example.invalid','Fixture owner'),(?,'shopify-viewer@example.invalid','Fixture viewer')",owner,viewer);
            scope(j,tenant);j.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'OWNER'),(?,?,'VIEWER')",tenant,owner,tenant,viewer);
            for(UUID c:List.of(conn,second))j.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'SHOPIFY',?,?,'ACTIVE','Fixture Shopify','fixture://no-credentials','UTC',now())",c,tenant,(c.equals(conn)?"fixture-one":"fixture-two")+".myshopify.com",(c.equals(conn)?"fixture-one":"fixture-two")+".myshopify.com");
            j.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','FIXTURE','ATVPDKIKX0DER','ACTIVE','Fixture Amazon','fixture://none','UTC',now())",amazon,tenant);
            j.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",tenant,viewer,conn,owner);
            j.update("INSERT INTO global_catalog_products(id,canonical_name,unit_of_measure) VALUES (?,'Fixture pizza','EA')",product);j.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,'0000123')",item,tenant,product);
            scope(j,other);j.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'OWNER')",other,owner);j.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,'999')",foreign,other,product);scope(j,tenant);
            long ledger=j.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant);long history=j.queryForObject("SELECT count(*) FROM amazon_inventory_snapshots WHERE tenant_id=?",Long.class,tenant);
            var access=new WorkspaceAccessRepository(j);var evidence=new ShopifyCatalogRepository(j);var jobs=new ShopifyCatalogJobs(j,tx,access,evidence,true);var skus=new ShopifySkuRepository(j,true);
            UUID jobId=jobs.enqueue(tenant,conn,"shopify-owner@example.invalid");assertThat(jobs.enqueue(tenant,conn,"shopify-owner@example.invalid")).isEqualTo(jobId);var job=jobs.claim(tenant,conn,jobId);assertThat(job).isNotNull();assertThat(jobs.claim(tenant,conn,jobId)).isNull();jobs.reserve(job);
            Instant updated=Instant.parse("2026-10-26T12:00:00Z");var sourceProduct=new ShopifyCatalogClient.Product("gid://shopify/Product/100","Fixture pizza","Fixture","ACTIVE",updated,null,"https://cdn.shopify.com/fixture.png");var variants=new ArrayList<ShopifyCatalogClient.Variant>();
            String[] raw={"0000123-EAx3","0000123-EAx12","DUP","DUP",null};for(int n=0;n<raw.length;n++)variants.add(new ShopifyCatalogClient.Variant("gid://shopify/ProductVariant/"+(200+n),"gid://shopify/InventoryItem/300",raw[n],"Default Title",new BigDecimal("12.34"),updated,"[]","[]",true,null,sourceProduct));
            var page=new ShopifyCatalogClient.Page(variants,"terminal",false,50,950,100);jobs.publish(job,"{\"fixture\":\"raw-page\"}",page,"USD",()->{var plan=skus.previewAutoMap(tenant,conn,job.run());assertThat(plan).hasSize(5);assertThat(plan.stream().filter(c->!c.components().isEmpty())).hasSize(2);skus.autoMap(tenant,conn,job.run());});
            assertThat(jobs.status(tenant,conn).state()).isEqualTo("COMPLETED");assertThat(skus.list(tenant,conn,"","ALL","sku","asc",0,25).total()).isEqualTo(5);assertThat(skus.counts(tenant,conn)).containsEntry("MAPPED",2L).containsEntry("UNMAPPED",3L);assertThat(skus.list(tenant,conn,"0000123","ALL","sku","asc",0,25).total()).isEqualTo(2);
            var refs=new CatalogRepository(j).listMarketplaceSkus(tenant,List.of(item));assertThat(refs).hasSize(2);assertThat(refs).allSatisfy(r->{assertThat(r.channel()).isEqualTo("SHOPIFY");assertThat(r.asin()).isNull();assertThat(r.marketplaceQuantity()).isNull();assertThat(r.shopifyAdmin()).startsWith("https://fixture-one.myshopify.com/admin/products/100/variants/");});
            var rows=skus.list(tenant,conn,"","ALL","sku","asc",0,25).rows();var blank=rows.stream().filter(r->r.sku()==null).findFirst().orElseThrow();skus.save(tenant,conn,blank.id(),blank.revision(),List.of(item.toString()),List.of("1"),()->{});skus.clear(tenant,conn,blank.id(),()->{});skus.autoMap(tenant,conn,job.run());assertThat(skus.find(tenant,conn,blank.id()).blocked()).isTrue();
            var first=rows.stream().filter(r->"0000123-EAx3".equals(r.sku())).findFirst().orElseThrow();assertThat(skus.components(tenant,conn,first.id()).getFirst().quantity()).isEqualByComparingTo("3");
            rejected(j,()->j.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku) VALUES (?,?,?,'legacy-not-allowed')",tenant,conn,item));
            rejected(j,()->j.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,shopify_variant_id) VALUES (?,?,?,NULL,?)",tenant,amazon,item,first.id()));
            rejected(j,()->j.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,shopify_variant_id) VALUES (?,?,?,NULL,?)",tenant,second,item,first.id()));
            rejected(j,()->j.update("UPDATE shopify_source_documents SET response_body='changed' WHERE tenant_id=?",tenant));rejected(j,()->j.update("DELETE FROM shopify_source_documents WHERE tenant_id=?",tenant));
            j.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku) VALUES (?,?,?,'AMAZON-SAFE') ON CONFLICT(tenant_id,marketplace_connection_id,marketplace_sku) DO UPDATE SET quantity_per_marketplace_unit=2",tenant,amazon,item);j.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku) VALUES (?,?,?,'AMAZON-SAFE') ON CONFLICT(tenant_id,marketplace_connection_id,marketplace_sku) DO UPDATE SET quantity_per_marketplace_unit=2",tenant,amazon,item);
            assertThat(j.queryForObject("SELECT quantity_per_marketplace_unit FROM marketplace_sku_mappings WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_sku='AMAZON-SAFE'",BigDecimal.class,tenant,amazon)).isEqualByComparingTo("2");
            var collaboration=new CollaborationRepository(j);var posted=collaboration.create(tenant,"MARKETPLACE_SKU",first.subjectKey(),"Fixture variant","Shopify","CONVERSATION","Fixture","Fixture message","TEAM_CHAT","{}","/app/marketplace-skus?q="+first.gid(),"shopify-owner@example.invalid",null,null,List.of());assertThat(collaboration.review(tenant,posted.reviewId(),"shopify-viewer@example.invalid")).isNotNull();j.update("DELETE FROM membership_store_access WHERE tenant_id=? AND user_id=?",tenant,viewer);assertThat(collaboration.review(tenant,posted.reviewId(),"shopify-viewer@example.invalid")).isNull();
            scope(j,other);assertThat(j.queryForObject("SELECT count(*) FROM shopify_variants WHERE tenant_id=?",Integer.class,tenant)).isZero();rejected(j,()->j.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,shopify_variant_id) VALUES (?,?,?,NULL,?)",other,conn,foreign,first.id()));scope(j,tenant);
            UUID again=jobs.enqueue(tenant,conn,"shopify-owner@example.invalid");var secondJob=jobs.claim(tenant,conn,again);jobs.reserve(secondJob);var changed=new ShopifyCatalogClient.Variant(first.gid(),first.inventoryGid(),"renamed-EAx12","Default Title",new BigDecimal("12.34"),updated.plusSeconds(1),"[]","[]",true,null,sourceProduct);jobs.publish(secondJob,"{\"fixture\":\"renamed\"}",new ShopifyCatalogClient.Page(List.of(changed),"end",false,50,950,100),"USD",()->skus.autoMap(tenant,conn,secondJob.run()));assertThat(skus.find(tenant,conn,first.id()).mappingNeedsReview()).isTrue();assertThat(skus.components(tenant,conn,first.id()).getFirst().quantity()).isEqualByComparingTo("3");
            // More than one work window, including adoption of an earlier capped job, retains one generation.
            UUID continuationId=jobs.enqueue(tenant,conn,"shopify-owner@example.invalid"),generation=null;
            // now() is constant in this outer rollback transaction; make the intended latest fixture explicit.
            j.update("UPDATE marketplace_sync_jobs SET created_at=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,continuationId);
            for(int pageNumber=0;pageNumber<21;pageNumber++){
                j.update("UPDATE marketplace_sync_jobs SET available_at=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,continuationId);
                if(pageNumber==20){j.update("UPDATE marketplace_sync_jobs SET status='FAILED',error_message='Partial import: 200-variant limit reached.',completed_at=clock_timestamp() WHERE tenant_id=? AND id=?",tenant,continuationId);j.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS' WHERE tenant_id=? AND id=?",tenant,generation);assertThat(jobs.enqueue(tenant,conn,"shopify-owner@example.invalid")).isEqualTo(continuationId);}
                var continuing=jobs.claim(tenant,conn,continuationId);assertThat(continuing).isNotNull();if(generation==null)generation=continuing.run();else assertThat(continuing.run()).isEqualTo(generation);
                if(pageNumber==20){assertThat(continuing.cursor()).isEqualTo("page20");assertThat(((Number)continuing.state().get("requests")).intValue()).isEqualTo(20);assertThat(((List<?>)continuing.state().get("seen"))).hasSize(200);}
                jobs.reserve(continuing);var entries=new ArrayList<ShopifyCatalogClient.Variant>();for(int n=0;n<10;n++){int sequence=pageNumber*10+n;entries.add(new ShopifyCatalogClient.Variant("gid://shopify/ProductVariant/"+(1000+sequence),"gid://shopify/InventoryItem/"+(1000+sequence),"UNMATCHED-"+sequence,"Default Title",BigDecimal.ONE,updated,"[]","[]",true,null,sourceProduct));}
                boolean more=pageNumber<20;jobs.publish(continuing,"{\"fixturePage\":"+pageNumber+"}",new ShopifyCatalogClient.Page(entries,"page"+(pageNumber+1),more,50,950,100),"USD",()->skus.autoMap(tenant,conn,continuing.run()));
                if(pageNumber==19)assertThat(jobs.status(tenant,conn).state()).isEqualTo("WAITING");
            }
            assertThat(jobs.status(tenant,conn).state()).isEqualTo("COMPLETED");assertThat(jobs.status(tenant,conn).checked()).isEqualTo(210);
            assertThat(j.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant)).isEqualTo(ledger);assertThat(j.queryForObject("SELECT count(*) FROM amazon_inventory_snapshots WHERE tenant_id=?",Long.class,tenant)).isEqualTo(history);
        }finally{status.setRollbackOnly();}});
        assertThat(j.queryForObject("SELECT count(*) FROM tenants WHERE id IN (?,?)",Integer.class,tenant,other)).isZero();
    }
}
