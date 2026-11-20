package com.nextaicommerce.platform.receiving;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
class ReplenishmentPurchasePlanControllerTest {
 @Test void deniedAndStaleWorkspacesNeverReachRepository(){var repo=mock(ReplenishmentPurchasePlans.class);var access=mock(WorkspaceAccessRepository.class);var c=new ReplenishmentPurchasePlanController(repo,access,mock(com.nextaicommerce.platform.catalog.CatalogRepository.class));UUID tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);
 assertThatThrownBy(()->c.list(session,auth,tenant,"DRAFT")).hasMessageContaining("403");verifyNoInteractions(repo);
 when(access.canOperateAccount(tenant,"planner")).thenReturn(true);assertThatThrownBy(()->c.list(session,auth,UUID.randomUUID(),"DRAFT")).hasMessageContaining("409");verifyNoInteractions(repo);
 c.list(session,auth,tenant,"DRAFT");verify(repo).list(tenant,"DRAFT");}
 @Test void deletionRequiresAuthorizedMatchingWorkspace(){var repo=mock(ReplenishmentPurchasePlans.class);var access=mock(WorkspaceAccessRepository.class);var c=new ReplenishmentPurchasePlanController(repo,access,mock(com.nextaicommerce.platform.catalog.CatalogRepository.class));UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);var request=new ReplenishmentPurchasePlans.DeleteDraft(1);
 assertThatThrownBy(()->c.delete(session,auth,tenant,id,request)).hasMessageContaining("403");verifyNoInteractions(repo);
 when(access.canOperateAccount(tenant,"planner")).thenReturn(true);assertThatThrownBy(()->c.delete(session,auth,UUID.randomUUID(),id,request)).hasMessageContaining("409");verifyNoInteractions(repo);
 assertThat(c.delete(session,auth,tenant,id,request)).containsEntry("deleted",true);verify(repo).deleteDraft(tenant,id,1);
 }
 @Test void catalogueSearchGuardsAccountAndDoesNotLoadWholeCatalogue(){var repo=mock(ReplenishmentPurchasePlans.class);var access=mock(WorkspaceAccessRepository.class);var catalog=mock(com.nextaicommerce.platform.catalog.CatalogRepository.class);var c=new ReplenishmentPurchasePlanController(repo,access,catalog);UUID tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);
 assertThatThrownBy(()->c.catalogue(session,auth,tenant,"product","")).hasMessageContaining("403");verifyNoInteractions(repo,catalog);
 when(access.canOperateAccount(tenant,"planner")).thenReturn(true);assertThatThrownBy(()->c.catalogue(session,auth,UUID.randomUUID(),"product","")).hasMessageContaining("409");verifyNoInteractions(repo,catalog);
 assertThat(c.catalogue(session,auth,tenant,"x","").getBody()).isEqualTo(List.of());verifyNoInteractions(repo,catalog);
 when(catalog.searchAccountItems(tenant,"product",20)).thenReturn(List.of());when(repo.catalogueChoices(tenant,List.of(),"Vendor")).thenReturn(List.of());when(catalog.pickerImages(tenant,List.of())).thenReturn(Map.of());c.catalogue(session,auth,tenant,"product","Vendor");verify(catalog).searchAccountItems(tenant,"product",20);verify(repo).catalogueChoices(tenant,List.of(),"Vendor");
 }
}
