package com.nextaicommerce.platform.web;

import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShopifyOrderTransportTest {
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();final Instant now=Instant.parse("2026-10-27T10:00:00Z");
    final WorkspaceAccessRepository repository=mock(WorkspaceAccessRepository.class);final MarketplaceCredentialService crypto=mock(MarketplaceCredentialService.class);final HttpClient http=mock(HttpClient.class);
    final ShopifyConnectionService service=new ShopifyConnectionService(repository,crypto,true,http,Clock.fixed(now,ZoneOffset.UTC));
    String query(){return new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query",com.nextaicommerce.platform.shopify.ShopifyOrderClient.QUERY,"variables",Map.of()));}
    Map<String,String> saved(String scopes){return Map.of("accessToken","fixture-token","shopDomain","fixture.myshopify.com","shopId","gid://shopify/Shop/1","currency","USD","expiresAt",now.plusSeconds(86399).toString(),"verifiedAt",now.minusSeconds(1).toString(),"scopes",scopes);}
    void prepare(String scopes){ReflectionTestUtils.setField(service,"ordersEnabled",true);when(repository.shopifyConnection(tenant,store)).thenReturn(new WorkspaceAccessRepository.ShopifyConnection("fixture.myshopify.com","ACTIVE",now));when(crypto.credentials(tenant,store)).thenReturn(saved(scopes));}
    @Test void ordersAreOffByDefaultBeforeCredentialsOrHttp(){assertThrows(IllegalStateException.class,()->service.readOrders(tenant,store,"{}",()->{}));verifyNoInteractions(repository,crypto,http);}
    @Test void missingMerchantScopeAndBroaderFulfillmentGrantsCannotAssertOwnership(){
        for(String scopes:List.of("read_products,read_orders","read_products,read_merchant_managed_fulfillment_orders","read_products,read_orders,read_merchant_managed_fulfillment_orders,read_third_party_fulfillment_orders")){
            prepare(scopes);Runnable budget=mock(Runnable.class);assertThrows(ShopifyConnectionService.AuthorizationRejected.class,()->service.readOrders(tenant,store,query(),budget));verifyNoInteractions(http,budget);
        }
    }
    @Test @SuppressWarnings("unchecked") void onlyApprovedExactHostReadUsesExistingTokenAndReservesBudget()throws Exception{
        prepare("read_products,read_orders,read_merchant_managed_fulfillment_orders");
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{}");when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);Runnable budget=mock(Runnable.class);
        assertEquals("{}",service.readOrders(tenant,store,query(),budget).body());verify(budget).run();
        verify(http).send(argThat(r->r.uri().toString().equals("https://fixture.myshopify.com/admin/api/2026-10/graphql.json")&&r.method().equals("POST")),any(HttpResponse.BodyHandler.class));
    }
    @Test void renewalKeepsFreshGrantAndChecksActorWithoutNetwork(){
        prepare("read_products,read_orders,read_merchant_managed_fulfillment_orders");Runnable actor=mock(Runnable.class);service.renewOrdersAuthorization(tenant,store,actor);verify(actor).run();verifyNoInteractions(http);
    }
    @Test @SuppressWarnings("unchecked") void expiredRecipientGrantRenewsWithoutOrderSyncOrPermissionExpansion()throws Exception{
        prepare("read_products,read_orders,read_merchant_managed_fulfillment_orders");
        var credentials=new java.util.concurrent.atomic.AtomicReference<Map<String,String>>();
        var expired=new HashMap<>(saved("read_products,read_orders,read_merchant_managed_fulfillment_orders"));
        expired.put("expiresAt",now.minusSeconds(1).toString());expired.put("clientId","fixture-id");expired.put("clientSecret","fixture-secret");credentials.set(expired);
        when(crypto.credentials(tenant,store)).thenAnswer(a->credentials.get());
        when(crypto.encrypt(anyMap())).thenAnswer(a->{credentials.set(Map.copyOf(a.getArgument(0)));return new MarketplaceCredentialService.EncryptedCredentials(new byte[]{1},new byte[]{2});});
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(a->{
            HttpRequest request=a.getArgument(0);HttpResponse<String> response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(x,y)->true));
            when(response.body()).thenReturn(request.uri().getPath().endsWith("access_token")?"{\"access_token\":\"renewed-fixture\",\"expires_in\":86400,\"scope\":\"read_products,read_orders,read_merchant_managed_fulfillment_orders\"}":credentials.get().get("accessToken").equals("fixture-token")?"{\"data\":{\"shop\":{\"id\":\"gid://shopify/Shop/1\",\"myshopifyDomain\":\"fixture.myshopify.com\",\"ianaTimezone\":\"UTC\",\"currencyCode\":\"USD\"}}}":"{\"data\":{\"order\":null}}");return response;});
        var body=new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query",com.nextaicommerce.platform.shopify.ShopifyOrderClient.RECIPIENT_QUERY,"variables",Map.of("id","gid://shopify/Order/1")));
        assertEquals("{\"data\":{\"order\":null}}",service.readOrderRecipient(tenant,store,body).body());
        verify(http,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        verify(repository).publishShopifyCredentials(eq(tenant),eq(store),eq(now),any(),any(),eq("UTC"),any());
        assertEquals("read_products,read_orders,read_merchant_managed_fulfillment_orders",credentials.get().get("scopes"));
    }
    @Test void mutationsAndOtherQueriesAreRejectedBeforeCredentialsOrHttp(){
        ReflectionTestUtils.setField(service,"ordersEnabled",true);
        for(String operation:List.of("mutation { inventoryAdjustQuantities(input:{}){userErrors{message}} }","mutation { productVariantsBulkUpdate(productId:\"x\",variants:[]){userErrors{message}} }","query { customers(first:1){nodes{id}} }"))
            assertThrows(IllegalArgumentException.class,()->service.readOrders(tenant,store,new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query",operation)),()->{}));
        verifyNoInteractions(repository,crypto,http);
    }
    @Test void inventoryWritesDefaultOffAndLocalAlwaysDenied(){
        String body=new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query",com.nextaicommerce.platform.shopify.ShopifyInventoryClient.WRITE));
        assertThrows(IllegalStateException.class,()->service.inventory(tenant,store,"gid://shopify/Shop/1",body,()->{},true));
        ReflectionTestUtils.setField(service,"inventoryWrites",true);ReflectionTestUtils.setField(service,"localDevelopment",true);
        assertThrows(IllegalStateException.class,()->service.inventory(tenant,store,"gid://shopify/Shop/1",body,()->{},true));
        verifyNoInteractions(repository,crypto,http);
    }
    @Test void inventoryRejectsWrongShopAndMissingScopeBeforeNetwork(){
        prepare("read_products,read_locations");ReflectionTestUtils.setField(service,"inventoryWrites",true);
        String body=new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query",com.nextaicommerce.platform.shopify.ShopifyInventoryClient.WRITE));
        assertThrows(ShopifyConnectionService.AuthorizationRejected.class,()->service.inventory(tenant,store,"gid://shopify/Shop/1",body,()->{},true));
        prepare("read_products,read_locations,write_inventory");
        assertThrows(ShopifyConnectionService.AuthorizationRejected.class,()->service.inventory(tenant,store,"gid://shopify/Shop/999",body,()->{},true));
        verifyNoInteractions(http);
    }
}
