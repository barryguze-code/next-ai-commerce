package com.nextaicommerce.platform.marketplace;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;
import tools.jackson.databind.ObjectMapper;
class MarketplaceInventoryEligibilityTest {
 final Instant now=Instant.parse("2026-10-21T12:00:00Z");
 Classification role(State s){return new Classification(s,Quality.VERIFIED,s==State.PARENT?Eligibility.EXCLUDED_PARENT:Eligibility.OFFER_ELIGIBLE,"VERIFIED",s.name(),now,now,now.plusSeconds(3600),1,1L,UUID.randomUUID());}
 MarketplaceInventoryEligibility.Source source(String sku,String asin,String fnsku,Instant time){return new MarketplaceInventoryEligibility.Source(sku,"US",asin,asin,fnsku,time,true,null,23);}
 @Test void defaultOffDoesNotReadAnySources(){var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var p=mock(AmazonVariationRelationshipProvider.class);var service=new MarketplaceInventoryEligibility(jdbc,p,new ObjectMapper(),false);assertThat(service.load(UUID.randomUUID(),UUID.randomUUID()).decisions()).isEmpty();verifyNoInteractions(jdbc,p);}
 @Test void parentExcludedWithoutContaminatingChildIdentity(){var rows=List.of(source("parent","CREAM","CREAM",now),source("000child","CREAM","CREAM",now));var d=MarketplaceInventoryEligibility.evaluate(rows,Map.of("parent",role(State.PARENT),"000child",role(State.CHILD)),now);assertThat(d.get("parent").parent()).isTrue();assertThat(d.get("parent").applicable()).isFalse();assertThat(d.get("parent").inventoryConflict()).isTrue();assertThat(d.get("000child").applicable()).isTrue();assertThat(rows.get(1).available()).isEqualTo(23);}
 @Test void unknownExpiredAndConflictingNeverCertify(){for(State s:List.of(State.UNKNOWN,State.CONFLICT)){var d=MarketplaceInventoryEligibility.evaluate(List.of(source("x","A","F",now)),Map.of("x",role(s)),now).get("x");assertThat(d.applicable()).isFalse();assertThat(d.parent()).isFalse();}assertThat(MarketplaceInventoryEligibility.evaluate(List.of(source("x","A","F",now)),Map.of(),now).get("x").applicable()).isFalse();}
 @Test void identityMismatchStaleAndDuplicateFnskuAreUnresolved(){var stale=source("stale","A","F1",now.minusSeconds(86400));var mismatch=new MarketplaceInventoryEligibility.Source("bad","US","A","B","F2",now,true,null,23);var rows=List.of(stale,mismatch,source("one","A","DUP",now),source("two","B","DUP",now));var roles=new HashMap<String,Classification>();rows.forEach(s->roles.put(s.sku(),role(State.STANDALONE)));var d=MarketplaceInventoryEligibility.evaluate(rows,roles,now);assertThat(d.values()).noneMatch(MarketplaceInventoryEligibility.Decision::applicable);assertThat(d.get("one").reason()).isEqualTo("DUPLICATE_FNSKU");assertThat(d.get("bad").reason()).isEqualTo("INVENTORY_IDENTITY");}
 @Test void sharedAsinAloneDoesNotDeduplicateAndZeroIsKnown(){var zero=new MarketplaceInventoryEligibility.Source("0001","US","A","A","F1",now,true,null,0);var rows=List.of(zero,source("0002","A","F2",now));var d=MarketplaceInventoryEligibility.evaluate(rows,Map.of("0001",role(State.CHILD),"0002",role(State.STANDALONE)),now);assertThat(d.values()).allMatch(MarketplaceInventoryEligibility.Decision::applicable);assertThat(zero.available()).isZero();}
}
