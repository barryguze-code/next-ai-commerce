package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;

class AmazonProductFeeTransportTest {
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
    final com.nextaicommerce.platform.web.MarketplaceCredentialService credentials=mock(com.nextaicommerce.platform.web.MarketplaceCredentialService.class);
    final HttpClient http=mock(HttpClient.class);
    final AmazonSpApiClient api=new AmazonSpApiClient(credentials,new tools.jackson.databind.ObjectMapper());
    AmazonProductFeeTransportTest(){org.springframework.test.util.ReflectionTestUtils.setField(api,"http",http);when(credentials.credentials(tenant,store)).thenReturn(Map.of("refreshToken","synthetic","lwaClientId","synthetic","lwaClientSecret","synthetic"));}
    @SuppressWarnings("unchecked") HttpResponse<String> response(int status,String body,Map<String,List<String>> headers){var r=(HttpResponse<String>)mock(HttpResponse.class);when(r.statusCode()).thenReturn(status);when(r.body()).thenReturn(body);when(r.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));return r;}
    @SafeVarargs final void respond(HttpResponse<String>... responses)throws Exception{when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(responses[0],Arrays.copyOfRange(responses,1,responses.length));}
    AmazonSpApiClient.FeePermit permit(boolean oauth,boolean fee){return new AmazonSpApiClient.FeePermit(){public boolean oauth(){return oauth;}public boolean getAsBoolean(){return fee;}public Duration remaining(){return Duration.ofSeconds(5);}};}
    String request(String market){return "{\"FeesEstimateRequest\":{\"MarketplaceId\":\""+market+"\"}}";}
    @Test void noNetworkWhenOauthIsDenied(){assertThatThrownBy(()->api.quoteProductFee(tenant,store,"0001",request("ATVPDKIKX0DER"),permit(false,true))).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);verifyNoInteractions(http);}
    @Test void noFeeDispatchWhenItsBudgetIsDeniedAfterOauth()throws Exception{respond(response(200,"{\"access_token\":\"synthetic\",\"expires_in\":3600}",Map.of()));assertThatThrownBy(()->api.quoteProductFee(tenant,store,"0001",request("ATVPDKIKX0DER"),permit(true,false))).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);verify(http,times(1)).send(any(),any());}
    @Test void cachedOauthAvoidsAnotherReservationAndSkuIsEncodedExactly()throws Exception{
        respond(response(200,"{\"access_token\":\"synthetic\",\"expires_in\":3600}",Map.of()),response(200,"{}",Map.of()),response(200,"{}",Map.of()));
        api.quoteProductFee(tenant,store,"0001 / +",request("ATVPDKIKX0DER"),permit(true,true));api.quoteProductFee(tenant,store,"0001 / +",request("ATVPDKIKX0DER"),permit(false,true));
        var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(3)).send(requests.capture(),any());assertThat(requests.getAllValues().get(1).uri().getRawPath()).isEqualTo("/products/fees/v0/listings/0001%20%2F%20%2B/feesEstimate");assertThat(requests.getAllValues().get(1).timeout()).contains(Duration.ofSeconds(5));
    }
    @Test void lowerRateIsObservedBeforePermitIsReleasedAndMalformedBodyIsRetained()throws Exception{
        var p=spy(permit(true,true));respond(response(200,"{\"access_token\":\"synthetic\",\"expires_in\":3600}",Map.of()),response(200,"not-json",Map.of("x-amzn-RateLimit-Limit",List.of("0.25"))));
        var result=api.quoteProductFee(tenant,store,"0001",request("A1F83G8C2ARO7P"),p);assertThat(result.body()).isEqualTo("not-json");assertThat(result.rateLimit()).isEqualTo(0.25);var order=inOrder(p);order.verify(p).oauth();order.verify(p).completed();order.verify(p).getAsBoolean();order.verify(p).observedRate(0.25);order.verify(p).completed();
        var capture=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(2)).send(capture.capture(),any());assertThat(capture.getAllValues().get(1).uri().getHost()).isEqualTo("sellingpartnerapi-eu.amazon.com");
    }
    @Test void retryAfterDatesAndSecondsAreRetainedWithFailureEvidence()throws Exception{
        respond(response(200,"{\"access_token\":\"synthetic\",\"expires_in\":3600}",Map.of()),response(429,"raw-failure",Map.of("Retry-After",List.of("120"))));
        assertThatThrownBy(()->api.quoteProductFee(tenant,store,"0001",request("ATVPDKIKX0DER"),permit(true,true))).isInstanceOfSatisfying(AmazonSpApiClient.FeeReadException.class,e->{assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(120));assertThat(e.body()).isEqualTo("raw-failure");});
        String date=ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(2).format(DateTimeFormatter.RFC_1123_DATE_TIME);var r=response(429,"",Map.of("Retry-After",List.of(date)));assertThat(AmazonSpApiClient.variationRetryAfter(r).toSeconds()).isBetween(118L,120L);
    }
    @Test void unsupportedRegionNeverSendsCredentials(){assertThatThrownBy(()->api.quoteProductFee(tenant,store,"0001",request("UNKNOWN"),permit(true,true))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(http);}
}

