package com.nextaicommerce.platform.shipping;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed carrier hosts, bounded token cache, and no automatic retry of shipment mutations. */
@Component
public class FedExClient {
    public enum Environment {
        SANDBOX("https://apis-sandbox.fedex.com"), PRODUCTION("https://apis.fedex.com");
        final String host;
        Environment(String host) { this.host = host; }
    }
    // Deliberately not a record: generated toString must never expose secrets.
    public static final class Credentials {
        final String clientId, secret;
        public Credentials(String clientId, String secret) {
            if (clientId == null || clientId.isBlank() || secret == null || secret.isBlank())
                throw new IllegalArgumentException("Configure both FedEx API keys.");
            this.clientId = clientId.trim(); this.secret = secret.trim();
        }
        @Override public String toString() { return "FedEx credentials [protected]"; }
    }
    record TokenKey(UUID tenant, UUID connection, long revision, Environment environment) {}
    record Token(String value, Instant expires) {}
    record Reply(int status, String body) {}
    @FunctionalInterface interface Transport { Reply send(HttpRequest request) throws Exception; }
    private final Transport transport;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Cache<TokenKey, Token> tokens = Caffeine.newBuilder().maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(55)).build();

    @Autowired
    public FedExClient(ObjectMapper json, @Value("${app.fedex.external-enabled:false}") boolean enabled) {
        this(json, enabled, defaultTransport());
    }
    FedExClient(ObjectMapper json, boolean enabled, Transport transport) {
        this.json = json; this.enabled = enabled; this.transport = transport;
    }
    private static Transport defaultTransport() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(8_000_001);
                if (bytes.length > 8_000_000) throw new IllegalStateException("FedEx response exceeded the supported size.");
                return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        };
    }
    public void health(UUID tenant, UUID connection, long revision, Environment environment, Credentials credentials) {
        var key = new TokenKey(tenant, connection, revision, environment);
        tokens.invalidate(key);
        token(key, credentials);
    }
    public JsonNode rates(UUID tenant, UUID connection, long revision, Environment environment,
                          Credentials credentials, JsonNode payload) {
        return request(new TokenKey(tenant, connection, revision, environment), credentials,
            "/rate/v1/rates/quotes", payload, false);
    }
    public JsonNode ship(UUID tenant, UUID connection, long revision, Environment environment,
                         Credentials credentials, JsonNode payload) {
        return request(new TokenKey(tenant, connection, revision, environment), credentials,
            "/ship/v1/shipments", payload, true);
    }
    private JsonNode request(TokenKey key, Credentials credentials, String path, JsonNode payload, boolean mutation) {
        String token = token(key, credentials);
        var request = HttpRequest.newBuilder(URI.create(key.environment.host + path))
            .timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        return send(request, mutation);
    }
    private String token(TokenKey key, Credentials credentials) {
        if (!enabled) throw new IllegalStateException("FedEx external requests are disabled in this environment.");
        Token existing = tokens.getIfPresent(key);
        if (existing != null && existing.expires.isAfter(Instant.now().plusSeconds(60))) return existing.value;
        if (existing != null) tokens.invalidate(key);
        // Caffeine coalesces refreshes for this connection, not across unrelated tenants.
        return tokens.get(key, ignored -> {
            String form = "grant_type=client_credentials&client_id=" + encode(credentials.clientId)
                + "&client_secret=" + encode(credentials.secret);
            var response = send(HttpRequest.newBuilder(URI.create(key.environment.host + "/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), false);
            String value = response.path("access_token").asText("");
            long expiry = response.path("expires_in").asLong(0);
            if (value.isBlank() || expiry <= 60) throw new IllegalStateException("FedEx returned an invalid authentication response.");
            return new Token(value, Instant.now().plusSeconds(Math.min(expiry, 3600)));
        }).value;
    }
    private JsonNode send(HttpRequest request, boolean mutation) {
        try {
            Reply reply = transport.send(request);
            if (reply.status < 200 || reply.status >= 300) {
                // Never echo carrier bodies: they can contain credentials or customer addresses.
                throw new CarrierException("FedEx rejected the request (HTTP " + reply.status + "). Check credentials, account permissions and shipment details.",
                    mutation && (reply.status >= 500 || reply.status == 408));
            }
            return json.readTree(reply.body);
        } catch (CarrierException ex) { throw ex; }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CarrierException("FedEx request interrupted. Review shipment status before retrying.", mutation);
        } catch (Exception ex) {
            throw new CarrierException("FedEx response could not be confirmed. Review shipment status before retrying.", mutation);
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static class CarrierException extends RuntimeException {
        private final boolean uncertain;
        public CarrierException(String message, boolean uncertain) { super(message); this.uncertain = uncertain; }
        public boolean uncertain() { return uncertain; }
    }
}
