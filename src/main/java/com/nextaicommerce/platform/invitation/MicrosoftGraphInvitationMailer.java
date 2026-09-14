package com.nextaicommerce.platform.invitation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "graph")
public class MicrosoftGraphInvitationMailer implements InvitationMailer, PlatformMailer {
    private static final Logger log = LoggerFactory.getLogger(MicrosoftGraphInvitationMailer.class);
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json;
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String from;
    private final String fromName;
    private final String senderMailbox;
    private final String replyTo;
    private final LocalEmailDeliveryGuard localGuard;
    private volatile AccessToken accessToken;

    MicrosoftGraphInvitationMailer(ObjectMapper json,
            @Value("${app.mail.graph.tenant-id:}") String tenantId,
            @Value("${app.mail.graph.client-id:}") String clientId,
            @Value("${app.mail.graph.client-secret:}") String clientSecret,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:Next AI Commerce}") String fromName,
            @Value("${app.mail.sender-mailbox:}") String senderMailbox,
            @Value("${app.mail.reply-to:}") String replyTo,LocalEmailDeliveryGuard localGuard) {
        this.json = json;
        this.tenantId = clean(tenantId);
        this.clientId = clean(clientId);
        this.clientSecret = clean(clientSecret);
        this.from = clean(from);
        this.fromName = clean(fromName);
        this.senderMailbox = clean(senderMailbox);
        this.replyTo = clean(replyTo);
        this.localGuard = localGuard;
    }

    @Override
    public void send(String recipient, String inviterName, String accountName, String role, String verificationUrl) {
        sendHtml(recipient, InvitationEmailContent.subject(accountName),
            InvitationEmailContent.html(inviterName, accountName, role, verificationUrl));
    }

    @Override
    public void sendHtml(String recipient, String subject, String html) {
        validateConfiguration();
        localGuard.verify(recipient);
        try {
            Map<String, Object> message = Map.of("message", Map.of(
                "subject", subject,
                "body", Map.of("contentType", "HTML", "content", html),
                "from", Map.of("emailAddress", Map.of("address", from, "name", fromName)),
                "replyTo", new Object[] { Map.of("emailAddress", Map.of("address", replyTo)) },
                "toRecipients", new Object[] { Map.of("emailAddress", Map.of("address", recipient)) }
            ), "saveToSentItems", true);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://graph.microsoft.com/v1.0/users/" + encodePath(senderMailbox) + "/sendMail"))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202) {
                log.error("Microsoft Graph invitation delivery failed status={} recipient={} response={}",
                    response.statusCode(), recipient, safeError(response.body()));
                throw new InvitationException("The invitation could not be emailed. Please try again shortly.");
            }
            log.info("Email accepted by Microsoft Graph recipient={} subject={}", recipient, subject);
        } catch (InvitationException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("Microsoft Graph invitation delivery interrupted recipient={}", recipient, exception);
            throw new InvitationException("The invitation could not be emailed. Please try again shortly.");
        } catch (Exception exception) {
            log.error("Microsoft Graph invitation delivery failed recipient={}", recipient, exception);
            throw new InvitationException("The invitation could not be emailed. Please try again shortly.");
        }
    }

    private synchronized String token() throws Exception {
        if (accessToken != null && accessToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return accessToken.value();
        }
        String form = "client_id=" + encodeForm(clientId)
            + "&client_secret=" + encodeForm(clientSecret)
            + "&scope=" + encodeForm("https://graph.microsoft.com/.default")
            + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://login.microsoftonline.com/" + encodePath(tenantId) + "/oauth2/v2.0/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Microsoft Graph token request failed status={} response={}", response.statusCode(), safeError(response.body()));
            throw new InvitationException("Invitation email authentication failed. Please contact the platform administrator.");
        }
        JsonNode body = json.readTree(response.body());
        String value = body.path("access_token").asText();
        long expiresIn = body.path("expires_in").asLong(3600);
        if (value.isBlank()) throw new InvitationException("Invitation email authentication failed.");
        accessToken = new AccessToken(value, Instant.now().plusSeconds(expiresIn));
        return value;
    }

    private void validateConfiguration() {
        if (tenantId.isBlank() || clientId.isBlank() || clientSecret.isBlank() || from.isBlank() || senderMailbox.isBlank() || replyTo.isBlank()) {
            throw new InvitationException("Invitation email is not fully configured. Please contact the platform administrator.");
        }
    }

    private static String encodeForm(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String encodePath(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace("@", "%40");
    }

    private static String safeError(String body) {
        if (body == null) return "";
        String redacted = body.replaceAll("(?i)\\\"access_token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "\"access_token\":\"[redacted]\"");
        return redacted.substring(0, Math.min(redacted.length(), 1000));
    }

    private record AccessToken(String value, Instant expiresAt) {}
}
