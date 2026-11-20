package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FedExIntegratorClientTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID tenant = UUID.randomUUID(), connection = UUID.randomUUID();
    final FedExRegisteredCustomer.Scope scope = new FedExRegisteredCustomer.Scope(tenant, connection, 2, FedExClient.Environment.SANDBOX, "001234567");
    final FedExRegisteredCustomer customer = new FedExRegisteredCustomer(scope, "child+&=key", "child&secret");
    final List<HttpRequest> requests = new ArrayList<>();
    FedExClient client(FedExClient.RateApi api) {
        return new FedExClient(json, true, r -> {
            requests.add(r);
            return new FedExClient.Reply(200, r.uri().getPath().equals("/oauth/token") ? "{\"access_token\":\"fixture-token\",\"expires_in\":3600}" : "{}");
        }, api);
    }
    FedExClient.Credentials credentials() { return FedExClient.Credentials.registered("project&key", "project+secret", customer); }
    tools.jackson.databind.node.ObjectNode payload() {
        var value = json.createObjectNode(); value.putObject("accountNumber").put("value", scope.account()); return value;
    }
    @Test void registeredCustomerUsesDocumentedCspFormAndScopedCache() {
        var client = client(FedExClient.RateApi.STANDARD);
        client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, credentials(), payload());
        client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, credentials(), payload());
        assertThat(requests).hasSize(3).allSatisfy(r -> assertThat(r.uri().getHost()).isEqualTo("apis-sandbox.fedex.com"));
        assertThat(body(requests.getFirst())).isEqualTo("grant_type=csp_credentials&client_id=project%26key&client_secret=project%2Bsecret&child_Key=child%2B%26%3Dkey&child_secret=child%26secret");
        assertThat(credentials().toString()).doesNotContain("project", "child");
        assertThat(customer.toString()).doesNotContain("child+", "child&");
    }
    @Test void wrongTenantConnectionRevisionEnvironmentAndAccountFailBeforeNetwork() {
        var client = client(FedExClient.RateApi.STANDARD);
        assertThatThrownBy(() -> client.health(UUID.randomUUID(), connection, 2, FedExClient.Environment.SANDBOX, credentials())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.health(tenant, UUID.randomUUID(), 2, FedExClient.Environment.SANDBOX, credentials())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.health(tenant, connection, 3, FedExClient.Environment.SANDBOX, credentials())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.health(tenant, connection, 2, FedExClient.Environment.PRODUCTION, credentials())).isInstanceOf(IllegalArgumentException.class);
        var wrong = payload(); wrong.putObject("accountNumber").put("value", "1234567");
        assertThatThrownBy(() -> client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, credentials(), wrong)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }
    @Test void directProjectTokenCannotBeReusedForRegisteredCustomer() {
        var client = client(FedExClient.RateApi.STANDARD);
        client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, new FedExClient.Credentials("fixture", "fixture"), payload());
        client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, credentials(), payload());
        assertThat(requests.stream().filter(r -> r.uri().getPath().equals("/oauth/token")).count()).isEqualTo(2);
    }
    @Test void comprehensiveIsExplicitSandboxOnlyAndDoesNotConvertPackagingOrMutateCaller() {
        var client = client(FedExClient.RateApi.COMPREHENSIVE); var payload = payload();
        payload.putObject("requestedShipment").put("packagingType", "YOUR_PACKAGING");
        client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, credentials(), payload);
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/rate/v1/comprehensiverates/quotes");
        var actual = json.readTree(body(requests.getLast()));
        assertThat(actual.path("rateDisplayOption").asText()).isEqualTo("SELECTED_RATES_EXCLUDING_F1R");
        assertThat(actual.path("requestedShipment").path("packagingType").asText()).isEqualTo("YOUR_PACKAGING");
        assertThat(payload.has("rateDisplayOption")).isFalse();
        client.rates(tenant, connection, 2, FedExClient.Environment.PRODUCTION, new FedExClient.Credentials("fixture", "fixture"), payload);
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/rate/v1/rates/quotes");
        assertThat(json.readTree(body(requests.getLast())).has("rateDisplayOption")).isFalse();
    }
    @Test void rejectionNeverRetriesOtherEndpointOrLeaksCustomerCredentials() {
        var client = new FedExClient(json, true, r -> {
            requests.add(r);
            return new FedExClient.Reply(r.uri().getPath().equals("/oauth/token") ? 200 : 403,
                r.uri().getPath().equals("/oauth/token") ? "{\"access_token\":\"fixture\",\"expires_in\":3600}" : "child+&=key child&secret private address");
        }, FedExClient.RateApi.COMPREHENSIVE);
        assertThatThrownBy(() -> client.rates(tenant, connection, 2, FedExClient.Environment.SANDBOX, credentials(), payload()))
            .isInstanceOf(FedExClient.CarrierException.class).hasMessageNotContaining("child").hasMessageNotContaining("private address");
        assertThat(requests).hasSize(2);
    }
    private static String body(HttpRequest request) {
        var out = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer data) { byte[] bytes = new byte[data.remaining()]; data.get(bytes); out.writeBytes(bytes); }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() {}
        });
        return out.toString(StandardCharsets.UTF_8);
    }
}
