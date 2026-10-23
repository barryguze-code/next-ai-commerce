package com.nextaicommerce.platform.sync;

import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmazonVariationTrialTransportTest {
    static final String ENDPOINT="https://sellingpartnerapi-na.amazon.com",PATH="/listings/2021-08-01/items/synthetic/sku";
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
    final com.nextaicommerce.platform.web.MarketplaceCredentialService creds=mock(com.nextaicommerce.platform.web.MarketplaceCredentialService.class);
    final AmazonSpApiClient api=new AmazonSpApiClient(creds,new tools.jackson.databind.ObjectMapper());
    final HttpClient http=mock(HttpClient.class);
    AmazonVariationTrialTransportTest(){org.springframework.test.util.ReflectionTestUtils.setField(api,"http",http);when(creds.credentials(tenant,store)).thenReturn(Map.of("refreshToken","synthetic","lwaClientId","synthetic","lwaClientSecret","synthetic"));}
    @SuppressWarnings("unchecked") HttpResponse<String> response(String body){var r=(HttpResponse<String>)mock(HttpResponse.class);when(r.statusCode()).thenReturn(200);when(r.body()).thenReturn(body);when(r.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));return r;}
    AmazonSpApiClient.VariationPermit permit(boolean oauth,boolean get,Duration remaining){return new AmazonSpApiClient.VariationPermit(){public boolean oauth(){return oauth;}public boolean getAsBoolean(){return get;}public Duration remaining(){return remaining;}};}
    @Test void oauthDeniedBeforeAnyNetwork()throws Exception{
        assertThatThrownBy(()->api.getVariationAt(tenant,store,ENDPOINT,PATH,permit(false,true,Duration.ofSeconds(1)))).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);
        verifyNoInteractions(http);
    }
    @Test void getDeniedAfterTokenDoesNotSendListing()throws Exception{
        var token=response("{\"access_token\":\"synthetic\",\"expires_in\":3600}");when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(token);
        assertThatThrownBy(()->api.getVariationAt(tenant,store,ENDPOINT,PATH,permit(true,false,Duration.ofMillis(700)))).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);
        var captor=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(captor.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(captor.getValue().uri().getPath()).isEqualTo("/auth/o2/token");assertThat(captor.getValue().timeout()).contains(Duration.ofMillis(700));
    }
    @Test void bothRequestsUseRemainingDeadlineAndCachedTokenSpendsNoOAuth()throws Exception{
        var token=response("{\"access_token\":\"synthetic\",\"expires_in\":3600}");var listing=response("{}");when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(token,listing,listing);
        api.getVariationAt(tenant,store,ENDPOINT,PATH,permit(true,true,Duration.ofMillis(700)));
        api.getVariationAt(tenant,store,ENDPOINT,PATH,permit(false,true,Duration.ofMillis(300)));
        var captor=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(3)).send(captor.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(captor.getAllValues().stream().map(r->r.timeout().orElseThrow())).containsExactly(Duration.ofMillis(700),Duration.ofMillis(700),Duration.ofMillis(300));
    }
    @Test void requestTimeoutIsCappedWithoutTruncatingTheTrialWindow()throws Exception{
        var tokenForCap=response("{\"access_token\":\"synthetic\",\"expires_in\":3600}");var listingForCap=response("{}");when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(tokenForCap,listingForCap);
        var permit=spy(permit(true,true,Duration.ofMinutes(4)));api.getVariationAt(tenant,store,ENDPOINT,PATH,permit);
        var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(2)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().stream().map(r->r.timeout().orElseThrow())).containsOnly(Duration.ofSeconds(30));verify(permit,times(2)).completed();
    }
    @Test void failedDispatchReleasesSlotWithoutRefundingReservation()throws Exception{
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("synthetic transport failure"));
        var permit=spy(permit(true,true,Duration.ofSeconds(5)));assertThatThrownBy(()->api.getVariationAt(tenant,store,ENDPOINT,PATH,permit)).isInstanceOf(AmazonSpApiClient.AmazonTransportException.class);
        verify(permit).oauth();verify(permit).completed();verify(permit,never()).getAsBoolean();
    }

}
