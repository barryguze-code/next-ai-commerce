package com.nextaicommerce.platform.profit;

import com.nextaicommerce.platform.orders.PackingPackageNames;
import com.nextaicommerce.platform.web.AccountSelectionController;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfitPackageChoicesTest {
 @Test void choicesUseSelectedAccountAndRequestedRateDate(){
  var costs=mock(ProfitCostRepository.class);var names=mock(com.nextaicommerce.platform.orders.PackageCatalogueReadService.class);
  var controller=new ProfitController(mock(ProfitRepository.class),costs,mock(ProfitPricePublisher.class));
  ReflectionTestUtils.setField(controller,"packageCatalogue",names);
  UUID tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);
  var date=LocalDate.of(2026,11,16);List<ProfitShippingRates.Rate> rates=List.of();
  var choices=List.of(new PackingPackageNames.Choice("Account Pak",null,"MISSING","USD",date,"",null,""));
  when(names.choices(tenant,date)).thenReturn(choices);
  assertThat(controller.packageChoices(session,date)).isEqualTo(choices);
  verify(names).choices(tenant,date);
 }
 @Test void missingAccountCannotReadPackageRates(){
  var costs=mock(ProfitCostRepository.class);var names=mock(com.nextaicommerce.platform.orders.PackageCatalogueReadService.class);
  var controller=new ProfitController(mock(ProfitRepository.class),costs,mock(ProfitPricePublisher.class));
  ReflectionTestUtils.setField(controller,"packageCatalogue",names);
  assertThatThrownBy(()->controller.packageChoices(new MockHttpSession(),null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  verifyNoInteractions(costs,names);
 }
}
