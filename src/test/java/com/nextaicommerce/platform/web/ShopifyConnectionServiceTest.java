package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.sync.AmazonInitializationService;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShopifyConnectionServiceTest {
    final UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    final WorkspaceAccessRepository repository=mock(WorkspaceAccessRepository.class);
    final AmazonInitializationService initialization=mock(AmazonInitializationService.class);
    final HttpClient http=mock(HttpClient.class);
    final MarketplaceCredentialService crypto=spy(new MarketplaceCredentialService(repository,initialization,Base64.getEncoder().encodeToString(new byte[32]),http));
    final ShopifyConnectionService service=new ShopifyConnectionService(repository,crypto,true,http,Clock.fixed(now,ZoneOffset.UTC));
    final String token="{\"access_token\":\"unit-access\",\"expires_in\":86399,\"scope\":\"\"}";
    final String identity="{\"data\":{\"shop\":{\"id\":\"gid://shopify/Shop/123\",\"name\":\"Unit Shop\",\"myshopifyDomain\":\"unit-shop.myshopify.com\",\"ianaTimezone\":\"Europe/London\",\"currencyCode\":\"GBP\"}}}";
    @BeforeEach void setup(){when(repository.shopifyConnection(tenant,id)).thenReturn(new WorkspaceAccessRepository.ShopifyConnection("unit-shop.myshopify.com","PENDING",now.minusSeconds(60)));}
    @SuppressWarnings("unchecked") HttpResponse<String> response(int status,String body){var r=mock(HttpResponse.class);when(r.statusCode()).thenReturn(status);when(r.body()).thenReturn(body);when(r.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("120")),(a,b)->true));return r;}
    @SuppressWarnings("unchecked") void responses(HttpResponse<String>...r)throws Exception{when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(r[0],Arrays.copyOfRange(r,1,r.length));}
    @Test void verifiesExactStoreBeforeEncryptedAtomicPublication()throws Exception{
        responses(response(200,token),response(200,identity));service.connect(tenant,id,"unit-app","unit-secret",()->{});
        var payload=org.mockito.ArgumentCaptor.forClass(byte[].class);var nonce=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(repository).publishShopifyCredentials(eq(tenant),eq(id),eq(now.minusSeconds(60)),payload.capture(),nonce.capture(),eq("Europe/London"),any(Runnable.class));
        assertFalse(new String(payload.getValue(),java.nio.charset.StandardCharsets.UTF_8).contains("unit-secret"));
        when(repository.loadCredentials(tenant,id)).thenReturn(new WorkspaceAccessRepository.CredentialEnvelope(payload.getValue(),nonce.getValue()));
        var stored=crypto.credentials(tenant,id);assertEquals("unit-shop.myshopify.com",stored.get("shopDomain"));assertEquals("unit-secret",stored.get("clientSecret"));assertEquals("GBP",stored.get("currency"));assertEquals(now.plusSeconds(86399).toString(),stored.get("expiresAt"));
        verifyNoInteractions(initialization);
        verify(http).send(argThat(q->q.uri().getHost().equals("unit-shop.myshopify.com")&&q.uri().getPath().equals("/admin/api/2026-10/graphql.json")),any(HttpResponse.BodyHandler.class));
    }
    @Test void emptyStorageCannotVerifyAndDoesNotCallShopify(){assertThrows(IllegalStateException.class,()->service.verify(tenant,id,()->{}));verifyNoInteractions(http);}
    @Test void disabledCapabilityCannotCallShopify(){var disabled=new ShopifyConnectionService(repository,crypto,false,http,Clock.systemUTC());assertThrows(IllegalStateException.class,()->disabled.connect(tenant,id,"a","b",()->{}));verifyNoInteractions(http,repository);}
    @Test void foreignTenantOrConnectionCannotUseCredential()throws Exception{UUID other=UUID.randomUUID();when(repository.shopifyConnection(other,id)).thenThrow(new IllegalArgumentException("Not found"));assertThrows(IllegalArgumentException.class,()->service.connect(other,id,"a","b",()->{}));verifyNoInteractions(http);verify(repository,never()).publishShopifyCredentials(any(),any(),any(),any(),any(),any(),any());}
    @Test void disconnectedStoreCannotReconnectViaOldRequest(){when(repository.shopifyConnection(tenant,id)).thenReturn(new WorkspaceAccessRepository.ShopifyConnection("unit-shop.myshopify.com","DISABLED",now));assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));verifyNoInteractions(http);}
    @Test void rejectsShopIdentityMismatchWithoutPublishing()throws Exception{responses(response(200,token),response(200,identity.replace("unit-shop.myshopify.com","another.myshopify.com")));assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));neverPublish();}
    @Test void malformedTokenAndGraphqlErrorsCannotPublish()throws Exception{responses(response(200,"not-json"));assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));neverPublish();}
    @Test void graphqlErrorsPreservePriorAuthorization()throws Exception{responses(response(200,token),response(200,"{\"errors\":[{\"message\":\"private\"}]}"));var ex=assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));assertFalse(ex.getMessage().contains("private"));neverPublish();}
    @Test void authorizationFailureShowsSameOrganizationGuidance()throws Exception{responses(response(403,"sensitive-provider-body"));var ex=assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));assertTrue(ex.getMessage().contains("organization"));assertFalse(ex.getMessage().contains("sensitive-provider-body"));neverPublish();}
    @Test void retryAfterPreventsImmediateRetryWithoutPublishing()throws Exception{responses(response(429,"{}"));assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));assertThrows(IllegalStateException.class,()->service.connect(tenant,id,"a","b",()->{}));verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));neverPublish();}
    @Test void freshSavedTokenIsReusedForExplicitVerification()throws Exception{doReturn(Map.of("shopDomain","unit-shop.myshopify.com","clientId","a","clientSecret","b","accessToken","cached","expiresAt",now.plusSeconds(500).toString())).when(crypto).credentials(tenant,id);responses(response(200,identity));service.verify(tenant,id,()->{});verify(http,times(1)).send(argThat(q->q.uri().getPath().contains("graphql.json")&&q.headers().firstValue("X-Shopify-Access-Token").orElse("").equals("cached")),any(HttpResponse.BodyHandler.class));}
    @Test void expiredSavedTokenIsRenewedBeforeIdentityVerification()throws Exception{doReturn(Map.of("shopDomain","unit-shop.myshopify.com","clientId","a","clientSecret","b","accessToken","old","expiresAt",now.minusSeconds(1).toString())).when(crypto).credentials(tenant,id);responses(response(200,token),response(200,identity));service.verify(tenant,id,()->{});verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));}
    @Test void rejectedSavedTokenMarksReconnectWithoutReplacingEnvelope()throws Exception{
        doReturn(Map.of("shopDomain","unit-shop.myshopify.com","clientId","a","clientSecret","b","accessToken","cached","expiresAt",now.plusSeconds(500).toString())).when(crypto).credentials(tenant,id);
        responses(response(401,"secret-error"));assertThrows(IllegalArgumentException.class,()->service.verify(tenant,id,()->{}));
        verify(repository).markShopifyVerificationRequired(tenant,id,now.minusSeconds(60));neverPublish();
    }
    @Test void changedImmutableShopIdentityIsNotReassigned()throws Exception{
        when(repository.loadCredentials(tenant,id)).thenReturn(new WorkspaceAccessRepository.CredentialEnvelope(new byte[0],new byte[0]));
        doReturn(Map.of("shopId","gid://shopify/Shop/999")).when(crypto).credentials(tenant,id);
        responses(response(200,token),response(200,identity));assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));neverPublish();
    }
    @Test void perConnectionVerificationCannotPublishInParallel()throws Exception{
        var started=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            HttpRequest request=call.getArgument(0);if(request.uri().getPath().endsWith("access_token")){started.countDown();assertTrue(finish.await(5,java.util.concurrent.TimeUnit.SECONDS));return response(200,token);}return response(200,identity);
        });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var first=executor.submit(()->service.connect(tenant,id,"a","b",()->{}));assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            try{assertThrows(IllegalStateException.class,()->service.connect(tenant,id,"a","b",()->{}));verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));}
            finally{finish.countDown();}
            first.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(repository,times(1)).publishShopifyCredentials(eq(tenant),eq(id),any(),any(),any(),any(),any());
    }
    @Test void missingCachedTokenRenewsInsteadOfSendingFormToGraphql()throws Exception{
        doReturn(Map.of("shopDomain","unit-shop.myshopify.com","clientId","a","clientSecret","b","expiresAt",now.plusSeconds(500).toString())).when(crypto).credentials(tenant,id);
        responses(response(200,token),response(200,identity));service.verify(tenant,id,()->{});verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void graphqlThrottleHonorsCooldownOnHttp200()throws Exception{
        responses(response(200,token),response(200,"{\"errors\":[{\"extensions\":{\"code\":\"THROTTLED\"}}],\"extensions\":{\"cost\":{\"requestedQueryCost\":10,\"throttleStatus\":{\"currentlyAvailable\":0,\"restoreRate\":2}}}}"));
        assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));assertThrows(IllegalStateException.class,()->service.connect(tenant,id,"a","b",()->{}));verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));neverPublish();
    }
    @Test void tokenExpiryMustBeFiniteAndPositive()throws Exception{
        responses(response(200,token.replace("86399","0")));assertThrows(IllegalArgumentException.class,()->service.connect(tenant,id,"a","b",()->{}));neverPublish();
    }
    @Test void publicationCarriesFreshAuthorizationCheck()throws Exception{
        responses(response(200,token),response(200,identity));var authorize=mock(Runnable.class);service.connect(tenant,id,"a","b",authorize);
        verify(authorize).run();verify(repository).publishShopifyCredentials(eq(tenant),eq(id),any(),any(),any(),any(),same(authorize));
    }
    @Test void strictDomainRejectsCustomUrlsPortsCredentialsPathsAndOversizedLabels(){for(String bad:List.of("evil.com","shop.myshopify.com.evil.com","https://unit-shop.myshopify.com","shop.myshopify.com:443","a@shop.myshopify.com","shop.myshopify.com/x","-bad.myshopify.com","a".repeat(64)+".myshopify.com"))assertThrows(IllegalArgumentException.class,()->ShopifyConnectionService.shopDomain(bad),bad);assertEquals("unit-shop.myshopify.com",ShopifyConnectionService.shopDomain(" Unit-Shop.MYSHOPIFY.com "));}
    void catalogSaved(Map<String,String> changes){var values=new HashMap<>(Map.of("shopDomain","unit-shop.myshopify.com","shopId","gid://shopify/Shop/123","currency","GBP","accessToken","cached","expiresAt",now.plusSeconds(500).toString(),"scopes","read_products"));values.putAll(changes);doReturn(values).when(crypto).credentials(tenant,id);when(repository.shopifyConnection(tenant,id)).thenReturn(new WorkspaceAccessRepository.ShopifyConnection("unit-shop.myshopify.com","ACTIVE",now));}
    @Test void catalogueReadRejectsMissingTokenScopeOrExpiryBeforeHttp(){for(var change:List.of(Map.of("accessToken",""),Map.of("scopes",""),Map.of("expiresAt",now.toString()))){catalogSaved(change);var budget=mock(Runnable.class);assertThrows(IllegalArgumentException.class,()->service.readCatalog(tenant,id,"{}",budget));verifyNoInteractions(budget);}verifyNoInteractions(http);}
    @Test void catalogueReadReservesBeforeNetworkAndHonorsRetryAfter()throws Exception{catalogSaved(Map.of());responses(response(429,"{}"));var budget=mock(Runnable.class);assertThrows(ShopifyConnectionService.RateLimited.class,()->service.readCatalog(tenant,id,"{}",budget));assertThrows(ShopifyConnectionService.RateLimited.class,()->service.readCatalog(tenant,id,"{}",budget));verify(budget,times(1)).run();verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));neverPublish();}
    @Test void failedBudgetFencePreventsCatalogueHttp(){catalogSaved(Map.of());assertThrows(IllegalStateException.class,()->service.readCatalog(tenant,id,"{}",()->{throw new IllegalStateException("lease lost");}));verifyNoInteractions(http);}
    void neverPublish(){verify(repository,never()).publishShopifyCredentials(any(),any(),any(),any(),any(),any(),any());verifyNoInteractions(initialization);}
}
