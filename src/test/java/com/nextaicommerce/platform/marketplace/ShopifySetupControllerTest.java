package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import com.nextaicommerce.platform.web.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopifySetupControllerTest {
    @Test void shopifySetupDoesNotLoadAmazonStockSalesOrMapping(){
        var workspace=mock(WorkspaceAccessRepository.class);var skus=mock(MarketplaceSkuRepository.class);var catalog=mock(CatalogRepository.class);var shopify=mock(ShopifyConnectionService.class);
        var controller=new MarketplaceSkuController(skus,workspace,catalog);ReflectionTestUtils.setField(controller,"shopify",shopify);
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var session=new MockHttpSession();
        session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.TENANT_NAME,"Unit account");session.setAttribute(AccountSelectionController.STORE_ID,connection);
        when(workspace.findConnection(tenant,connection)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(connection,"SHOPIFY"));
        var actor=new UsernamePasswordAuthenticationToken("viewer@example.test","",List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
        var store=new WorkspaceAccessRepository.ConnectionView(connection,tenant,"Unit account","Unit store","SHOPIFY","unit.myshopify.com","unit.myshopify.com","","Shopify","ACTIVE",null,true);
        when(workspace.listAccountOptions(actor.getName(),false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(tenant,"Unit account","U",null,List.of(store))));
        var setup=new ShopifyConnectionService.SetupState(true,false);when(shopify.setupState(tenant,connection)).thenReturn(setup);
        var model=new ExtendedModelMap();assertEquals("marketplace-skus",controller.marketplaceSkus("","ALL","status","asc",0,null,25,Map.of(),actor,session,model));
        assertSame(setup,model.get("shopifySetup"));assertNull(model.get("unsupportedChannel"));verifyNoInteractions(skus,catalog);
    }
    @Test void revokedStoreInOldSessionCannotReadCredentialProjection(){
        var workspace=mock(WorkspaceAccessRepository.class);var shopify=mock(ShopifyConnectionService.class);
        var controller=new MarketplaceSkuController(mock(MarketplaceSkuRepository.class),workspace,mock(CatalogRepository.class));ReflectionTestUtils.setField(controller,"shopify",shopify);
        var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.TENANT_NAME,"Old account");session.setAttribute(AccountSelectionController.STORE_ID,connection);
        var actor=new UsernamePasswordAuthenticationToken("viewer@example.test","",List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
        when(workspace.findConnection(tenant,connection)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(connection,"SHOPIFY"));
        when(workspace.listAccountOptions(actor.getName(),false)).thenReturn(List.of());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.marketplaceSkus("","ALL","status","asc",0,null,25,Map.of(),actor,session,new ExtendedModelMap()));verifyNoInteractions(shopify);
    }
}
