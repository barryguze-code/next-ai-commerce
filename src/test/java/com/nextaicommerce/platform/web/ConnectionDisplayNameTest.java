package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class ConnectionDisplayNameTest {
    @Test void nameEditingReusesStoreAdministrationAndOnlyChangesSelectedStoreLabel(){
        var repository=mock(WorkspaceAccessRepository.class);var guard=mock(ShopifyConnectionAccess.class);
        var controller=new AdministrationController(repository,mock(MarketplaceCredentialService.class),mock(ShopifyConnectionService.class),guard);
        UUID account=UUID.randomUUID(),store=UUID.randomUUID();var auth=new UsernamePasswordAuthenticationToken("owner","unused",List.of());
        var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,account);session.setAttribute(AccountSelectionController.STORE_ID,store);
        assertThat(controller.renameConnection(store," New name ",session,auth,new RedirectAttributesModelMap())).isEqualTo("redirect:/app/connections");
        verify(guard).require(account,store,auth);verify(repository).renameConnection(account,store," New name ");
        assertThat(session.getAttribute(AccountSelectionController.STORE_NAME)).isEqualTo("New name");
        doThrow(new AccessDeniedException("Denied")).when(guard).require(account,store,auth);
        assertThatThrownBy(()->controller.renameConnection(store,"Forbidden",session,auth,new RedirectAttributesModelMap())).isInstanceOf(AccessDeniedException.class);
        verify(repository,never()).renameConnection(account,store,"Forbidden");
    }
}
