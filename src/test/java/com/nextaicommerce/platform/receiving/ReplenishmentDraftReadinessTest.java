package com.nextaicommerce.platform.receiving;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
class ReplenishmentDraftReadinessTest {
 @Test void completionBetweenControllerReadsStillPollsForTheSnapshotActuallyRendered(){
  UUID tenant=UUID.randomUUID();var service=mock(ReplenishmentSuggestions.class);var catalog=mock(com.nextaicommerce.platform.catalog.CatalogRepository.class);
  var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);session.setAttribute("selectedTenantName","Fixture");
  when(service.snapshot(tenant)).thenReturn(null,new ReplenishmentSuggestions.Snapshot(List.of(),java.time.Instant.now()));when(service.configuration(tenant)).thenReturn(Map.of());
  var controller=new ReplenishmentDraftController(service,catalog);var model=new ExtendedModelMap();var auth=new UsernamePasswordAuthenticationToken("fixture","",List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
  assertThat(controller.draft(session,auth,model,"",0,25,"all")).isEqualTo("replenishment-draft");assertThat(model.get("pending")).isEqualTo(true);verify(service).ensureFresh(tenant);
 }
 @Test void refreshPreservesSearchFilterAndPage(){
  UUID tenant=UUID.randomUUID();var service=mock(ReplenishmentSuggestions.class);var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var controller=new ReplenishmentDraftController(service,mock(com.nextaicommerce.platform.catalog.CatalogRepository.class));var auth=new UsernamePasswordAuthenticationToken("fixture","",List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
  assertThat(controller.refresh(session,auth,new ExtendedModelMap(),new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap(),"A & B","all",2,50)).isEqualTo("redirect:/app/inventory/replenishment?q=A%20%26%20B&status=all&page=2&size=50");verify(service).requestRefresh(tenant);
 }
}
