package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.*;
import com.nextaicommerce.platform.collaboration.CollaborationRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShopifyOrderPageAccessTest {
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
    final ShopifyOrderReadRepository reads=mock(ShopifyOrderReadRepository.class);final ShopifyOrderJobs jobs=mock(ShopifyOrderJobs.class);final ShopifyOrderWorker worker=mock(ShopifyOrderWorker.class);
    final ShopifyConnectionService connections=mock(ShopifyConnectionService.class);final WorkspaceAccessRepository access=mock(WorkspaceAccessRepository.class);final CollaborationRepository collaboration=mock(CollaborationRepository.class);
    final ShopifyOrderPage page=new ShopifyOrderPage(reads,jobs,worker,connections,access,collaboration);
    final org.springframework.security.core.Authentication actor=UsernamePasswordAuthenticationToken.authenticated("viewer@example.invalid","unused",List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
    @Test void revokedStoreCannotReadOrdersCredentialsOrCollaboration(){
        when(access.listAccountOptions(actor.getName(),false)).thenReturn(List.of());
        assertThatThrownBy(()->page.show(tenant,store,actor,"ALL","",0,25,new ExtendedModelMap())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(reads,connections,collaboration,jobs,worker);
    }
    @Test void streamVersionRejectsStaleStoreBeforeJobRead(){
        assertThatThrownBy(()->page.version(tenant,store,actor)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));verifyNoInteractions(jobs,connections,reads,collaboration);
    }
    @Test void authorizedViewerCanReadButNeverGetsSyncOrMappingWriteControls(){
        var connection=new WorkspaceAccessRepository.ConnectionView(store,tenant,"Fixture","Fixture Shopify","SHOPIFY","fixture.myshopify.com","fixture.myshopify.com","Shopify","","ACTIVE",null,true);
        when(access.listAccountOptions(actor.getName(),false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(tenant,"Fixture","F",null,List.of(connection))));
        when(jobs.enabled()).thenReturn(true);when(reads.page(tenant,store,"ALL","",0,25)).thenReturn(new ShopifyOrderReadRepository.Page(List.of(),0,0,25));
        var model=new ExtendedModelMap();model.addAttribute("canEditCatalog",true);assertThat(page.show(tenant,store,actor,"ALL","",0,25,model)).isEqualTo("orders");
        assertThat(model.get("canSyncShopifyOrders")).isEqualTo(false);assertThat(model.get("canEditCatalog")).isEqualTo(false);verify(reads).page(tenant,store,"ALL","",0,25);verifyNoInteractions(worker);
    }
    @Test void orderAndSyncTimesUsePacificWithDaylightSavingAndDateRollover(){
        var connection=new WorkspaceAccessRepository.ConnectionView(store,tenant,"Fixture","Fixture Shopify","SHOPIFY","fixture.myshopify.com","fixture.myshopify.com","Shopify","","ACTIVE",null,true);
        when(access.listAccountOptions(actor.getName(),false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(tenant,"Fixture","F",null,List.of(connection))));
        when(jobs.enabled()).thenReturn(true);
        when(reads.page(tenant,store,"ALL","",0,25)).thenReturn(new ShopifyOrderReadRepository.Page(List.of(),0,0,25));
        var model=new ExtendedModelMap();page.show(tenant,store,actor,"ALL","",0,25,model);
        var formatter=(java.time.format.DateTimeFormatter)model.get("orderTime");
        assertThat(formatter.format(java.time.Instant.parse("2026-10-30T20:24:00Z"))).isEqualTo("10/30/26 · 1:24 PM PDT");
        assertThat(formatter.format(java.time.Instant.parse("2026-12-01T20:24:00Z"))).isEqualTo("12/01/26 · 12:24 PM PST");
        assertThat(formatter.format(java.time.Instant.parse("2026-10-30T02:00:00Z"))).isEqualTo("10/29/26 · 7:00 PM PDT");
        verifyNoInteractions(worker);
    }

}
