package com.nextaicommerce.platform.orders;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PackageManagementAccessTest {
 @Test void dimensionsRequireAccountOperationAndAccessToEveryStore(){
  var access=mock(WorkspaceAccessRepository.class);var packages=mock(PackingPackageNames.class);
  var c=new OrderController(mock(OrderRepository.class),access,mock(com.nextaicommerce.platform.sync.AmazonManualOrderSync.class),mock(PackingSlipRepository.class));ReflectionTestUtils.setField(c,"packageNames",packages);
  var tenant=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var auth=new UsernamePasswordAuthenticationToken("planner",null);
  assertThatThrownBy(()->c.savePackageDimensions("Carton",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"inches",0,session,auth)).hasMessageContaining("403");verifyNoInteractions(packages);
  when(access.canOperateAccount(tenant,"planner")).thenReturn(true);var store=new WorkspaceAccessRepository.ConnectionView(UUID.randomUUID(),tenant,"Account","Store","AMAZON","seller","US","US","US","ACTIVE",null,false);when(access.listConnections(tenant,"")).thenReturn(List.of(store));when(access.listAccountOptions("planner",false)).thenReturn(List.of());
  assertThatThrownBy(()->c.savePackageDimensions("Carton",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"inches",0,session,auth)).hasMessageContaining("403");verifyNoInteractions(packages);
  when(access.listAccountOptions("planner",false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(tenant,"Account","A",null,List.of(store))));
  assertThat(c.savePackageDimensions("Carton",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"inches",0,session,auth)).containsEntry("status","saved");
  verify(packages).saveDimensions(tenant,"Carton",new PackingPackageNames.Dimensions(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"inches",0),"planner");
 }
}
