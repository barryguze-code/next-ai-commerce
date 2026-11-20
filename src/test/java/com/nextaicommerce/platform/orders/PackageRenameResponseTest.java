package com.nextaicommerce.platform.orders;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PackageRenameResponseTest {
 @Test void validationAndConflictsReachTheDialogWithoutChangingAssignments() throws Exception {
  var access=mock(WorkspaceAccessRepository.class);var names=mock(PackingPackageNames.class);var tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);
  when(access.canOperateAccount(tenant,"planner")).thenReturn(true);when(access.listAccountOptions("planner",false)).thenReturn(List.of());when(access.listConnections(tenant,"")).thenReturn(List.of());
  var c=new OrderController(mock(OrderRepository.class),access,mock(com.nextaicommerce.platform.sync.AmazonManualOrderSync.class),mock(PackingSlipRepository.class));ReflectionTestUtils.setField(c,"packageNames",names);var mvc=MockMvcBuilders.standaloneSetup(c).build();
  when(names.preview(tenant,"Old","New")).thenThrow(new IllegalArgumentException("This package changed. Refresh and try again."));
  mvc.perform(post("/app/orders/packing-packages/review").session(session).principal(auth).param("source","Old").param("destination","New")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("This package changed. Refresh and try again."));
  when(names.apply(tenant,"Old","New","r",2,"planner")).thenThrow(new IllegalArgumentException("Package assignments changed. Review again before saving."));
  mvc.perform(post("/app/orders/packing-packages/apply").session(session).principal(auth).param("source","Old").param("destination","New").param("revision","r").param("affectedAssignments","2")).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Package assignments changed. Review again before saving."));
 }
}
