package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FedExClientTest {
    final ObjectMapper json=new ObjectMapper();
    final FedExClient.Credentials keys=new FedExClient.Credentials("test-key","test-secret");
    @Test void sandboxShipmentAndAuthenticationNeverUseLiveHost(){
        List<HttpRequest> requests=new ArrayList<>();
        var client=new FedExClient(json,true,r->{requests.add(r);return new FedExClient.Reply(200,r.uri().getPath().equals("/oauth/token")?"{\"access_token\":\"test-token\",\"expires_in\":3600}":"{}");});
        client.ship(UUID.randomUUID(),UUID.randomUUID(),1,FedExClient.Environment.SANDBOX,keys,json.createObjectNode());
        assertThat(requests).hasSize(2).allSatisfy(r->assertThat(r.uri().getHost()).isEqualTo("apis-sandbox.fedex.com"));
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/ship/v1/shipments");
    }
    @Test void cacheIsTenantEnvironmentAndRevisionScopedAndRateUsesSandboxHost(){
        List<HttpRequest> requests=new ArrayList<>();
        var client=new FedExClient(json,true,r->{requests.add(r);return new FedExClient.Reply(200,r.uri().getPath().equals("/oauth/token")?"{\"access_token\":\"test-token\",\"expires_in\":3600}":"{}");});
        UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();
        for(int i=0;i<2;i++)client.rates(tenant,id,1,FedExClient.Environment.SANDBOX,keys,json.createObjectNode());
        assertThat(requests.stream().filter(r->r.uri().getPath().equals("/oauth/token")).count()).isEqualTo(1);
        assertThat(requests.getLast().uri().toString()).isEqualTo("https://apis-sandbox.fedex.com/rate/v1/rates/quotes");
        assertThat(requests.getLast().headers().firstValue("Authorization")).contains("Bearer test-token");
        client.rates(UUID.randomUUID(),id,1,FedExClient.Environment.SANDBOX,keys,json.createObjectNode());
        client.rates(tenant,id,2,FedExClient.Environment.SANDBOX,keys,json.createObjectNode());
        client.rates(tenant,id,2,FedExClient.Environment.PRODUCTION,keys,json.createObjectNode());
        assertThat(requests.stream().filter(r->r.uri().getPath().equals("/oauth/token")).count()).isEqualTo(4);
    }
    @Test void timedOutLabelIsUncertainAndNeverRetried(){
        int[] ships={0};var client=new FedExClient(json,true,r->{if(r.uri().getPath().equals("/oauth/token"))return new FedExClient.Reply(200,"{\"access_token\":\"test\",\"expires_in\":3600}");ships[0]++;throw new java.net.http.HttpTimeoutException("sensitive provider detail");});
        assertThatThrownBy(()->client.ship(UUID.randomUUID(),UUID.randomUUID(),1,FedExClient.Environment.SANDBOX,keys,json.createObjectNode()))
            .isInstanceOfSatisfying(FedExClient.CarrierException.class,e->{assertThat(e.uncertain()).isTrue();assertThat(e.getMessage()).doesNotContain("sensitive");});
        assertThat(ships[0]).isEqualTo(1);
    }
    @Test void explicitRejectionIsNotUncertainAndDoesNotLeakResponse(){
        var client=new FedExClient(json,true,r->new FedExClient.Reply(401,"secret test-key customer address"));
        assertThatThrownBy(()->client.health(UUID.randomUUID(),UUID.randomUUID(),1,FedExClient.Environment.SANDBOX,keys))
            .isInstanceOfSatisfying(FedExClient.CarrierException.class,e->{assertThat(e.uncertain()).isFalse();assertThat(e.getMessage()).doesNotContain("test-key","customer address");});
        assertThat(keys.toString()).doesNotContain("test-key","test-secret");
    }
    @Test void disabledClientNeverTouchesNetwork(){var client=new FedExClient(json,false,r->{throw new AssertionError("network");});assertThatThrownBy(()->client.health(UUID.randomUUID(),UUID.randomUUID(),1,FedExClient.Environment.SANDBOX,keys)).isInstanceOf(IllegalStateException.class);}
}
