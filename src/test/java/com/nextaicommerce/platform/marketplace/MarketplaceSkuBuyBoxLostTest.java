package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketplaceSkuBuyBoxLostTest {
 @Test void filterUsesSharedPredicateAndPreservesBoundTenantStoreSearchAndPagination(){
  var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();var store=UUID.randomUUID();
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{
   String sql=call.getArgument(0);Object[] args=Arrays.copyOfRange(call.getArguments(),2,call.getArguments().length);
   assertThat(sql).contains(MarketplaceSkuRepository.BUY_BOX_LOST_PREDICATE,"NOT family_is_parent","orders.purchase_date>=now()-interval '28 days'","NOT IN ('CANCELLED','CANCELED')","count(*) over() filtered_total","LIMIT ? OFFSET ?");
   assertThat(args).startsWith(tenant,store,tenant,store).endsWith(25,25);
   assertThat(Arrays.stream(args).filter("BUY_BOX_LOST"::equals).count()).isEqualTo(5);
   assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(args.length);
   return List.of();
  });
  new MarketplaceSkuRepository(jdbc).list(tenant,store,"exact SKU","BUY_BOX_LOST","sku","asc",1,25);
  verify(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
 }
 @Test void summaryUsesSamePredicateWithOneScopedAggregation(){
  var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();var store=UUID.randomUUID();
  when(jdbc.query(anyString(),any(ResultSetExtractor.class),any(Object[].class))).thenAnswer(call->{
   String sql=call.getArgument(0);Object[] args=Arrays.copyOfRange(call.getArguments(),2,call.getArguments().length);
   assertThat(sql).contains("recent_sales AS",MarketplaceSkuRepository.BUY_BOX_LOST_PREDICATE,"NOT verified_parent","GROUP BY item.seller_sku");
   assertThat(args).containsExactly(tenant,store,tenant,store);
   assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(args.length);
   return new MarketplaceSkuRepository.SkuSummary(0,0,0,0,0,0,0,0);
  });
  new MarketplaceSkuRepository(jdbc).summary(tenant,store);
  verify(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
 }
 @Test @EnabledIfSystemProperty(named="localTestDatabase",matches="true")
 void actualPostgresPredicateExcludesOosUnknownNoSalesEqualAndMismatchedCurrency()throws Exception{
  var password=Files.readString(Path.of(System.getProperty("testDatabasePasswordFile",".local/database-password"))).trim();
  var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test","postgres",password));
  // Read-only VALUES fixtures: no application tables, migrations or inventory writes.
  var result=jdbc.queryForList("""
   SELECT id FROM (VALUES
    ('eligible',1,2,10::numeric,9::numeric,'USD','USD'),
    ('oos',0,2,10,9,'USD','USD'),('reserved',0,2,10,9,'USD','USD'),
    ('no-sales',1,0,10,9,'USD','USD'),('unknown-sales',1,NULL,10,9,'USD','USD'),
    ('unknown-stock',NULL,2,10,9,'USD','USD'),('equal',1,2,10,10,'USD','USD'),
    ('below',1,2,9,10,'USD','USD'),('no-buybox',1,2,10,NULL,'USD','USD'),
    ('no-price',1,2,NULL,9,'USD','USD'),('foreign-currency',1,2,10,9,'USD','CAD'),
    ('currency-fallback',1,2,10,9,'USD',NULL),('unknown-currency',1,2,10,9,NULL,NULL)
   ) fixture(id,available,four_week_units,price,buy_box_price,comparison_currency,buy_box_currency)
   WHERE
   """+MarketplaceSkuRepository.BUY_BOX_LOST_PREDICATE+" ORDER BY id",String.class);
  assertThat(result).containsExactly("currency-fallback","eligible");
 }
}
