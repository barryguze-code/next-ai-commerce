package com.nextaicommerce.platform.sync;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.web.MarketplaceCredentialService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AmazonSpApiClient {
    private static final Logger log=LoggerFactory.getLogger(AmazonSpApiClient.class);
    private static final URI LWA = URI.create("https://api.amazon.com/auth/o2/token");
    private static final String NA_ENDPOINT = "https://sellingpartnerapi-na.amazon.com";
    private final MarketplaceCredentialService credentialService;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json;
    private final Map<TokenKey,CachedToken> accessTokens=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Value("${app.integrations.external-enabled:true}")
    private boolean externalEnabled=true;
    @org.springframework.beans.factory.annotation.Value("${app.amazon.write-enabled:true}")
    private boolean writeEnabled=true;

    public AmazonSpApiClient(MarketplaceCredentialService credentialService, ObjectMapper json) {
        this.credentialService = credentialService;
        this.json = json;
    }

    public ApiResponse get(UUID tenantId, UUID connectionId, String path) {
        return send(tenantId, connectionId, "GET", path, null);
    }

    public ApiResponse post(UUID tenantId, UUID connectionId, String path, String body) {
        return send(tenantId, connectionId, "POST", path, body);
    }

    /** Calls a regional SP-API endpoint selected from the order's marketplace. */
    public ApiResponse getAt(UUID tenantId, UUID connectionId, String endpoint, String path) {
        return send(tenantId, connectionId, endpoint, "GET", path, null);
    }

    /** Calls a regional SP-API endpoint selected from the order's marketplace. */
    public ApiResponse postAt(UUID tenantId, UUID connectionId, String endpoint, String path, String body) {
        return send(tenantId, connectionId, endpoint, "POST", path, body);
    }

    /** Used for Merchant Fulfillment label refunds. */
    public ApiResponse deleteAt(UUID tenantId, UUID connectionId, String endpoint, String path) {
        return send(tenantId, connectionId, endpoint, "DELETE", path, null);
    }

    /** Applies a queued, user-approved listing update. */
    public ApiResponse patch(UUID tenantId, UUID connectionId, String path, String body) {
        return send(tenantId, connectionId, "PATCH", path, body);
    }

    public String createReport(UUID tenantId, UUID connectionId, String reportType,
            String marketplaceId, Instant start, Instant end) {
        String body;
        try {
            var root = json.createObjectNode().put("reportType", reportType);
            if (start != null) root.put("dataStartTime", start.toString());
            if (end != null) root.put("dataEndTime", end.toString());
            root.putArray("marketplaceIds").add(marketplaceId);
            body = json.writeValueAsString(root);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
        ApiResponse response = post(tenantId, connectionId, "/reports/2021-06-30/reports", body);
        return response.json().path("reportId").asText();
    }

    public String downloadDocument(UUID tenantId, UUID connectionId, String documentId) {
        ApiResponse metadata = get(tenantId, connectionId,
            "/reports/2021-06-30/documents/" + encode(documentId));
        String url = metadata.json().path("url").asText();
        String compression = metadata.json().path("compressionAlgorithm").asText();
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw new AmazonApiException(response.statusCode(), null,
                "Amazon report download failed.");
            InputStream input = new ByteArrayInputStream(response.body());
            if ("GZIP".equalsIgnoreCase(compression)) input = new GZIPInputStream(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (AmazonApiException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("Amazon report download failed.", ex); }
    }

    private ApiResponse send(UUID tenantId, UUID connectionId, String method, String path, String body) {
        return send(tenantId,connectionId,NA_ENDPOINT,method,path,body);
    }

    private ApiResponse send(UUID tenantId, UUID connectionId, String endpoint, String method, String path, String body) {
        if(!externalEnabled)throw new IllegalStateException("Amazon connections are disabled in local testing. No request was sent.");
        if(!writeEnabled&&!readOnlyOperation(method,path))
            throw new IllegalStateException("Amazon changes are disabled in Local UAT. No request was sent.");
        try {
            String operation=operation(method,path);
            log.info("Amazon request started — {}. {} {}.",operation,method,safeEndpoint(path));
            String token = accessToken(tenantId, connectionId);
            var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(45))
                .header("x-amz-access-token", token).header("Accept", "application/json");
            if ("GET".equals(method)) builder.GET();
            else if ("DELETE".equals(method)) builder.DELETE();
            else builder.header("Content-Type", "application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String requestId = response.headers().firstValue("x-amzn-RequestId").orElse(null);
            if (response.statusCode() / 100 != 2)
                throw new AmazonApiException(response.statusCode(), retryAfter(response), safeMessage(response.statusCode(),path,response.body()));
            log.info("Amazon request complete — {}. HTTP {}; Amazon request ID: {}.",operation,
                response.statusCode(),requestId==null?"not supplied":requestId);
            JsonNode parsed=response.body()==null||response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
            return new ApiResponse(response.statusCode(), requestId, parsed, response.body());
        } catch (AmazonApiException ex) { throw ex; }
        catch (HttpTimeoutException ex) { throw new AmazonTransportException("Amazon did not answer before the request timed out.",true,ex); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AmazonTransportException("The Amazon request was interrupted.",true,ex); }
        catch (Exception ex) { throw new AmazonTransportException("Amazon could not be reached.",false,ex); }
    }

    static boolean readOnlyOperation(String method,String path){
        if("GET".equals(method))return true;
        if(!"POST".equals(method))return false;
        return "/reports/2021-06-30/reports".equals(path)
            || "/batches/products/pricing/v0/itemOffers".equals(path)
            || "/mfn/v0/eligibleShippingServices".equals(path);
    }

    private String accessToken(UUID tenantId, UUID connectionId) throws Exception {
        TokenKey key=new TokenKey(tenantId,connectionId);CachedToken cached=accessTokens.get(key);
        if(cached!=null&&cached.expiresAt().isAfter(Instant.now().plusSeconds(60)))return cached.value();
        synchronized(accessTokens){
            cached=accessTokens.get(key);
            if(cached!=null&&cached.expiresAt().isAfter(Instant.now().plusSeconds(60)))return cached.value();
            Map<String, String> c = credentialService.credentials(tenantId, connectionId);
            String form = "grant_type=refresh_token&refresh_token=" + encode(c.get("refreshToken"))
                + "&client_id=" + encode(c.get("lwaClientId")) + "&client_secret=" + encode(c.get("lwaClientSecret"));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(LWA).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new AmazonApiException(response.statusCode(), null,
                "Amazon authorization needs attention.");
            JsonNode token=json.readTree(response.body());String value=token.path("access_token").asText();
            long lifetime=Math.max(120,token.path("expires_in").asLong(3600));
            accessTokens.put(key,new CachedToken(value,Instant.now().plusSeconds(lifetime)));
            return value;
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try { return Duration.ofSeconds(Math.max(1, Long.parseLong(value))); }
            catch (NumberFormatException ignored) { return Duration.ofMinutes(1); }
        }).orElse(null);
    }
    private String safeMessage(int status,String path,String body) {
        if(status==403&&path.startsWith("/mfn/"))return "Amazon has not authorized Buy Shipping for this connection. Add the Direct-to-Consumer Shipping role, then reauthorize this seller account.";
        if(status==429)return path.startsWith("/mfn/")
            ?"Amazon is receiving too many shipping requests. Wait a moment and compare rates again."
            :"Amazon is receiving too many pricing requests. The background refresh will slow down and continue.";
        if(status>=500)return path.startsWith("/mfn/")?"Amazon Buy Shipping is temporarily unavailable. No purchase was retried automatically.":"Amazon is temporarily unavailable. Please try again later.";
        try{
            JsonNode root=json.readTree(body);JsonNode error=root.path("errors").path(0);
            String message=error.path("message").asText("");if(!message.isBlank())return message.length()<=500?message:message.substring(0,500);
        }catch(Exception ignored){}
        return "Amazon rejected the request.";
    }
    private static String safeEndpoint(String path){
        if(path.startsWith("/orders/v0/orders/")&&path.contains("/orderItems"))return "/orders/v0/orders/{orderId}/orderItems";
        if(path.startsWith("/orders/v0/orders"))return "/orders/v0/orders";
        if(path.startsWith("/catalog/2022-04-01/items/"))return "/catalog/2022-04-01/items/{ASIN}?includedData=images";
        if(path.startsWith("/reports/2021-06-30/documents/"))return "/reports/2021-06-30/documents/{documentId}";
        if(path.startsWith("/reports/2021-06-30/reports/"))return "/reports/2021-06-30/reports/{reportId}";
        if(path.startsWith("/reports/2021-06-30/reports"))return "/reports/2021-06-30/reports";
        if(path.startsWith("/finances/"))return "/finances/2024-06-19/transactions";
        if(path.startsWith("/batches/products/pricing/v0/itemOffers"))return "/batches/products/pricing/v0/itemOffers";
        if(path.startsWith("/products/pricing/"))return "/products/pricing/v0/competitivePrice";
        if(path.startsWith("/listings/"))return "/listings/2021-08-01/items/{sellerId}/{sku}";
        if(path.startsWith("/mfn/v0/eligibleShippingServices"))return "/mfn/v0/eligibleShippingServices";
        if(path.startsWith("/mfn/v0/shipments/"))return "/mfn/v0/shipments/{shipmentId}";
        if(path.startsWith("/mfn/v0/shipments"))return "/mfn/v0/shipments";
        return path.contains("?")?path.substring(0,path.indexOf('?')):path;
    }
    private static String operation(String method,String path){
        if(path.startsWith("/orders/v0/orders/")&&path.contains("/orderItems"))return "Get item details for one changed order";
        if(path.startsWith("/orders/v0/orders"))return "Find new and changed orders";
        if(path.startsWith("/catalog/2022-04-01/items/"))return "Get a product's main Amazon image";
        if("POST".equals(method)&&path.equals("/reports/2021-06-30/reports"))return "Request an Amazon report";
        if(path.startsWith("/reports/2021-06-30/documents/"))return "Get the secure report download location";
        if(path.startsWith("/reports/2021-06-30/reports/"))return "Check whether an Amazon report is ready";
        if(path.startsWith("/finances/"))return "Get financial transaction changes";
        if(path.startsWith("/batches/products/pricing/v0/itemOffers"))return "Verify current Featured Offers";
        if(path.startsWith("/products/pricing/"))return "Get current Buy Box prices";
        if("PATCH".equals(method)&&path.startsWith("/listings/"))return "Update Amazon listing price or seller-fulfilled quantity";
        if(path.startsWith("/mfn/v0/eligibleShippingServices"))return "Compare Amazon Buy Shipping services";
        if("POST".equals(method)&&path.equals("/mfn/v0/shipments"))return "Purchase an Amazon Buy Shipping label";
        if("DELETE".equals(method)&&path.startsWith("/mfn/v0/shipments/"))return "Request an Amazon Buy Shipping label refund";
        if(path.startsWith("/mfn/v0/shipments/"))return "Check an Amazon Buy Shipping shipment";
        if(path.startsWith("/sellers/"))return "Verify the connected Amazon seller account";
        return "Amazon data request";
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public record ApiResponse(int status, String requestId, JsonNode json, String body) {}
    private record TokenKey(UUID tenantId,UUID connectionId){}
    private record CachedToken(String value,Instant expiresAt){}
    public static class AmazonApiException extends RuntimeException {
        private final int status; private final Duration retryAfter;
        public AmazonApiException(int status, Duration retryAfter, String message) { super(message); this.status=status; this.retryAfter=retryAfter; }
        public int status() { return status; } public Duration retryAfter() { return retryAfter; }
    }
    public static class AmazonTransportException extends RuntimeException {
        private final boolean outcomeUnknown;
        public AmazonTransportException(String message,boolean outcomeUnknown,Throwable cause){super(message,cause);this.outcomeUnknown=outcomeUnknown;}
        public boolean outcomeUnknown(){return outcomeUnknown;}
    }
}
