package com.nextaicommerce.platform.shipping;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.io.IOException;
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
    public enum RateApi {
        STANDARD("/rate/v1/rates/quotes"), COMPREHENSIVE("/rate/v1/comprehensiverates/quotes");
        final String path;
        RateApi(String path) { this.path = path; }
    }
    // Deliberately not a record: generated toString must never expose secrets.
    public static final class Credentials {
        final String clientId, secret;
        final FedExRegisteredCustomer customer;
        public Credentials(String clientId, String secret) {
            this(clientId, secret, null);
        }
        private Credentials(String clientId, String secret, FedExRegisteredCustomer customer) {
            if (clientId == null || clientId.isBlank() || secret == null || secret.isBlank())
                throw new IllegalArgumentException("Configure both FedEx API keys.");
            this.clientId = clientId.trim(); this.secret = secret.trim();
            this.customer = customer;
        }
        public static Credentials registered(String clientId, String secret, FedExRegisteredCustomer customer) {
            return new Credentials(clientId, secret, java.util.Objects.requireNonNull(customer));
        }
        @Override public String toString() { return "FedEx credentials [protected]"; }
    }
    record TokenKey(UUID tenant, UUID connection, long revision, Environment environment, boolean registered) {}
    record Token(String value, Instant expires) {}
    record Reply(int status, String body) {}
    @FunctionalInterface interface Transport { Reply send(HttpRequest request) throws Exception; }
    private final Transport transport;
    private final ObjectMapper json;
    private final boolean enabled;
    private final boolean simulated;
    private final RateApi sandboxRateApi;
    private final Cache<TokenKey, Token> tokens = Caffeine.newBuilder().maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(55)).build();

    @Autowired
    public FedExClient(ObjectMapper json, @Value("${app.fedex.external-enabled:false}") boolean enabled,
                       @Value("${app.fedex.sandbox-rate-api:STANDARD}") RateApi sandboxRateApi,
                       @Value("${app.fedex.simulation-enabled:false}") boolean simulated) {
        this(json,enabled,simulated?new FedExSimulation(json):defaultTransport(),sandboxRateApi,simulated);
    }
    public FedExClient(ObjectMapper json, boolean enabled) {
        this(json, enabled, defaultTransport(), RateApi.STANDARD);
    }
    FedExClient(ObjectMapper json, boolean enabled, Transport transport) {
        this(json, enabled, transport, RateApi.STANDARD);
    }
    FedExClient(ObjectMapper json, boolean enabled, Transport transport, RateApi sandboxRateApi) {
        this(json,enabled,transport,sandboxRateApi,false);
    }
    FedExClient(ObjectMapper json, boolean enabled, Transport transport, RateApi sandboxRateApi,boolean simulated) {
        this.json = json; this.enabled = enabled; this.transport = transport;
        this.simulated=simulated;
        this.sandboxRateApi = java.util.Objects.requireNonNull(sandboxRateApi);
    }
    public boolean simulated(){return simulated;}
    static Transport defaultTransport() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                return new Reply(response.statusCode(), readBody(body, response.headers().firstValue("Content-Encoding").orElse("")));
            }
        };
    }
    static String readBody(InputStream body, String encoding) throws IOException {
        String type = encoding.trim();
        if (!type.isEmpty() && !"identity".equalsIgnoreCase(type) && !"gzip".equalsIgnoreCase(type))
            throw new IOException("Unsupported FedEx response encoding.");
        byte[] bytes = body.readNBytes(8_000_001);
        if (bytes.length > 8_000_000) throw new IllegalStateException("FedEx response exceeded the supported size.");
        boolean gzip = "gzip".equalsIgnoreCase(type) || (type.isEmpty() && bytes.length > 1
            && (bytes[0] & 255) == 31 && (bytes[1] & 255) == 139);
        if (gzip) {
            try (var decoded = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                bytes = decoded.readNBytes(8_000_001);
                if (bytes.length > 8_000_000) throw new IllegalStateException("FedEx response exceeded the supported size.");
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public void health(UUID tenant, UUID connection, long revision, Environment environment, Credentials credentials) {
        var key = key(tenant, connection, revision, environment, credentials);
        tokens.invalidate(key);
        token(key, credentials);
    }
    void invalidate(UUID tenant, UUID connection) {
        tokens.asMap().keySet().removeIf(key -> key.tenant().equals(tenant) && key.connection().equals(connection));
    }
    public JsonNode rates(UUID tenant, UUID connection, long revision, Environment environment,
                          Credentials credentials, JsonNode payload) {
        RateApi api = environment == Environment.SANDBOX && (simulated || credentials.customer != null) ? sandboxRateApi : RateApi.STANDARD;
        JsonNode rated = payload;
        if (api == RateApi.COMPREHENSIVE) {
            if (!(payload instanceof tools.jackson.databind.node.ObjectNode object))
                throw new IllegalArgumentException("FedEx rate details are required.");
            // Existing shipment generation does not request One Rate packaging/services.
            var copy = object.deepCopy();
            copy.put("rateDisplayOption", "SELECTED_RATES_EXCLUDING_F1R");
            rated = copy;
        }
        return request(key(tenant, connection, revision, environment, credentials), credentials,
            api.path, rated, false);
    }
    public JsonNode ship(UUID tenant, UUID connection, long revision, Environment environment,
                         Credentials credentials, JsonNode payload) {
        return request(key(tenant, connection, revision, environment, credentials), credentials,
            "/ship/v1/shipments", payload, true);
    }
    /** Separate local public-fixture diagnostic; never changes normal merchant/CSP routing. */
    JsonNode sandboxParentRates(UUID tenant, UUID connection, Credentials credentials, JsonNode payload) {
        if (simulated || credentials.customer != null) throw new IllegalStateException("Use the dedicated sandbox rate-only connection.");
        var rated = ((tools.jackson.databind.node.ObjectNode) payload).deepCopy();
        rated.put("rateDisplayOption", "SELECTED_RATES_EXCLUDING_F1R");
        return request(key(tenant, connection, 1, Environment.SANDBOX, credentials), credentials,
            RateApi.COMPREHENSIVE.path, rated, false);
    }
    private JsonNode request(TokenKey key, Credentials credentials, String path, JsonNode payload, boolean mutation) {
        if (credentials.customer != null) credentials.customer.requireAccount(payload.path("accountNumber").path("value").asText(""));
        String token = token(key, credentials);
        var request = HttpRequest.newBuilder(URI.create(key.environment.host + path))
            .timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        return send(request, mutation);
    }
    private String token(TokenKey key, Credentials credentials) {
        if (!enabled&&!simulated) throw new IllegalStateException("FedEx external requests are disabled in this environment.");
        Token existing = tokens.getIfPresent(key);
        if (existing != null && existing.expires.isAfter(Instant.now().plusSeconds(60))) return existing.value;
        if (existing != null) tokens.invalidate(key);
        // Caffeine coalesces refreshes for this connection, not across unrelated tenants.
        return tokens.get(key, ignored -> {
            String form = "grant_type=" + (credentials.customer == null ? "client_credentials" : "csp_credentials")
                + "&client_id=" + encode(credentials.clientId)
                + "&client_secret=" + encode(credentials.secret);
            if (credentials.customer != null) form += "&child_Key=" + encode(credentials.customer.childKey)
                + "&child_secret=" + encode(credentials.customer.childSecret);
            var response = send(HttpRequest.newBuilder(URI.create(key.environment.host + "/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), false);
            String value = response.path("access_token").asText("");
            long expiry = response.path("expires_in").asLong(0);
            if (value.isBlank() || expiry <= 60) throw new IllegalStateException("FedEx returned an invalid authentication response.");
            return new Token(value, Instant.now().plusSeconds(Math.min(expiry, 3600)));
        }).value;
    }
    private TokenKey key(UUID tenant, UUID connection, long revision, Environment environment, Credentials credentials) {
        if(simulated&&environment!=Environment.SANDBOX)throw new IllegalArgumentException("Simulation cannot use a production carrier connection.");
        if (credentials.customer != null) credentials.customer.requireScope(tenant, connection, revision, environment);
        return new TokenKey(tenant, connection, revision, environment, credentials.customer != null);
    }
    private JsonNode send(HttpRequest request, boolean mutation) {
        try {
            Reply reply = transport.send(request);
            if (reply.status < 200 || reply.status >= 300) {
                // Never echo carrier bodies: they can contain credentials or customer addresses.
                String guidance = reply.status >= 500
                    ? (mutation ? "FedEx returned a server error (HTTP " + reply.status + "). Review shipment status before retrying."
                        : "FedEx returned a temporary service error (HTTP " + reply.status + "). Retry shortly; if it continues, review the request details.")
                    : reply.status == 401 || reply.status == 403
                        ? "FedEx denied access (HTTP " + reply.status + "). Check credentials and account permissions."
                        : "FedEx rejected the request (HTTP " + reply.status + "). Review shipment details.";
                throw new CarrierException(guidance,
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
