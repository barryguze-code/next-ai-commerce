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

    /** Dedicated PII transport: no raw archive, response-bearing exception cause, or body logging. */
    public com.nextaicommerce.platform.shipping.OrderRecipient recipient(UUID tenant,UUID store,String endpoint,String order) {
        if(!externalEnabled)throw new AmazonApiException(503,null,"Recipient retrieval is disabled.");
        if(!java.util.Set.of(NA_ENDPOINT,"https://sellingpartnerapi-eu.amazon.com","https://sellingpartnerapi-fe.amazon.com").contains(endpoint)
            ||!order.matches("[0-9]{3}-[0-9]{7}-[0-9]{7}"))throw new IllegalArgumentException("Invalid recipient request.");
        try {
            var request=HttpRequest.newBuilder(URI.create(endpoint+"/orders/2026-01-01/orders/"+order+"?includedData=RECIPIENT"))
                .timeout(Duration.ofSeconds(30)).header("x-amz-access-token",accessToken(tenant,store)).header("Accept","application/json").GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                if(response.statusCode()/100!=2)throw new AmazonApiException(response.statusCode(),null,"Amazon recipient retrieval was not successful.");
                byte[] bytes=input.readNBytes(2_000_001);
                if(bytes.length>2_000_000)throw new IllegalArgumentException("Recipient response exceeded the safe size limit.");
                try{return com.nextaicommerce.platform.shipping.OrderRecipient.parse(json.readTree(bytes),order);}
                finally{java.util.Arrays.fill(bytes,(byte)0);}
            }
        }catch(AmazonApiException ex){throw ex;}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AmazonApiException(503,null,"Recipient retrieval was interrupted.");}
        catch(Exception ex){throw new AmazonApiException(503,null,"Recipient retrieval is temporarily unavailable.");}
    }

    public ApiResponse post(UUID tenantId, UUID connectionId, String path, String body) {
        return send(tenantId, connectionId, "POST", path, body);
    }

    public interface RequestPermit extends java.util.function.BooleanSupplier {
        boolean oauth();
        Duration remaining();
        default void observedRate(Double rate){}
        default void completed(){}
        default void uncertain(){completed();}
    }
    public interface VariationPermit extends RequestPermit {}
    public interface FeePermit extends RequestPermit {}
    /** Fee estimates are reads; reserve dispatch after cached OAuth resolution. */
    static String endpoint(String market){
        if(java.util.Set.of("ATVPDKIKX0DER","A2EUQ1WTGCTBG2","A1AM78C64UM0Y8","A2Q3Y263D00KWC").contains(market))return "https://sellingpartnerapi-na.amazon.com";
        if(java.util.Set.of("A1F83G8C2ARO7P","A1PA6795UKMFR9","A13V1IB3VIYZZH","APJ6JRA9NG5V4","A1RKKUPIHCS9HS","A1805IZSGTT6HS","A2NODRKZP88ZB9","A1C3SOZRARQ6R3","A33AVAJ2PDY3EV","A21TJRUUN4KGV","A2VIGQ35RCS4UG","A17E79C6D8DWNP","A28R8C7NBKEWEA").contains(market))return "https://sellingpartnerapi-eu.amazon.com";
        if(java.util.Set.of("A1VC38T7YXB528","A39IBJ37TRP1C6","A19VAU5U5O7RUS").contains(market))return "https://sellingpartnerapi-fe.amazon.com";
        throw new IllegalArgumentException("Unsupported marketplace region");
    }
    public ApiResponse quoteProductFee(UUID tenant,UUID connection,String sku,String body,FeePermit permit){
        String path="/products/fees/v0/listings/"+encode(sku).replace("+","%20")+"/feesEstimate";
        java.util.Objects.requireNonNull(permit,"Fee dispatch permit required");
        String market=json.readTree(body).path("FeesEstimateRequest").path("MarketplaceId").asText();
        return send(tenant,connection,endpoint(market),"POST",path,body,true,permit);
    }
    private static Duration timeout(java.util.function.BooleanSupplier permit){
        Duration remaining=permit instanceof RequestPermit p?p.remaining():Duration.ofSeconds(30);
        return remaining.compareTo(Duration.ofSeconds(30))<0?remaining:Duration.ofSeconds(30);
    }
    /** Read-only Listings Items transport retaining malformed successful response text for A2 diagnostics. */
    public ApiResponse getVariationAt(UUID tenantId,UUID connectionId,String endpoint,String path){
        return getVariationAt(tenantId,connectionId,endpoint,path,null);
    }
    public ApiResponse getVariationAt(UUID tenantId,UUID connectionId,String endpoint,String path,java.util.function.BooleanSupplier permit){
        if(!java.util.Set.of(NA_ENDPOINT,"https://sellingpartnerapi-eu.amazon.com","https://sellingpartnerapi-fe.amazon.com").contains(endpoint)||!path.startsWith("/listings/2021-08-01/items/"))throw new IllegalArgumentException("Invalid relationship path");
        return send(tenantId,connectionId,endpoint,"GET",path,null,true,permit);
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
        return send(tenantId,connectionId,endpoint,method,path,body,false);
    }
    private ApiResponse send(UUID tenantId, UUID connectionId, String endpoint, String method, String path, String body,boolean retainMalformed) {
        return send(tenantId,connectionId,endpoint,method,path,body,retainMalformed,null);
    }
    private ApiResponse send(UUID tenantId, UUID connectionId, String endpoint, String method, String path, String body,boolean retainMalformed,java.util.function.BooleanSupplier permit) {
        if(!externalEnabled)throw new IllegalStateException("Amazon connections are disabled in local testing. No request was sent.");
        if(!writeEnabled&&!readOnlyOperation(method,path))
            throw new IllegalStateException("Amazon changes are disabled in Local UAT. No request was sent.");
        try {
            String operation=operation(method,path);
            log.info("Amazon request started — {}. {} {}.",operation,method,safeEndpoint(path));
            String token = accessToken(tenantId, connectionId,permit);
            // Reserve Listings capacity after token refresh, immediately before request dispatch.
            if(permit!=null&&!permit.getAsBoolean())throw new VariationDeferredException();
            var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(permit instanceof RequestPermit?timeout(permit):Duration.ofSeconds(45))
                .header("x-amz-access-token", token).header("Accept", "application/json");
            if ("GET".equals(method)) builder.GET();
            else if ("DELETE".equals(method)) builder.DELETE();
            else builder.header("Content-Type", "application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = dispatch(builder.build(),permit);
            String requestId = response.headers().firstValue("x-amzn-RequestId").orElse(null);
            if (response.statusCode() / 100 != 2)
                throw retainMalformed?(permit instanceof FeePermit
                    ?new FeeReadException(response.statusCode(),variationRetryAfter(response),response.body(),requestId,rateLimit(response))
                    :new VariationReadException(response.statusCode(),variationRetryAfter(response),response.body(),requestId,rateLimit(response)))
                    :new AmazonApiException(response.statusCode(), retryAfter(response), safeMessage(response.statusCode(),path,response.body()));
            log.info("Amazon request complete — {}. HTTP {}; Amazon request ID: {}.",operation,
                response.statusCode(),requestId==null?"not supplied":requestId);
            JsonNode parsed;
            try{parsed=response.body()==null||response.body().isBlank()?json.createObjectNode():json.readTree(response.body());}
            catch(RuntimeException malformed){if(!retainMalformed)throw malformed;parsed=json.createObjectNode();}
            if(path.startsWith("/orders/v0/")){parsed=OrderPiiRedactor.redact(parsed);return new ApiResponse(response.statusCode(),requestId,parsed,parsed.toString());}
            return new ApiResponse(response.statusCode(), requestId, parsed, response.body(),rateLimit(response));
        } catch (VariationDeferredException ex){throw ex;} catch (AmazonApiException ex) { throw ex; }
        catch (IllegalStateException ex) {
            // Credential decryption/configuration happens before the HTTP request.
            // Preserve its safe, actionable message instead of mislabelling it as a
            // network failure; never log or expose credential values.
            log.warn("Amazon request could not start — {}", ex.getMessage());
            throw ex;
        }
        catch (HttpTimeoutException ex) { throw new AmazonTransportException("Amazon did not answer before the request timed out.",true,ex); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AmazonTransportException("The Amazon request was interrupted.",true,ex); }
        catch (Exception ex) { throw new AmazonTransportException("Amazon could not be reached.",false,path.startsWith("/orders/")?null:ex); }
    }

    static boolean readOnlyOperation(String method,String path){
        if("GET".equals(method))return true;
        if(!"POST".equals(method))return false;
        return "/reports/2021-06-30/reports".equals(path)
            || path.matches("/products/fees/v0/listings/[^/]+/feesEstimate")
            || "/batches/products/pricing/v0/itemOffers".equals(path)
            || "/mfn/v0/eligibleShippingServices".equals(path);
    }

    private String accessToken(UUID tenantId, UUID connectionId) throws Exception {return accessToken(tenantId,connectionId,null);}
    private String accessToken(UUID tenantId, UUID connectionId,java.util.function.BooleanSupplier permit) throws Exception {
        TokenKey key=new TokenKey(tenantId,connectionId);CachedToken cached=accessTokens.get(key);
        if(cached!=null&&cached.expiresAt().isAfter(Instant.now().plusSeconds(60)))return cached.value();
        synchronized(accessTokens){
            cached=accessTokens.get(key);
            if(cached!=null&&cached.expiresAt().isAfter(Instant.now().plusSeconds(60)))return cached.value();
            Map<String, String> c = credentialService.credentials(tenantId, connectionId);
            String form = "grant_type=refresh_token&refresh_token=" + encode(c.get("refreshToken"))
                + "&client_id=" + encode(c.get("lwaClientId")) + "&client_secret=" + encode(c.get("lwaClientSecret"));
            if(permit instanceof RequestPermit p&&!p.oauth())throw new VariationDeferredException();
            HttpResponse<String> response = dispatch(HttpRequest.newBuilder(LWA).timeout(timeout(permit))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),permit);
            if (response.statusCode() / 100 != 2) throw new AmazonApiException(response.statusCode(), null,
                "Amazon authorization needs attention.");
            JsonNode token=json.readTree(response.body());String value=token.path("access_token").asText();
            long lifetime=Math.max(120,token.path("expires_in").asLong(3600));
            accessTokens.put(key,new CachedToken(value,Instant.now().plusSeconds(lifetime)));
            return value;
        }
    }

    private HttpResponse<String> dispatch(HttpRequest request,java.util.function.BooleanSupplier permit)throws Exception {
        try{var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(permit instanceof RequestPermit p){if(permit instanceof FeePermit){Double rate=rateLimit(response);if(rate!=null)p.observedRate(rate);}p.completed();}return response;}
        catch(java.io.IOException|InterruptedException ex){if(permit instanceof RequestPermit p)p.uncertain();throw ex;}
    }
    static Duration variationRetryAfter(HttpResponse<?> response){
        String value=response.headers().firstValue("Retry-After").orElse(null);if(value==null)return null;
        try{return Duration.ofSeconds(Math.max(1,Long.parseLong(value)));}
        catch(NumberFormatException ignored){try{
            Duration delay=Duration.between(Instant.now(),java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative()?Duration.ofSeconds(1):delay;
        }catch(java.time.DateTimeException malformed){return Duration.ofMinutes(1);}}
    }
    public static final class VariationDeferredException extends RuntimeException {
        public VariationDeferredException(){super("Relationship request deferred before dispatch");}
    }
    /** Raw Listings read evidence is accessible only to the fenced archive writer, never exception text. */
    public static final class FeeReadException extends AmazonApiException {
        private final String body,requestId;private final Double rateLimit;
        FeeReadException(int status,Duration retryAfter,String body,String requestId,Double rateLimit){
            super(status,retryAfter,"Amazon fee estimate failed (HTTP "+status+").");
            this.body=body;this.requestId=requestId;this.rateLimit=rateLimit;
        }
        public String body(){return body;} public String requestId(){return requestId;}
        public Double rateLimit(){return rateLimit;}
    }
    public static final class VariationReadException extends AmazonApiException {
        private final String rawBody,requestId;private final Double rateLimit;
        public VariationReadException(int status,Duration retryAfter,String body,String requestId,Double rateLimit){super(status,retryAfter,"Amazon relationship read failed (HTTP "+status+").");this.rawBody=body;this.requestId=requestId;this.rateLimit=rateLimit;}
        public String rawBody(){return rawBody;}public String requestId(){return requestId;}public Double rateLimit(){return rateLimit;}
    }
    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try { return Duration.ofSeconds(Math.max(1, Long.parseLong(value))); }
            catch (NumberFormatException ignored) { return Duration.ofMinutes(1); }
        }).orElse(null);
    }
    private String safeMessage(int status,String path,String body) {
        if(path.startsWith("/orders/"))return "Amazon order request was not successful (HTTP "+status+").";
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

    private static Double rateLimit(HttpResponse<?> response){
        try{double value=Double.parseDouble(response.headers().firstValue("x-amzn-RateLimit-Limit").orElse(""));return value>0&&Double.isFinite(value)?value:null;}
        catch(NumberFormatException ignored){return null;}
    }
    public record ApiResponse(int status, String requestId, JsonNode json, String body,Double rateLimit) {
        public ApiResponse(int status,String requestId,JsonNode json,String body){this(status,requestId,json,body,null);}
        @Override public String toString(){return "Amazon response [body redacted; status="+status+"]";}
    }
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
