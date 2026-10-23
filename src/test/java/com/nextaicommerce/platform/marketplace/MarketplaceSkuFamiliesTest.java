package com.nextaicommerce.platform.marketplace;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.State;
class MarketplaceSkuFamiliesTest {
 @Test void noVerifiedParentDoesNotReadOrInventRelationships(){var jdbc=mock(JdbcTemplate.class);var listings=mock(MarketplaceSkuRepository.class);var service=new MarketplaceSkuFamilies(jdbc,listings);assertThat(service.load(UUID.randomUUID(),UUID.randomUUID(),List.of(),MarketplaceInventoryEligibility.disabled())).isEmpty();verifyNoInteractions(jdbc,listings);}
 @Test void onlySameMarketplaceVerifiedChildrenEnterDisclosureAndExactScopeIsBound(){
  var jdbc=mock(JdbcTemplate.class);var listings=mock(MarketplaceSkuRepository.class);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
  var p=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","P","marketplaceId","US"));var c=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","000C","marketplaceId","US"));
  var decisions=Map.of("P",new MarketplaceInventoryEligibility.Decision("P","US","PARENT",false,"VERIFIED",State.PARENT),"000C",new MarketplaceInventoryEligibility.Decision("000C","US","ELIGIBLE",true,"VERIFIED",State.CHILD),"UNKNOWN",new MarketplaceInventoryEligibility.Decision("UNKNOWN","US","UNRESOLVED",false,"MISSING",State.UNKNOWN));
  var batch=new MarketplaceInventoryEligibility.Batch(decisions,"[]",Instant.now());
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{assertThat((String)call.getArgument(0)).contains("tenant_id=? AND marketplace_connection_id=?","direction='PARENT_OF'");assertThat(Arrays.copyOfRange(call.getArguments(),2,call.getArguments().length)).containsExactly(tenant,store,"P");return List.of(List.of("P","000C","US"),List.of("P","UNKNOWN","US"),List.of("P","000C","CA"));});
  when(listings.exactListings(tenant,store,List.of("000C"),batch)).thenReturn(List.of(c));
  assertThat(new MarketplaceSkuFamilies(jdbc,listings).load(tenant,store,List.of(p),batch)).containsExactlyEntriesOf(Map.of("P",List.of(c)));
 }
}
