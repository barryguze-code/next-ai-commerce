package com.nextaicommerce.platform.marketplace;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;import java.time.*;import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;

class MarketplaceOrganizationTest {
 @Test void defaultsAreMarketplaceOnlyAndUnknownNeverMatchesNewOrFbm(){
  assertThat(MarketplaceSkuFilters.defaults(Map.of()).get("f_condition")).isEqualTo("NEW");
  assertThat(MarketplaceSkuFilters.defaults(Map.of("f_condition","ALL")).get("f_condition")).isEqualTo("ALL");
  assertThat(MarketplaceSkuFilters.parse(Map.of()).sql()).isEqualTo("true");
  assertThat(MarketplaceSkuFilters.parse(Map.of("f_condition","NEW")).args()).containsExactly("NEW");
  assertThat(MarketplaceSkuFilters.parse(Map.of("f_channel","FBM")).sql()).contains("IN ('DEFAULT','MFN','MERCHANT','FBM')").doesNotContain("NOT LIKE");
  for(String raw:List.of("11","New","new_new"))assertThat(SkuCondition.key(raw)).isEqualTo("NEW");
  for(String raw:List.of("","unexpected","used unknown"))assertThat(SkuCondition.key(raw)).isEqualTo("UNKNOWN");
  assertThatThrownBy(()->MarketplaceSkuFilters.parse(Map.of("f_condition","NEW' OR true"))).isInstanceOf(IllegalArgumentException.class);
  assertThat(MarketplaceSkuFilters.clean(Map.of("f_condition","USED_GOOD","f_channel","FBA"))).containsEntry("f_condition","USED_GOOD");
 }
 @Test void emptyEvidenceHasNoInventedParents(){
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(eq("SELECT transaction_timestamp()"),eq(Timestamp.class))).thenReturn(Timestamp.from(Instant.parse("2026-10-22T12:00:00Z")));
  var provider=mock(AmazonVariationRelationshipProvider.class);var result=new MarketplaceSkuRelationships(jdbc,provider,new ObjectMapper()).load(UUID.randomUUID(),UUID.randomUUID());
  assertThat(result.children()).isEmpty();assertThat(result.parents()).isEmpty();assertThat(result.json()).isNull();verifyNoInteractions(provider);
 }
 @Test void onlyFreshVerifiedReciprocalFamilyCanBecomePresentationContext(){
  var jdbc=mock(JdbcTemplate.class);var provider=mock(AmazonVariationRelationshipProvider.class);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();Instant now=Instant.parse("2026-10-22T12:00:00Z");
  when(jdbc.queryForObject(eq("SELECT transaction_timestamp()"),eq(Timestamp.class))).thenReturn(Timestamp.from(now));
  when(jdbc.queryForList(anyString(),eq(tenant),eq(store))).thenAnswer(call->{String sql=call.getArgument(0);assertThat(sql).contains("tenant_id=?","marketplace_connection_id=?");
   return sql.contains("direction='CHILD_OF'")?List.of(Map.of("subject_sku","000CHILD","related_sku","PARENT","marketplace_id","US"),Map.of("subject_sku","UNKNOWN","related_sku","PARENT","marketplace_id","US")):
    List.of(Map.of("seller_sku","PARENT","marketplace_id","US","observed_asin","P-ASIN"),Map.of("seller_sku","000CHILD","marketplace_id","US","observed_asin","C-ASIN"),Map.of("seller_sku","UNKNOWN","marketplace_id","US","observed_asin","U-ASIN"));});
  when(provider.classifyAt(eq(new Scope(tenant,store,"US")),anyCollection(),eq(now))).thenReturn(Map.of("PARENT",classification(State.PARENT,Quality.VERIFIED,now),"000CHILD",classification(State.CHILD,Quality.VERIFIED,now),"UNKNOWN",classification(State.UNKNOWN,Quality.EXPIRED,now)));
  var service=new MarketplaceSkuRelationships(jdbc,provider,new ObjectMapper());var result=service.load(tenant,store);
  assertThat(result.parents()).containsExactly("PARENT");assertThat(result.children()).containsOnlyKeys("000CHILD");assertThat(result.children().get("000CHILD")).isEqualTo(new MarketplaceSkuRelationships.Parent("PARENT","P-ASIN",1));
  when(provider.classifyAt(any(),anyCollection(),any())).thenReturn(Map.of("PARENT",classification(State.UNKNOWN,Quality.EXPIRED,now),"000CHILD",classification(State.CONFLICT,Quality.CONFLICT,now)));
  assertThat(service.load(tenant,store).children()).isEmpty();assertThat(service.load(tenant,store).parents()).isEmpty();
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void verifiedTotalUsesAllExactFamilyIdentitiesIndependentOfVisiblePage(){
  var jdbc=mock(JdbcTemplate.class);var provider=mock(AmazonVariationRelationshipProvider.class);
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();Instant now=Instant.parse("2026-10-22T12:00:00Z");
  when(jdbc.queryForObject(eq("SELECT transaction_timestamp()"),eq(Timestamp.class))).thenReturn(Timestamp.from(now));
  var observations=new ArrayList<Map<String,Object>>();var edges=new ArrayList<Map<String,Object>>();var classifications=new HashMap<String,Classification>();
  observations.add(Map.of("seller_sku","PARENT","marketplace_id","US","observed_asin","P-ASIN"));classifications.put("PARENT",classification(State.PARENT,Quality.VERIFIED,now));
  for(String child:List.of("00001","0001","00001 - US - FBA")){
   observations.add(Map.of("seller_sku",child,"marketplace_id","US","observed_asin","C-"+child));
   edges.add(Map.of("subject_sku",child,"related_sku","PARENT","marketplace_id","US"));classifications.put(child,classification(State.CHILD,Quality.VERIFIED,now));
  }
  when(jdbc.queryForList(anyString(),eq(tenant),eq(store))).thenAnswer(call->((String)call.getArgument(0)).contains("direction='CHILD_OF'")?edges:observations);
  when(provider.classifyAt(eq(new Scope(tenant,store,"US")),anyCollection(),eq(now))).thenReturn(classifications);
  edges.add(Map.of("subject_sku","00001","related_sku","PARENT","marketplace_id","OTHER"));
  var result=new MarketplaceSkuRelationships(jdbc,provider,new ObjectMapper()).load(tenant,store);
  assertThat(result.exactParent("PARENT").verifiedTotal()).isEqualTo(3);
  assertThat(result.exactParent("P-ASIN").sku()).isEqualTo("PARENT");
  assertThat(result.exactParent("PAR")).isNull();assertThat(result.exactParent("00001")).isNull();assertThat(result.exactParent(null)).isNull();
  assertThat(result.children()).hasSize(3);result.children().values().forEach(parent->assertThat(parent.verifiedTotal()).isEqualTo(3));
  assertThat(result.children().keySet()).containsExactlyInAnyOrder("00001","0001","00001 - US - FBA");
  // Provider expiry/incomplete/identity-conflict decisions remove context, never publish a guessed total.
  for(Quality quality:List.of(Quality.EXPIRED,Quality.INCOMPLETE,Quality.CONFLICT)){
   classifications.replaceAll((sku,c)->classification(State.UNKNOWN,quality,now));
   assertThat(new MarketplaceSkuRelationships(jdbc,provider,new ObjectMapper()).load(tenant,store).children()).isEmpty();
  }
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 static Classification classification(State state,Quality quality,Instant now){return new Classification(state,quality,Eligibility.UNRESOLVED,"FIXTURE",null,now,now,now.plusSeconds(60),1,1L,UUID.randomUUID());}
}
