package com.nextaicommerce.platform.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.nextaicommerce.platform.sync.AmazonInitializationService;

@Service
public class MarketplaceCredentialService {
    public record EncryptedCredentials(byte[] payload, byte[] nonce) {}

    private final WorkspaceAccessRepository repository;
    private final HttpClient httpClient;
    private final byte[] encryptionKey;
    private final AmazonInitializationService initializationService;

    @org.springframework.beans.factory.annotation.Autowired
    public MarketplaceCredentialService(WorkspaceAccessRepository repository,
            AmazonInitializationService initializationService,
            @Value("${app.credentials.encryption-key:}") String encodedKey) {
        this(repository, initializationService, encodedKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    MarketplaceCredentialService(WorkspaceAccessRepository repository,
            AmazonInitializationService initializationService, String encodedKey, HttpClient httpClient) {
        this.repository = repository;
        this.initializationService = initializationService;
        this.httpClient = httpClient;
        this.encryptionKey = decodeKey(encodedKey);
    }

    public void connect(UUID tenantId, UUID connectionId, Map<String, String> form) {
        var connection = repository.findConnection(tenantId, connectionId);
        Map<String, String> credentials = credentialsFor(connection.channel(), form);
        validate(connection.channel(), connection.marketplaceId(), credentials);
        EncryptedCredentials encrypted = encrypt(credentials);
        boolean amazon = "AMAZON".equals(connection.channel());
        repository.saveCredentials(tenantId, connectionId, encrypted.payload(), encrypted.nonce(), amazon);
        if (amazon) initializationService.enqueue(tenantId, connectionId);
    }

    public Map<String, String> credentials(UUID tenantId, UUID connectionId) {
        if (encryptionKey == null) throw new IllegalStateException("Credential encryption is not configured.");
        var stored = repository.loadCredentials(tenantId, connectionId);
        if (stored == null) throw new IllegalStateException("Amazon credentials are not connected.");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(128, stored.nonce()));
            String encoded = new String(cipher.doFinal(stored.payload()), StandardCharsets.UTF_8);
            Map<String, String> result = new LinkedHashMap<>();
            for (String pair : encoded.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            return Map.copyOf(result);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored marketplace credentials could not be opened.", ex);
        }
    }

    private Map<String, String> credentialsFor(String channel, Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        if ("AMAZON".equals(channel)) {
            values.put("lwaClientId", required(form, "lwaClientId"));
            values.put("lwaClientSecret", required(form, "lwaClientSecret"));
            values.put("refreshToken", required(form, "refreshToken"));
        } else if ("WALMART".equals(channel)) {
            values.put("clientId", required(form, "clientId"));
            values.put("clientSecret", required(form, "clientSecret"));
        } else {
            throw new IllegalArgumentException("Unsupported marketplace channel.");
        }
        return values;
    }

    private void validate(String channel, String marketplaceId, Map<String, String> credentials) {
        try {
            HttpRequest request = "AMAZON".equals(channel)
                ? amazonRequest(credentials) : walmartRequest(credentials);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalArgumentException("The marketplace rejected these credentials. Check the values and permissions.");
            if ("AMAZON".equals(channel)) {
                String token = new tools.jackson.databind.ObjectMapper().readTree(response.body()).path("access_token").asText();
                if (token.isBlank()) throw new IllegalArgumentException("Amazon did not return an access token. Check the credentials.");
                String region = "A1F83G8C2ARO7P".equals(marketplaceId) ? "eu" : "na";
                HttpResponse<String> health = httpClient.send(HttpRequest.newBuilder(
                        URI.create("https://sellingpartnerapi-" + region + ".amazon.com/sellers/v1/marketplaceParticipations"))
                    .timeout(Duration.ofSeconds(20)).header("x-amz-access-token", token)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                if (health.statusCode() / 100 != 2)
                    throw new IllegalArgumentException("Amazon login succeeded, but the SP-API health check failed. Check seller authorization and API permissions, then try again. Existing credentials were not changed.");
                var participations = new tools.jackson.databind.ObjectMapper().readTree(health.body()).path("payload");
                boolean authorized = false;
                for (var participation : participations) {
                    if (marketplaceId.equals(participation.path("marketplace").path("id").asText())
                            && participation.path("participation").path("isParticipating").asBoolean()) authorized = true;
                }
                if (!authorized) throw new IllegalArgumentException("These credentials do not authorize the selected Amazon marketplace. Existing credentials were not changed.");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The marketplace could not be reached. Please try again.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("The marketplace could not be reached. Please try again.", ex);
        }
    }

    private HttpRequest amazonRequest(Map<String, String> c) {
        String body = form(Map.of("grant_type", "refresh_token", "refresh_token", c.get("refreshToken"),
            "client_id", c.get("lwaClientId"), "client_secret", c.get("lwaClientSecret")));
        return HttpRequest.newBuilder(URI.create("https://api.amazon.com/auth/o2/token"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest walmartRequest(Map<String, String> c) {
        String basic = Base64.getEncoder().encodeToString(
            (c.get("clientId") + ":" + c.get("clientSecret")).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("https://marketplace.walmartapis.com/v3/token"))
            .timeout(Duration.ofSeconds(20)).header("Authorization", "Basic " + basic)
            .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
            .header("WM_QOS.CORRELATION_ID", UUID.randomUUID().toString())
            .header("WM_SVC.NAME", "Walmart Marketplace")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
    }

    private EncryptedCredentials encrypt(Map<String, String> credentials) {
        if (encryptionKey == null)
            throw new IllegalStateException("Credential encryption is not configured. Set APP_CREDENTIAL_ENCRYPTION_KEY.");
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            return new EncryptedCredentials(
                cipher.doFinal(form(credentials).getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (Exception ex) {
            throw new IllegalStateException("Credentials could not be encrypted.", ex);
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("APP_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key.");
        }
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Complete every required credential field.");
        return value.trim();
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElse("");
    }
}
