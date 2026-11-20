package com.nextaicommerce.platform.web;

import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopifySetupStateTest {
    final UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    final WorkspaceAccessRepository repository=mock(WorkspaceAccessRepository.class);
    final MarketplaceCredentialService credentials=mock(MarketplaceCredentialService.class);
    final HttpClient http=mock(HttpClient.class);
    final ShopifyConnectionService service=new ShopifyConnectionService(repository,credentials,true,http,Clock.fixed(now,ZoneOffset.UTC));
    Map<String,String> saved(){return new HashMap<>(Map.of("shopDomain","unit.myshopify.com","shopId","gid://shopify/Shop/123","verifiedAt",now.minusSeconds(60).toString(),"expiresAt",now.plusSeconds(600).toString(),"scopes",""));}
    void state(String status,Map<String,String> saved){when(repository.shopifyConnection(tenant,connection)).thenReturn(new WorkspaceAccessRepository.ShopifyConnection("unit.myshopify.com",status,now));when(credentials.credentials(tenant,connection)).thenReturn(saved);}
    @Test void verifiedConnectionDoesNotImplyProductPermissionOrStartAnyRequest(){
        state("ACTIVE",saved());assertEquals(new ShopifyConnectionService.SetupState(true,false),service.setupState(tenant,connection));verifyNoInteractions(http);
        verify(repository,never()).publishShopifyCredentials(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void explicitScopeIsExactAndNeverExposesCredentials(){
        var saved=saved();saved.put("scopes","read_products_extra, read_inventory");state("ACTIVE",saved);assertFalse(service.setupState(tenant,connection).productsPermitted());
        saved.put("scopes","read_inventory,read_products");assertEquals(new ShopifyConnectionService.SetupState(true,true),service.setupState(tenant,connection));verifyNoInteractions(http);
    }
    @Test void expiredMalformedOrForeignEvidenceIsNotVerified(){
        var saved=saved();saved.put("expiresAt",now.toString());state("ACTIVE",saved);assertFalse(service.setupState(tenant,connection).verified());
        saved.put("expiresAt","bad");assertFalse(service.setupState(tenant,connection).verified());
        saved=saved();saved.put("shopDomain","foreign.myshopify.com");state("ACTIVE",saved);assertFalse(service.setupState(tenant,connection).verified());
        state("DISABLED",saved());assertFalse(service.setupState(tenant,connection).verified());verifyNoInteractions(http);
    }
    @Test void MissingOrUnreadableEvidenceCannotAppearVerified(){
        state("ACTIVE",Map.of());assertFalse(service.setupState(tenant,connection).verified());
        when(credentials.credentials(tenant,connection)).thenThrow(new IllegalStateException("private"));assertFalse(service.setupState(tenant,connection).verified());verifyNoInteractions(http);
    }
}
