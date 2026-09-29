package com.nextaicommerce.platform.marketplace;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import com.nextaicommerce.platform.web.*;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.sync.AmazonManualListingSync;

class MarketplaceSkuSyncTest {
    @Test void unionedRolesCannotSyncAnAccountWithoutItsOwnPermission() {
        var workspace=mock(WorkspaceAccessRepository.class);
        var sync=mock(AmazonManualListingSync.class);
        var controller=new MarketplaceSkuController(mock(MarketplaceSkuRepository.class),workspace,mock(CatalogRepository.class));
        controller.configureListingSync(sync);
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();
        var session=new MockHttpSession();
        session.setAttribute(AccountSelectionController.TENANT_ID,tenant);
        session.setAttribute(AccountSelectionController.STORE_ID,connection);
        when(workspace.findConnection(tenant,connection)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(connection,"AMAZON","ATVPDKIKX0DER"));
        var auth=new UsernamePasswordAuthenticationToken("operator@example.test","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThatThrownBy(()->controller.syncListings(auth,session,new RedirectAttributesModelMap()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(sync);
        when(workspace.canOperateAccount(tenant,auth.getName())).thenReturn(true);
        when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(List.of());
        assertThatThrownBy(()->controller.syncListings(auth,session,new RedirectAttributesModelMap()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(sync);
    }
    @Test void authorizedStoreMemberCanRequestImport() {
        var workspace=mock(WorkspaceAccessRepository.class);var sync=mock(AmazonManualListingSync.class);
        var controller=new MarketplaceSkuController(mock(MarketplaceSkuRepository.class),workspace,mock(CatalogRepository.class));
        controller.configureListingSync(sync);
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var session=new MockHttpSession();
        session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.STORE_ID,connection);
        var auth=new UsernamePasswordAuthenticationToken("operator@example.test","",List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        when(workspace.findConnection(tenant,connection)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(connection,"AMAZON"));
        when(workspace.canOperateAccount(tenant,auth.getName())).thenReturn(true);
        var store=new WorkspaceAccessRepository.ConnectionView(connection,tenant,"Account","Store","AMAZON","seller","ATVPDKIKX0DER","US","US","ACTIVE",null,true);
        when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(tenant,"Account","A",null,List.of(store))));
        assertThat(controller.syncListings(auth,session,new RedirectAttributesModelMap())).isEqualTo("redirect:/app/marketplace-skus");
        verify(sync).request(tenant,connection);
    }
}
