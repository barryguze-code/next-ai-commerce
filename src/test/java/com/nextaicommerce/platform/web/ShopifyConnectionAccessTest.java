package com.nextaicommerce.platform.web;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ShopifyConnectionAccessTest {
 @Test void rechecksSelectedTenantRoleAndExactAssignedStore(){
  var members=mock(MemberAccessService.class);var guard=new ShopifyConnectionAccess(members,mock(WorkspaceAccessRepository.class));UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
  var actor=new UsernamePasswordAuthenticationToken("unit-admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  when(members.current(tenant,"unit-admin")).thenReturn(new MemberAccessService.Access("ADMIN",true,Set.of(store)));
  assertDoesNotThrow(()->guard.require(tenant,store,actor));assertThrows(AccessDeniedException.class,()->guard.require(tenant,UUID.randomUUID(),actor));assertThrows(AccessDeniedException.class,()->guard.require(tenant,null,actor));
  assertThrows(AccessDeniedException.class,()->guard.require(UUID.randomUUID(),store,actor));
  when(members.current(tenant,"unit-admin")).thenReturn(new MemberAccessService.Access("VIEWER",false,Set.of(store)));assertThrows(AccessDeniedException.class,()->guard.require(tenant,store,actor));
  when(members.current(tenant,"unit-admin")).thenReturn(new MemberAccessService.Access("OWNER",false,Set.of()));assertDoesNotThrow(()->guard.require(tenant,null,actor));
 }
 @Test void stalePlatformAdminAuthorityIsNotEnough(){
  var members=mock(MemberAccessService.class);var repository=mock(WorkspaceAccessRepository.class);var guard=new ShopifyConnectionAccess(members,repository);
  var actor=new UsernamePasswordAuthenticationToken("unit-platform",null,List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
  UUID tenant=UUID.randomUUID();assertThrows(AccessDeniedException.class,()->guard.require(tenant,null,actor));
  when(repository.isCurrentPlatformAdmin("unit-platform")).thenReturn(true);assertDoesNotThrow(()->guard.require(tenant,null,actor));
 }

}
