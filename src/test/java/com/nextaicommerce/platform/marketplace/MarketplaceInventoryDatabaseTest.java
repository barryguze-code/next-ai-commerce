package com.nextaicommerce.platform.marketplace;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;import java.nio.file.*;import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import com.nextaicommerce.platform.orders.OrderRepository;
import tools.jackson.databind.ObjectMapper;
@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class MarketplaceInventoryDatabaseTest {
 static String schema;static JdbcTemplate jdbc;static TransactionTemplate tx;
 @BeforeAll static void fixture()throws Exception{
  schema="sku_inventory_"+UUID.randomUUID().toString().replace("-","");
  var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema,"postgres",Files.readString(Path.of(".local/database-password")).trim());
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(ds);jdbc.execute("ALTER FUNCTION "+schema+".ensure_item_default_location() SET search_path TO "+schema);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
 }
 @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("sku_inventory_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 @Test void realQueriesApplyEligibilityBeforeFiltersCountsAndPaginationWithoutChangingSharedStock(){tx.executeWithoutResult(t->{
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Safety fixture')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','fixture','US','fixture','ACTIVE','Fixture','UTC',now())",store,tenant);
  var jsonRows=new ArrayList<Map<String,Object>>();
  for(int n=0;n<103;n++){String sku=n==0?"PARENT":n==1?"000CHILD":n==2?"UNKNOWN":"S"+n;
   jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,item_name,listing_status,fulfillment_channel) VALUES (?,?,'US',?,'CREAM',?,'INACTIVE','AMAZON')",tenant,store,sku,sku);
   jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,inbound_shipped_quantity,snapshot_at) VALUES (?,?,'US',?,'CREAM',?,23,160,now())",tenant,store,sku,n<3?"CREAM":"FN-"+sku);
   jsonRows.add(Map.of("sku",sku,"marketplace","US","kind",n==0?"PARENT":n==2?"UNRESOLVED":"ELIGIBLE","applicable",n!=0&&n!=2));
  }
  jdbc.update("UPDATE amazon_listings SET price=30,buy_box_price=25,currency='USD' WHERE tenant_id=? AND marketplace_connection_id=?",tenant,store);
  for(String key:List.of("PARENT","000CHILD")){
   jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status,fulfillment_channel,currency,operational_scope) VALUES (?,?,'US',?,now(),'Pending','AFN','USD','LIVE')",tenant,store,key);
   jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price,currency) VALUES (?,?,?,?,?,1,30,'USD')",tenant,store,key,key,key);
  }
  var orders=new OrderRepository(jdbc);var beforeOrders=orders.orders(tenant,store,"BUY_BOX_LOST","",0,25);
  assertThat(beforeOrders.rows()).hasSize(2);
  String snapshots=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM amazon_inventory_snapshots s",String.class);
  var relationship=mock(AmazonVariationRelationshipProvider.class);
  when(relationship.classifyAt(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyCollection(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
   AmazonVariationRelationshipProvider.Scope scope=call.getArgument(0);assertThat(scope.tenant()).isEqualTo(tenant);assertThat(scope.connection()).isEqualTo(store);assertThat(scope.marketplace()).isEqualTo("US");
   Collection<String> keys=call.getArgument(1);Instant now=call.getArgument(2);var result=new HashMap<String,AmazonVariationRelationshipProvider.Classification>();
   for(String key:keys){var state=key.equals("PARENT")?AmazonVariationRelationshipProvider.State.PARENT:key.equals("UNKNOWN")?AmazonVariationRelationshipProvider.State.UNKNOWN:AmazonVariationRelationshipProvider.State.CHILD;
    result.put(key,new AmazonVariationRelationshipProvider.Classification(state,state==AmazonVariationRelationshipProvider.State.UNKNOWN?AmazonVariationRelationshipProvider.Quality.MISSING:AmazonVariationRelationshipProvider.Quality.VERIFIED,state==AmazonVariationRelationshipProvider.State.PARENT?AmazonVariationRelationshipProvider.Eligibility.EXCLUDED_PARENT:state==AmazonVariationRelationshipProvider.State.UNKNOWN?AmazonVariationRelationshipProvider.Eligibility.UNRESOLVED:AmazonVariationRelationshipProvider.Eligibility.OFFER_ELIGIBLE,"fixture",state.name(),now,now,now.plusSeconds(3600),1,1L,UUID.randomUUID()));}
   return result;
  });
  var liveReadAdapter=new MarketplaceInventoryEligibility(jdbc,relationship,new ObjectMapper(),true);
  var actualBatch=liveReadAdapter.load(tenant,store);assertThat(actualBatch.decisions()).hasSize(103);assertThat(actualBatch.get("PARENT").parent()).isTrue();assertThat(actualBatch.get("000CHILD").applicable()).isTrue();assertThat(actualBatch.get("UNKNOWN").applicable()).isFalse();
  assertThat(liveReadAdapter.load(UUID.randomUUID(),store).decisions()).isEmpty();
  var policy=mock(MarketplaceInventoryEligibility.class);when(policy.load(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(MarketplaceInventoryEligibility.disabled());when(policy.load(tenant,store)).thenReturn(new MarketplaceInventoryEligibility.Batch(Map.of(),new ObjectMapper().writeValueAsString(jsonRows),Instant.now()));
  var repo=new MarketplaceSkuRepository(jdbc);repo.configureEligibility(policy);
  var summary=repo.summary(tenant,store);assertThat(summary.total()).isEqualTo(103);assertThat(summary.availableUnits()).isEqualTo(101*23);assertThat(summary.unresolvedInventory()).isEqualTo(1);assertThat(summary.excludedParents()).isEqualTo(1);
  var rows=repo.list(tenant,store,"","ALL","available","desc",0,100,Map.of());assertThat(rows.total()).isEqualTo(103);assertThat(rows.rows()).hasSize(100);assertThat(rows.rows()).noneMatch(r->r.sellerSku().equals("PARENT")||r.sellerSku().equals("UNKNOWN"));
  assertThat(repo.list(tenant,store,"","ALL","available","asc",0,100,Map.of("f_available_min","1")).total()).isEqualTo(101);
  assertThat(repo.list(tenant,store,"","INACTIVE","available","asc",0,100,Map.of()).total()).isEqualTo(101);
  assertThat(repo.list(tenant,store,"PARENT","ALL","status","asc",0,25).rows().getFirst().statusLabel()).isEqualTo("Parent SKU");
  var shared=new FbaReadRepository(jdbc).stocks(tenant,store,List.of("PARENT","000CHILD"));assertThat(shared.get("PARENT").available()).isEqualTo(23);assertThat(shared.get("000CHILD").totalInbound()).isEqualTo(160);
  assertThat(orders.orders(tenant,store,"BUY_BOX_LOST","",0,25).rows()).isEqualTo(beforeOrders.rows());
  var legacy=new MarketplaceSkuRepository(jdbc);assertThat(legacy.summary(tenant,store).availableUnits()).isEqualTo(103*23);
  assertThat(legacy.list(tenant,store,"","INACTIVE","available","asc",0,100).total()).isEqualTo(103);
  assertThat(repo.list(tenant,UUID.randomUUID(),"","ALL","available","asc",0,25).rows()).isEmpty();
  assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM amazon_inventory_snapshots s",String.class)).isEqualTo(snapshots);
 });}
 @Test void presentationAndConditionFiltersRunBeforePaginationWithoutChangingInventoryOrOtherStores(){tx.executeWithoutResult(t->{
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Organization fixture')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','fixture','US','fixture','ACTIVE','Fixture','UTC',now())",store,tenant);
  for(int i=0;i<65;i++)jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,item_name,listing_status,fulfillment_channel,condition_type,quantity) VALUES (?,?,'US',?,'ASIN','Fixture','ACTIVE',?,?,7)",tenant,store,String.format("%05d",i),i==63?null:i==62?"DEFAULT":"AMAZON_NA",i==64?null:i==61?"UsedGood":"11");

  var repo=new MarketplaceSkuRepository(jdbc);var relationships=mock(MarketplaceSkuRelationships.class);
  String projection="[{\"sku\":\"00000\",\"marketplace\":\"US\",\"is_parent\":true},{\"sku\":\"00001\",\"marketplace\":\"US\",\"is_parent\":false,\"parent_sku\":\"00000\",\"parent_asin\":\"PARENT-ASIN\"}]";
  when(relationships.load(tenant,store)).thenReturn(new MarketplaceSkuRelationships.Batch(Map.of("00001",new MarketplaceSkuRelationships.Parent("00000","PARENT-ASIN")),Set.of("00000"),projection));
  when(relationships.load(eq(tenant),argThat(v->!v.equals(store)))).thenReturn(MarketplaceSkuRelationships.Batch.empty());repo.configureRelationships(relationships);
  assertThatThrownBy(()->repo.saveMapping(tenant,store,"00000",List.of(UUID.randomUUID().toString()),List.of("1")))
   .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("variation parents");
  UUID product=UUID.randomUUID(),item=UUID.randomUUID();
  jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Fixture')",product);
  jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,display_name,account_sku) VALUES (?,?,?,'Fixture','00000')",item,tenant,product);
  jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value) VALUES (?,'UPC','012345678905')",product);
  jdbc.update("UPDATE amazon_listings SET catalog_barcodes='[\"00012345678905\"]',catalog_barcodes_asin=asin WHERE tenant_id=? AND seller_sku='00000'",tenant);
  String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(r) ORDER BY seller_sku)::text FROM amazon_listings r WHERE tenant_id=?",String.class,tenant);
  var mapper=new MarketplaceSkuAutoMapper(jdbc,tx);mapper.configureRelationships(relationships);
  assertThat(mapper.mapConnection(tenant,store)).isZero();
  assertThat(mapper.mapTenant(tenant).remaining()).isEqualTo(64);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sku_mappings WHERE tenant_id=?",Integer.class,tenant)).isZero();
  var first=repo.list(tenant,store,"","ALL","sku","asc",0,25,Map.of("f_condition","NEW","f_channel","FBA"));
  assertThat(first.total()).isEqualTo(60);assertThat(first.rows()).hasSize(25);assertThat(first.rows().getFirst().sellerSku()).isEqualTo("00001");
  var last=repo.list(tenant,store,"","ALL","sku","asc",2,25,Map.of("f_condition","NEW","f_channel","FBA"));assertThat(last.rows()).hasSize(10);
  assertThat(repo.list(tenant,store,"PARENT-ASIN","ALL","sku","asc",0,25,Map.of("f_condition","NEW")).rows()).extracting(MarketplaceSkuRepository.SkuView::sellerSku).containsExactly("00001");
  assertThat(repo.list(tenant,store,"","ALL","sku","asc",0,25,Map.of("f_condition","ALL")).total()).isEqualTo(64);
  assertThat(repo.list(tenant,store,"","ALL","sku","asc",0,25,Map.of("f_condition","ALL","f_channel","FBM")).rows()).extracting(MarketplaceSkuRepository.SkuView::sellerSku).containsExactly("00062");
  assertThat(repo.list(tenant,store,"","ALL","sku","asc",0,25,Map.of("f_condition","UNKNOWN")).rows()).extracting(MarketplaceSkuRepository.SkuView::sellerSku).containsExactly("00064");
  assertThat(repo.list(tenant,UUID.randomUUID(),"","ALL","sku","asc",0,25,Map.of("f_condition","ALL")).rows()).isEmpty();
  assertThat(repo.list(tenant,store,"","UNMAPPED","sku","asc",0,25,Map.of("f_condition","ALL")).total()).isEqualTo(64);
  assertThat(repo.summary(tenant,store).unmapped()).isEqualTo(64);
  repo.saveMapping(tenant,store,"00001",List.of(item.toString()),List.of("1")); // Verified child remains mappable.
  repo.saveMapping(tenant,store,"00064",List.of(item.toString()),List.of("1")); // Missing evidence remains eligible.
  jdbc.update("INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,marketplace_sku,account_catalog_item_id,status) VALUES (?,?,'00000',?,'ACTIVE')",tenant,store,item);
  String parentMapping=jdbc.queryForObject("SELECT to_jsonb(m)::text FROM marketplace_sku_mappings m WHERE tenant_id=? AND marketplace_sku='00000'",String.class,tenant);
  assertThatThrownBy(()->repo.clearMapping(tenant,store,"00000")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("variation parents");
  assertThat(mapper.mapConnection(tenant,store)).isZero(); // Do not repair components of an existing parent mapping.
  assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sku_mapping_components c JOIN marketplace_sku_mappings m ON m.id=c.marketplace_sku_mapping_id WHERE m.tenant_id=? AND m.marketplace_sku='00000'",Integer.class,tenant)).isZero();
  assertThat(jdbc.queryForObject("SELECT to_jsonb(m)::text FROM marketplace_sku_mappings m WHERE tenant_id=? AND marketplace_sku='00000'",String.class,tenant)).isEqualTo(parentMapping);
  assertThat(repo.summary(tenant,store).availableUnits()).isEqualTo(14); // Legacy stock calculation is not an enforcement activation.
  assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(r) ORDER BY seller_sku)::text FROM amazon_listings r WHERE tenant_id=?",String.class,tenant)).isEqualTo(before);
 });}

}
