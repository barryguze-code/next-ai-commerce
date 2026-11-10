package com.nextaicommerce.platform.catalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogRemovalAccessTest {
 @Test void viewerCannotReviewRemovalOrMutate(){checkDenied("ROLE_VIEWER",false,true);}
 @Test void accountAdministratorCannotArchiveSharedIdentity(){checkDenied("ROLE_ADMIN",true,true);}
 @Test void operatorCannotRemoveFromInaccessibleAccount(){checkDenied("ROLE_OPERATOR",false,false);}
 @Test void platformAdminReviewsGlobalScopeWithoutAccountDependency(){
  var controller=new CatalogController(null,null);var service=mock(CatalogRemovalService.class);ReflectionTestUtils.setField(controller,"removals",service);UUID id=UUID.randomUUID();when(service.review(null,id,true)).thenReturn(new CatalogRemovalService.Review("Product","DELETE","Delete product","Confirm"));var request=new MockHttpServletRequest();request.setServletPath("/app/platform/catalog/products/"+id+"/removal-review");var auth=new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));assertThat(controller.removalReview(id,new MockHttpSession(),auth,request).getStatusCode().value()).isEqualTo(200);verify(service).review(null,id,true);
 }
 @Test void accountOperatorReviewIsBoundToSelectedAccount(){
  var controller=new CatalogController(null,null);var service=mock(CatalogRemovalService.class);var access=mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);ReflectionTestUtils.setField(controller,"removals",service);ReflectionTestUtils.setField(controller,"workspaceAccess",access);UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();when(access.canOperateAccount(tenant,"operator")).thenReturn(true);when(service.review(tenant,id,false)).thenReturn(new CatalogRemovalService.Review("Product","INACTIVE","Mark inactive","Confirm"));var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);var request=new MockHttpServletRequest();request.setServletPath("/app/catalog/products/"+id+"/removal-review");var auth=new UsernamePasswordAuthenticationToken("operator",null,List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));assertThat(controller.removalReview(id,session,auth,request).getStatusCode().value()).isEqualTo(200);verify(service).review(tenant,id,false);
 }
 void checkDenied(String role,boolean global,boolean accountAccess){
  var controller=new CatalogController(null,null);var removals=mock(CatalogRemovalService.class);var access=mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);when(access.canOperateAccount(any(),any())).thenReturn(accountAccess);ReflectionTestUtils.setField(controller,"workspaceAccess",access);ReflectionTestUtils.setField(controller,"removals",removals);
  var session=new MockHttpSession();session.setAttribute("selectedTenantId",UUID.randomUUID());var authentication=new UsernamePasswordAuthenticationToken("viewer",null,List.of(new SimpleGrantedAuthority(role)));var request=new MockHttpServletRequest();request.setServletPath(global?"/app/platform/catalog/products/id/remove":"/app/catalog/products/id/remove");
  assertThatThrownBy(()->controller.removalReview(UUID.randomUUID(),session,authentication,request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThatThrownBy(()->controller.removeProduct(UUID.randomUUID(),"DELETE",session,authentication,request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(removals);
 }
}
