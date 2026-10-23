package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import com.nextaicommerce.platform.orders.SkuRefundHistory;
import com.nextaicommerce.platform.profit.ProfitRepository;
import tools.jackson.databind.ObjectMapper;

class MarketplaceSkuCsvGenerationTest {
 final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();final Instant T=Instant.parse("2026-10-19T12:00:00Z");
 final MarketplaceSkuRepository listings=mock(MarketplaceSkuRepository.class);final JdbcTemplate jdbc=mock(JdbcTemplate.class);final FbaReadRepository stocks=mock(FbaReadRepository.class);final SkuRefundHistory refunds=mock(SkuRefundHistory.class);final ProfitRepository profit=mock(ProfitRepository.class);
 final Map<String,Map<String,Object>> evidence=new HashMap<>();
 final MarketplaceSkuCsvExport export=new MarketplaceSkuCsvExport(listings,stocks,refunds,profit,jdbc,new ObjectMapper()){@Override Map<String,Map<String,Object>> rawEvidence(UUID t,UUID c,List<String> keys){assertThat(t).isEqualTo(tenant);assertThat(c).isEqualTo(store);return evidence;}};
 MarketplaceSkuCsvGenerationTest(){when(listings.relationshipPolicy(tenant,store)).thenReturn(MarketplaceSkuRelationships.Batch.empty());when(jdbc.queryForObject("SELECT transaction_timestamp()",Timestamp.class)).thenReturn(Timestamp.from(T));for(int d:List.of(7,14,21,28))when(jdbc.queryForObject("SELECT transaction_timestamp() - (? * interval '1 day')",Timestamp.class,d)).thenReturn(Timestamp.from(T.minusSeconds((long)d*86400)));}
 MarketplaceSkuCsvExport.Request request(List<String> columns){return new MarketplaceSkuCsvExport.Request("Tea","ALL","price","desc",Map.of(),columns,Map.of());}
 MarketplaceSkuRepository.SkuView sku(String key){evidence.put(key,Map.of("unknown_currency",0L));return MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku",key,"marketplaceId","US","listingStatus","ACTIVE","fulfillmentChannel","DEFAULT"));}
 void batch(int page,List<MarketplaceSkuRepository.SkuView> rows,long total){when(listings.list(tenant,store,"Tea","ALL","price","desc",page,100,Map.of())).thenReturn(new MarketplaceSkuRepository.SkuPage(rows,total,page,100));}
 @Test void chosenGroupOrderAllPagesAndLegacyMappingHaveOneCanonicalRow(){
  UUID item=UUID.randomUUID();var first=sku("0001");first=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","0001","marketplaceId","US","mappedProduct","Bundle","mappingQuantity",new BigDecimal("2")));evidence.put("0001",Map.of("unknown_currency",0L,"item_id",item,"account_sku","0009"));var second=sku("0002");var page=new ArrayList<MarketplaceSkuRepository.SkuView>();page.add(first);for(int i=1;i<100;i++)page.add(sku("P"+i));batch(0,page,101);batch(1,List.of(second),101);
  var artifact=export.generate(tenant,store,request(List.of("product-details","profit","status")));String text=new String(artifact.bytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertThat(artifact.rows()).isEqualTo(101);assertThat(text.indexOf("Estimated profit per SKU")).isLessThan(text.indexOf("Listing status"));assertThat(text).contains("\"0001\"","\"0002\"");assertThat(text).doesNotContain("\"0009\"");assertThat(text).doesNotContain("Review costs","<button");verify(profit,times(2)).skus(eq(tenant),eq(store),anyList(),eq(T.atZone(java.time.ZoneId.of("America/Los_Angeles")).toLocalDate()));
 }
 @Test void duplicateIdentityAndIncompletePageFailInsteadOfPartialArtifact(){
  var s=sku("0001");batch(0,List.of(s,s),2);assertThatThrownBy(()->export.generate(tenant,store,request(List.of("product-details")))).hasMessageContaining("Ambiguous listing identity");
  var page=new ArrayList<MarketplaceSkuRepository.SkuView>();page.add(s);for(int i=1;i<100;i++)page.add(sku("P"+i));batch(0,page,101);when(listings.list(tenant,store,"Tea","ALL","price","desc",1,100,Map.of())).thenThrow(new IllegalStateException("fixture page failure"));assertThatThrownBy(()->export.generate(tenant,store,request(List.of("product-details")))).hasMessageContaining("fixture page failure");
 }
 @Test void overTenThousandMatchingRowsCannotPublish(){
  var rows=new ArrayList<MarketplaceSkuRepository.SkuView>();for(int i=0;i<=10000;i++)rows.add(sku("S"+i));for(int i=0;i<=100;i++)batch(i,rows.subList(i*100,Math.min(rows.size(),(i+1)*100)),10001);assertThatThrownBy(()->export.generate(tenant,store,request(List.of("product-details")))).hasMessageContaining("10,000");
 }
 @Test void unknownRefundCurrencyPreventsFalseCompleteCurrencyTotals(){
  var s=sku("0001");evidence.put("0001",Map.of("unknown_currency",1L));batch(0,List.of(s),1);when(refunds.fourWeeks(tenant,store,List.of("0001"))).thenReturn(Map.of("0001","[{\"currency\":\"USD\",\"amount\":-10,\"units\":1,\"unknownAmounts\":0,\"unknownUnits\":0}]"));
  var result=export.generate(tenant,store,request(List.of("product-details","4w-sales")));String csv=new String(result.bytes(),java.nio.charset.StandardCharsets.UTF_8);assertThat(csv).doesNotContain("Refund amount USD (4 weeks)");assertThat(csv).doesNotContain("\"-10\"");assertThat(csv).doesNotContain(" state\"","Profit calculation state","\"UNKNOWN\"");
 }
 @Test void emptyMatchingSetStillHasStableRequiredHeaders(){batch(0,List.of(),0);var result=export.generate(tenant,store,request(List.of("product-details","profit")));assertThat(result.rows()).isZero();assertThat(new String(result.bytes(),java.nio.charset.StandardCharsets.UTF_8)).startsWith("\uFEFF\"Product title\",\"Seller SKU\",\"ASIN\"");}
 @Test void missingNextPageCannotTruncateSuccessfully(){var s=sku("x");batch(0,List.of(s),101);assertThatThrownBy(()->export.generate(tenant,store,request(List.of("product-details")))).hasMessageContaining("Incomplete listing page");}
 @Test void calendarDayAndElapsedWindowMismatchFailsClosed(){batch(0,List.of(),0);when(jdbc.queryForObject("SELECT transaction_timestamp() - (? * interval '1 day')",Timestamp.class,28)).thenReturn(Timestamp.from(T.minusSeconds(28L*86400+3600)));assertThatThrownBy(()->export.generate(tenant,store,request(List.of("product-details")))).hasMessageContaining("timezone mismatch");}
 @Test void selectedVisibleGroupsOnlyPublishFriendlySummaryHeadersInOrder(){
  var s=sku("0001");batch(0,List.of(s),1);
  String csv=new String(export.generate(tenant,store,request(List.of("profit","product-details","available","4w-sales"))).bytes(),java.nio.charset.StandardCharsets.UTF_8);
  String header=csv.substring(0,csv.indexOf("\r\n"));
  assertThat(header).startsWith("\uFEFF\"Estimated profit per SKU\",\"Profit currency\",\"Product title\",\"Seller SKU\",\"ASIN\"");
  assertThat(header).contains("Week 1 units (oldest)","Week 4 units (latest)");
  assertThat(header).doesNotContain("Marketplace ID","Refund","Condition","FNSKU","Shipping template","Inbound","Reserved","fee","UTC","state","note","Mapping status");
 }
 @Test void optionalAndSummaryQuantitiesDeduplicateWithoutExposingUnselectedGroups(){
  var s=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","fba","marketplaceId","US","fulfillmentChannel","AMAZON_NA"));
  evidence.put("fba",Map.of("unknown_currency",0L));batch(0,List.of(s),1);
  String csv=new String(export.generate(tenant,store,request(List.of("product-details","inbound","available","condition","status"))).bytes(),java.nio.charset.StandardCharsets.UTF_8);
  String header=csv.substring(0,csv.indexOf("\r\n"));
  assertThat(header.split("\"Inbound units\"",-1)).hasSize(2);
  assertThat(header.split("\"Condition\"",-1)).hasSize(2);
  assertThat(header.indexOf("Inbound units")).isLessThan(header.indexOf("Available units"));
  assertThat(header).doesNotContain("Unfulfillable","FNSKU","FBA fee","Snapshot");
 }

 @Test void verifiedChildExportsParentIdentifiersOnceAndUnknownStaysBlank(){
  var parent=new MarketplaceSkuRelationships.Parent("000PARENT","P-ASIN");
  when(listings.relationshipPolicy(tenant,store)).thenReturn(new MarketplaceSkuRelationships.Batch(Map.of("000CHILD",parent),Set.of("000PARENT"),null));
  batch(0,List.of(sku("000CHILD"),sku("UNKNOWN")),2);
  var artifact=export.generate(tenant,store,request(List.of("product-details")));String csv=new String(artifact.bytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertThat(artifact.rows()).isEqualTo(2);assertThat(csv).contains("\"Parent SKU\",\"Parent ASIN\"");
  assertThat(csv.lines().filter(l->l.contains("000PARENT"))).hasSize(1);assertThat(csv.lines()).hasSize(3);
  assertThat(csv.lines().filter(l->l.contains("UNKNOWN")).findFirst().orElseThrow()).doesNotContain("000PARENT","P-ASIN");
 }

}
