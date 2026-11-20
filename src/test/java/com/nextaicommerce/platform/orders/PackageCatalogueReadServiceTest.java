package com.nextaicommerce.platform.orders;
import java.time.*;
import java.util.*;
import com.nextaicommerce.platform.config.PlatformReadCache;
import com.nextaicommerce.platform.profit.ProfitCostRepository;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PackageCatalogueReadServiceTest {
 @Test void choicesReuseSafeDisplayCacheAndIsolateAccountsDatesAndInvalidation(){
  var names=mock(PackingPackageNames.class);var costs=mock(ProfitCostRepository.class);var cache=new PlatformReadCache(true,Duration.ofSeconds(10),32);var service=new PackageCatalogueReadService(names,costs,cache);UUID a=UUID.randomUUID(),b=UUID.randomUUID();var date=LocalDate.of(2026,11,17);
  when(costs.rates(any())).thenReturn(List.of());when(names.choices(any(),anyList(),any())).thenAnswer(call->List.of(new PackingPackageNames.Choice(call.getArgument(0).toString(),null,"UNKNOWN","USD",call.getArgument(2),"",null,"")));
  assertThat(service.choices(a,date)).isSameAs(service.choices(a,date));verify(names,times(1)).choices(a,List.of(),date);
  assertThat(service.choices(b,date).getFirst().name()).isEqualTo(b.toString());service.choices(a,date.minusDays(1));verify(names,times(3)).choices(any(),anyList(),any());
  cache.invalidate();service.choices(a,date);verify(names,times(2)).choices(a,List.of(),date);
 }
 @Test void failedLoadIsRetriedRatherThanCached(){
  var names=mock(PackingPackageNames.class);var costs=mock(ProfitCostRepository.class);var service=new PackageCatalogueReadService(names,costs,new PlatformReadCache(true,Duration.ofSeconds(10),32));var a=UUID.randomUUID();var date=LocalDate.of(2026,11,17);when(costs.rates(a)).thenReturn(List.of());when(names.choices(a,List.of(),date)).thenThrow(new IllegalStateException("Unavailable")).thenReturn(List.of());assertThatThrownBy(()->service.choices(a,date)).hasMessage("Unavailable");assertThat(service.choices(a,date)).isEmpty();verify(names,times(2)).choices(a,List.of(),date);
 }
}
