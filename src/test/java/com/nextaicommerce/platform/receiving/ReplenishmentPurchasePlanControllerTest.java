package com.nextaicommerce.platform.receiving;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
class ReplenishmentPurchasePlanControllerTest {
 @Test void deniedAndStaleWorkspacesNeverReachRepository(){var repo=mock(ReplenishmentPurchasePlans.class);var access=mock(WorkspaceAccessRepository.class);var c=new ReplenishmentPurchasePlanController(repo,access);UUID tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);
 assertThatThrownBy(()->c.list(session,auth,tenant,"DRAFT")).hasMessageContaining("403");verifyNoInteractions(repo);
 when(access.canOperateAccount(tenant,"planner")).thenReturn(true);assertThatThrownBy(()->c.list(session,auth,UUID.randomUUID(),"DRAFT")).hasMessageContaining("409");verifyNoInteractions(repo);
 c.list(session,auth,tenant,"DRAFT");verify(repo).list(tenant,"DRAFT");}
 @Test void deletionRequiresAuthorizedMatchingWorkspace(){var repo=mock(ReplenishmentPurchasePlans.class);var access=mock(WorkspaceAccessRepository.class);var c=new ReplenishmentPurchasePlanController(repo,access);UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);var request=new ReplenishmentPurchasePlans.DeleteDraft(1);
 assertThatThrownBy(()->c.delete(session,auth,tenant,id,request)).hasMessageContaining("403");verifyNoInteractions(repo);
 when(access.canOperateAccount(tenant,"planner")).thenReturn(true);assertThatThrownBy(()->c.delete(session,auth,UUID.randomUUID(),id,request)).hasMessageContaining("409");verifyNoInteractions(repo);
 assertThat(c.delete(session,auth,tenant,id,request)).containsEntry("deleted",true);verify(repo).deleteDraft(tenant,id,1);
 }
}
