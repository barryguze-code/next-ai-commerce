package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.shipping.CarrierConnectionService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CarrierConnectionsAccessTest {
    final CarrierConnectionService service=mock(CarrierConnectionService.class);
    final WorkspaceAccessRepository access=mock(WorkspaceAccessRepository.class);
    final MemberAccessService members=mock(MemberAccessService.class);
    final CarrierConnectionsController controller=new CarrierConnectionsController(service,access,members);
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
    final String email="fixture@example.invalid";
    MockHttpSession session(){var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.STORE_ID,store);return session;}
    UsernamePasswordAuthenticationToken actor(){return new UsernamePasswordAuthenticationToken(email,"",List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));}
    @Test void staleAdminOrRevokedAccountCannotReadOrMutateCarriers(){
        assertThatThrownBy(()->controller.list(session(),actor())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(access.canOperateAccount(tenant,email)).thenReturn(true);when(members.current(tenant,email)).thenReturn(new MemberAccessService.Access("OPERATOR",false,Set.of()));
        assertThatThrownBy(()->controller.health(UUID.randomUUID(),session(),actor())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test void currentAccountAdminAndActivePlatformAdminCanReadSafeProjection(){
        when(access.canOperateAccount(tenant,email)).thenReturn(true);when(members.current(tenant,email)).thenReturn(new MemberAccessService.Access("OWNER",false,Set.of()));
        controller.list(session(),actor());verify(service).views(tenant);
        when(access.isCurrentPlatformAdmin(email)).thenReturn(true);controller.list(session(),actor());verify(service,times(2)).views(tenant);
    }
    @Test void restrictedAdminRequiresAnAssignedStoreSelection(){
        when(access.canOperateAccount(tenant,email)).thenReturn(true);when(members.current(tenant,email)).thenReturn(new MemberAccessService.Access("ADMIN",true,Set.of(store)));
        var missing=session();missing.removeAttribute(AccountSelectionController.STORE_ID);
        assertThatThrownBy(()->controller.list(missing,actor())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var malformed=session();malformed.setAttribute(AccountSelectionController.STORE_ID,"not-a-store");
        assertThatThrownBy(()->controller.list(malformed,actor())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(service);
    }
    @Test void restrictedAdminCannotReuseRevokedStoreSelection(){
        when(access.canOperateAccount(tenant,email)).thenReturn(true);when(members.current(tenant,email)).thenReturn(new MemberAccessService.Access("ADMIN",true,Set.of(UUID.randomUUID())));
        assertThatThrownBy(()->controller.delete(UUID.randomUUID(),session(),actor())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(service);
    }
}
