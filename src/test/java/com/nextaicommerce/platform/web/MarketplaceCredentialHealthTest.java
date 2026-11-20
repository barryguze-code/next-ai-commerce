package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.sync.AmazonInitializationService;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MarketplaceCredentialHealthTest {
    @Test void spApiFailureDoesNotReplaceStoredCredentials() throws Exception {
        check(403, false, "ATVPDKIKX0DER", "ATVPDKIKX0DER");
    }
    @Test void successfulReadOnlyHealthCheckAllowsSave() throws Exception {
        check(200, true, "ATVPDKIKX0DER", "ATVPDKIKX0DER");
    }
    @Test void wrongMarketplaceDoesNotReplaceStoredCredentials() throws Exception {
        check(200, false, "ATVPDKIKX0DER", "A2EUQ1WTGCTBG2");
    }
    @Test void ukUsesEuropeanHealthEndpoint() throws Exception {
        check(200, true, "A1F83G8C2ARO7P", "A1F83G8C2ARO7P");
    }
    @SuppressWarnings("unchecked")
    private void check(int status, boolean success, String marketplace, String authorizedMarketplace) throws Exception {
        var repository=mock(WorkspaceAccessRepository.class);
        var initialization=mock(AmazonInitializationService.class);
        var http=mock(HttpClient.class);
        HttpResponse<String> login=mock(HttpResponse.class), health=mock(HttpResponse.class);
        when(login.statusCode()).thenReturn(200);
        when(login.body()).thenReturn("{\"access_token\":\"test-token\"}");
        when(health.statusCode()).thenReturn(status);
        when(health.body()).thenReturn("{\"payload\":[{\"marketplace\":{\"id\":\"" + authorizedMarketplace + "\"},\"participation\":{\"isParticipating\":true}}]}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(login,health);
        UUID tenant=UUID.randomUUID(), connection=UUID.randomUUID();
        when(repository.findConnection(tenant,connection)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(connection,"AMAZON",marketplace));
        var service=new MarketplaceCredentialService(repository,initialization,Base64.getEncoder().encodeToString(new byte[32]),http);
        Map<String,String> form=Map.of("lwaClientId","test-id","lwaClientSecret","test-secret","refreshToken","test-refresh");
        if(success) {
            service.connect(tenant,connection,form);
            verify(repository).saveCredentials(eq(tenant),eq(connection),any(byte[].class),any(byte[].class),eq(true));
        } else {
            assertThrows(IllegalArgumentException.class,()->service.connect(tenant,connection,form));
            verify(repository,never()).saveCredentials(any(),any(),any(),any(),anyBoolean());
            verifyNoInteractions(initialization);
        }
        String host="A1F83G8C2ARO7P".equals(marketplace)?"sellingpartnerapi-eu.amazon.com":"sellingpartnerapi-na.amazon.com";
        verify(http).send(argThat(request->"GET".equals(request.method()) && request.uri().getHost().equals(host) && request.uri().getPath().equals("/sellers/v1/marketplaceParticipations")),any(HttpResponse.BodyHandler.class));
    }
}
