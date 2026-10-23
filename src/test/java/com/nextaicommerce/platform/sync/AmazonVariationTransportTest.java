package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AmazonVariationTransportTest {
    @SuppressWarnings("unchecked") private HttpResponse<String> headers(String value){
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of(value)),(a,b)->true));return response;
    }
    @Test void retryAfterSupportsSecondsHttpDatesAndMalformedValues(){
        assertThat(AmazonSpApiClient.variationRetryAfter(headers("900"))).isEqualTo(Duration.ofSeconds(900));
        String future=java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(AmazonSpApiClient.variationRetryAfter(headers(future))).isBetween(Duration.ofSeconds(3590),Duration.ofSeconds(3600));
        assertThat(AmazonSpApiClient.variationRetryAfter(headers("invalid"))).isEqualTo(Duration.ofMinutes(1));
        assertThat(AmazonSpApiClient.variationRetryAfter(headers("-1"))).isEqualTo(Duration.ofSeconds(1));
    }
    @Test void exactPathEncodingAndKnownRegions(){
        assertThat(AmazonVariationRelationshipSync.encode("0001 /+é")).isEqualTo("0001%20%2F%2B%C3%A9");
        assertThat(AmazonVariationRelationshipSync.endpoint("ATVPDKIKX0DER")).endsWith("-na.amazon.com");
        assertThat(AmazonVariationRelationshipSync.endpoint("A1PA6795UKMFR9")).endsWith("-eu.amazon.com");
        assertThat(AmazonVariationRelationshipSync.endpoint("A1VC38T7YXB528")).endsWith("-fe.amazon.com");
        assertThatThrownBy(()->AmazonVariationRelationshipSync.endpoint("UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidHostOrOperationRejectedBeforeCredentials(){
        var credentials=mock(com.nextaicommerce.platform.web.MarketplaceCredentialService.class);
        var client=new AmazonSpApiClient(credentials,new tools.jackson.databind.ObjectMapper());
        assertThatThrownBy(()->client.getVariationAt(UUID.randomUUID(),UUID.randomUUID(),"https://example.invalid","/listings/2021-08-01/items/seller/sku")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->client.getVariationAt(UUID.randomUUID(),UUID.randomUUID(),"https://sellingpartnerapi-na.amazon.com","/orders/v0/orders")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(credentials);
    }
    @SuppressWarnings("unchecked") @Test void existingClientRetainsMalformedReadAndRateMetadataWithoutNetwork()throws Exception{
        var credentials=mock(com.nextaicommerce.platform.web.MarketplaceCredentialService.class);UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
        when(credentials.credentials(tenant,connection)).thenReturn(Map.of("refreshToken","synthetic","lwaClientId","synthetic","lwaClientSecret","synthetic"));
        var client=new AmazonSpApiClient(credentials,new tools.jackson.databind.ObjectMapper());HttpClient http=mock(HttpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(client,"http",http);
        HttpResponse<String> token=mock(HttpResponse.class),listing=mock(HttpResponse.class),limited=mock(HttpResponse.class);
        when(token.statusCode()).thenReturn(200);when(token.body()).thenReturn("{\"access_token\":\"synthetic\",\"expires_in\":3600}");
        when(listing.statusCode()).thenReturn(200);when(listing.body()).thenReturn("malformed JSON");
        when(listing.headers()).thenReturn(HttpHeaders.of(Map.of("x-amzn-RateLimit-Limit",List.of("0.25")),(a,b)->true));
        when(limited.statusCode()).thenReturn(429);when(limited.body()).thenReturn("raw quota response");
        when(limited.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("900")),(a,b)->true));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(token,listing,limited);
        String endpoint="https://sellingpartnerapi-na.amazon.com",path="/listings/2021-08-01/items/seller/0001?marketplaceIds=ATVPDKIKX0DER&includedData=summaries,attributes,relationships";
        assertThatThrownBy(()->client.getVariationAt(tenant,connection,endpoint,path,()->false)).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)); // Token refresh only; no listing dispatch.
        var result=client.getVariationAt(tenant,connection,endpoint,path);assertThat(result.body()).isEqualTo("malformed JSON");assertThat(result.rateLimit()).isEqualTo(0.25);
        assertThatThrownBy(()->client.getVariationAt(tenant,connection,endpoint,path)).isInstanceOfSatisfying(AmazonSpApiClient.VariationReadException.class,e->{assertThat(e.rawBody()).isEqualTo("raw quota response");assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(900));assertThat(e.toString()).doesNotContain("raw quota response");});
        var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(3)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().get(1).method()).isEqualTo("GET");assertThat(requests.getAllValues().get(1).uri().toString()).endsWith(path);
    }

}
