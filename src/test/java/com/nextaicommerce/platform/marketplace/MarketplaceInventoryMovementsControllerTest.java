package com.nextaicommerce.platform.marketplace;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MarketplaceInventoryMovementsControllerTest {
 @Test void staleWorkspaceCannotReadAndValidReadIsNotCached(){
  var tenant=UUID.randomUUID();var store=UUID.randomUUID();var workspace=mock(WorkspaceAccessRepository.class);var reader=mock(MarketplaceInventoryMovements.class);
  when(workspace.findConnection(tenant,store)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(store,"AMAZON","US"));
  var controller=new MarketplaceSkuController(mock(MarketplaceSkuRepository.class),workspace,mock(CatalogRepository.class));ReflectionTestUtils.setField(controller,"inventoryMovements",reader);
  var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);session.setAttribute("selectedStoreId",store);
  assertThatThrownBy(()->controller.inventoryMovements("SKU",null,true,0,UUID.randomUUID(),store,session)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThatThrownBy(()->controller.inventoryMovements("SKU",null,true,0,tenant,UUID.randomUUID(),session)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(reader);
  when(reader.read(tenant,store,"SKU",null,true,0)).thenReturn(new MarketplaceInventoryMovements.Result(List.of(),List.of(),false));
  var response=controller.inventoryMovements("SKU",null,true,0,tenant,store,session);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");verify(reader).read(tenant,store,"SKU",null,true,0);
  when(reader.read(tenant,store,"SKU",null,true,0)).thenThrow(new IllegalArgumentException("Changed component"));
  assertThatThrownBy(()->controller.inventoryMovements("SKU",null,true,0,tenant,store,session)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409").hasMessageContaining("Reopen");
 }
}
