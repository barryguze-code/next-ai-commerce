package com.nextaicommerce.platform.web;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Merchant-owned Dev Dashboard apps only. Fixed reads and separately guarded inventory publication. */
@Service
public class ShopifyConnectionService {
    static final String API_VERSION="2026-10";
    private final WorkspaceAccessRepository repository;
    private final MarketplaceCredentialService crypto;
    private final HttpClient http;
    private final Clock clock;
    private final boolean enabled;
    @Value("${app.shopify.orders.enabled:false}") private boolean ordersEnabled;
    @Value("${app.shopify.inventory.write-enabled:false}") private boolean inventoryWrites;
    @Value("${app.local-development:false}") private boolean localDevelopment;
    private final ConcurrentHashMap<String,ReentrantLock> locks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Instant> retryAfter=new ConcurrentHashMap<>();
    private final ObjectMapper json=new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public ShopifyConnectionService(WorkspaceAccessRepository repository,MarketplaceCredentialService crypto,
            @Value("${app.shopify.connections.enabled:false}") boolean enabled){
        this(repository,crypto,enabled,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build(),Clock.systemUTC());
    }
    ShopifyConnectionService(WorkspaceAccessRepository repository,MarketplaceCredentialService crypto,
            boolean enabled,HttpClient http,Clock clock){
        this.repository=repository;this.crypto=crypto;this.enabled=enabled;this.http=http;this.clock=clock;
    }
    public boolean enabled(){return enabled;}
    public boolean ordersEnabled(){return ordersEnabled;}
    /** Safe local-only setup projection. Rendering never refreshes tokens or calls Shopify. */
    public SetupState setupState(UUID tenant,UUID id){
        var connection=repository.shopifyConnection(tenant,id);
        try{
            var saved=crypto.credentials(tenant,id);
            boolean verified="ACTIVE".equals(connection.status())
                &&shopDomain(connection.shop()).equals(saved.get("shopDomain"))
                &&saved.getOrDefault("shopId","").matches("gid://shopify/Shop/[0-9]+")
                &&Instant.parse(saved.getOrDefault("verifiedAt","")).isBefore(clock.instant().plusSeconds(1))
                &&Instant.parse(saved.getOrDefault("expiresAt","")).isAfter(clock.instant());
            boolean products=Arrays.stream(saved.getOrDefault("scopes","").split("[,\\s]+"))
                .anyMatch("read_products"::equals);
            var scopes=new HashSet<>(Arrays.asList(saved.getOrDefault("scopes","").split("[,\\s]+")));
            boolean orders=scopes.containsAll(Set.of("read_orders","read_merchant_managed_fulfillment_orders"))
                &&scopes.stream().noneMatch(scope->scope.contains("fulfillment_orders")&&!scope.equals("read_merchant_managed_fulfillment_orders"));
            return new SetupState(verified,verified&&products,verified&&products&&orders);
        }catch(IllegalStateException|IllegalArgumentException|DateTimeException ex){return new SetupState(false,false);}
    }
    public record SetupState(boolean verified,boolean productsPermitted,boolean ordersPermitted){
        public SetupState(boolean verified,boolean productsPermitted){this(verified,productsPermitted,false);}
    }
    public static String shopDomain(String input){
        String shop=input==null?"":input.trim().toLowerCase(Locale.ROOT);
        if(!shop.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.myshopify\\.com"))
            throw new IllegalArgumentException("Enter the store's exact your-store.myshopify.com domain, without a path or custom website address.");
        return shop;
    }
    public void connect(UUID tenant,UUID id,String clientId,String secret,Runnable authorize){
        if(clientId==null||clientId.isBlank()||secret==null||secret.isBlank())
            throw new IllegalArgumentException("Enter the app Client ID and Client secret from this store's Shopify organization.");
        execute(tenant,id,new LinkedHashMap<>(Map.of("clientId",clientId.trim(),"clientSecret",secret.trim())),true,authorize);
    }
    public void verify(UUID tenant,UUID id,Runnable authorize){execute(tenant,id,null,false,authorize);}
    /** Explicit human permission refresh; never invoked by rendering or a catalogue worker. */
    public void refreshPermissions(UUID tenant,UUID id,Runnable authorize){
        authorize.run();var saved=new LinkedHashMap<>(crypto.credentials(tenant,id));saved.remove("accessToken");
        execute(tenant,id,saved,false,authorize);
    }
    /** Renew the already-approved read grant at a job boundary, using the existing token/identity verification. */
    public void renewOrdersAuthorization(UUID tenant,UUID id,Runnable authorize){
        if(!enabled||!ordersEnabled)throw new IllegalStateException("Shopify Orders sync is disabled.");
        authorize.run();var saved=crypto.credentials(tenant,id);
        var scopes=new HashSet<>(Arrays.asList(saved.getOrDefault("scopes","").split("[,\\s]+")));
        if(!scopes.containsAll(Set.of("read_products","read_orders","read_merchant_managed_fulfillment_orders"))
            ||scopes.stream().anyMatch(scope->scope.contains("fulfillment_orders")&&!scope.equals("read_merchant_managed_fulfillment_orders")))
            throw new AuthorizationRejected("Approve and verify the reviewed Shopify Orders read permissions first.");
        if(!Instant.parse(saved.getOrDefault("expiresAt","")).isAfter(clock.instant().plusSeconds(4500))){
            var refreshed=new LinkedHashMap<>(saved);refreshed.remove("accessToken");execute(tenant,id,refreshed,false,authorize);
        }
    }
    public record CatalogResponse(String body,String shopId,String currency,Instant connectionRevision){}
    /** Read-products transport using the existing client, exact host, token and rate safeguards. */
    public CatalogResponse readCatalog(UUID tenant,UUID id,String body,Runnable reserve){
        return read(tenant,id,body,reserve,false);
    }
    /** Orders reads require the reviewed minimal scope set; broader fulfillment scopes invalidate ownership assumptions. */
    public CatalogResponse readOrders(UUID tenant,UUID id,String body,Runnable reserve){
        if(!ordersEnabled)throw new IllegalStateException("Shopify Orders sync is not enabled.");
        if(!com.nextaicommerce.platform.shopify.ShopifyOrderClient.QUERY.equals(json.readTree(body).path("query").asText()))
            throw new IllegalArgumentException("Only the reviewed read-only Shopify Orders query is permitted.");
        return read(tenant,id,body,reserve,true);
    }
    /** Separately allowlisted user-initiated shipping read; ordinary sync query stays unchanged. */
    public CatalogResponse readOrderRecipient(UUID tenant,UUID id,String body){
        if(!ordersEnabled||!com.nextaicommerce.platform.shopify.ShopifyOrderClient.RECIPIENT_QUERY.equals(json.readTree(body).path("query").asText()))
            throw new IllegalArgumentException("Only the reviewed shipping recipient read is permitted.");
        // This read is reached only through an authorized shipping operation. Renew the
        // existing grant without the setup controller's catalogue/order initialization.
        renewOrdersAuthorization(tenant,id,()->{});
        return read(tenant,id,body,()->{},true);
    }
    public CatalogResponse inventory(UUID tenant,UUID id,String expectedShop,String body,Runnable reserve,boolean write){
        if(write&&(!inventoryWrites||localDevelopment))throw new IllegalStateException("Shopify inventory writes are disabled");
        String query=json.readTree(body).path("query").asText();
        if(!(write?com.nextaicommerce.platform.shopify.ShopifyInventoryClient.WRITE:com.nextaicommerce.platform.shopify.ShopifyInventoryClient.READ).equals(query))
            throw new IllegalArgumentException("Only the reviewed inventory operation is permitted");
        if(expectedShop==null||!expectedShop.matches("gid://shopify/Shop/[0-9]+"))throw new IllegalArgumentException("Verified shop binding required");
        return read(tenant,id,body,reserve,false,expectedShop,write);
    }
    private CatalogResponse read(UUID tenant,UUID id,String body,Runnable reserve,boolean orders){
        return read(tenant,id,body,reserve,orders,null,false);
    }
    private CatalogResponse read(UUID tenant,UUID id,String body,Runnable reserve,boolean orders,String expectedShop,boolean inventoryWrite){
        if(!enabled)throw new IllegalStateException("Shopify setup is not enabled.");
        var lock=locks.computeIfAbsent(tenant+":"+id,k->new ReentrantLock());boolean acquired=false;
        try{
            acquired=lock.tryLock(1,TimeUnit.SECONDS);if(!acquired)throw new IllegalStateException("Shopify authorization is busy. Retry shortly.");
            var connection=repository.shopifyConnection(tenant,id);var saved=crypto.credentials(tenant,id);
            if(expectedShop!=null){
                var inventoryScopes=new HashSet<>(Arrays.asList(saved.getOrDefault("scopes","").split("[,\\s]+")));
                if(!expectedShop.equals(saved.get("shopId"))||!inventoryScopes.contains("read_locations")
                    ||(!inventoryScopes.contains("read_inventory")&&!inventoryScopes.contains("write_inventory"))
                    ||(inventoryWrite&&!inventoryScopes.contains("write_inventory")))
                    throw new AuthorizationRejected("Verify shop identity and required inventory/location permissions before publication");
            }
            if(orders){
                var scopes=new HashSet<>(Arrays.asList(saved.getOrDefault("scopes","").split("[,\\s]+")));
                if(!scopes.containsAll(Set.of("read_orders","read_merchant_managed_fulfillment_orders"))
                    ||scopes.stream().anyMatch(scope->scope.contains("fulfillment_orders")&&!scope.equals("read_merchant_managed_fulfillment_orders")))
                    throw new AuthorizationRejected("Verify the approved Orders and merchant-managed fulfillment read permissions before syncing. Existing data was kept.");
            }
            Instant pause=retryAfter.getOrDefault(tenant+":"+id,Instant.MIN);if(clock.instant().isBefore(pause))throw new RateLimited(pause);
            if(!"ACTIVE".equals(connection.status())||saved.getOrDefault("accessToken","").isBlank()||!shopDomain(connection.shop()).equals(saved.get("shopDomain"))
                ||!saved.getOrDefault("shopId","").matches("gid://shopify/Shop/[0-9]+")
                ||!Instant.parse(saved.getOrDefault("expiresAt","")).isAfter(clock.instant().plusSeconds(30))
                ||Arrays.stream(saved.getOrDefault("scopes","").split("[,\\s]+")).noneMatch("read_products"::equals))
                throw new AuthorizationRejected("Verify saved authorization with product-read permission before syncing. Existing data was kept.");
            reserve.run();
            return new CatalogResponse(request(tenant+":"+id,connection.shop(),"/admin/api/"+API_VERSION+"/graphql.json",body,saved.get("accessToken")),saved.get("shopId"),saved.get("currency"),connection.updatedAt());
        }catch(DateTimeException ex){throw new AuthorizationRejected("Saved authorization needs verification. Existing data was kept.");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Shopify read interrupted.");}
        finally{if(acquired)lock.unlock();}
    }
    private void execute(UUID tenant,UUID id,Map<String,String> supplied,boolean replace,Runnable authorize){
        if(!enabled)throw new IllegalStateException("Shopify connection setup is not enabled in this environment yet.");
        authorize.run();
        String key=tenant+":"+id;
        if(clock.instant().isBefore(retryAfter.getOrDefault(key,Instant.MIN)))throw new IllegalStateException("Shopify requested a pause. Please wait before verifying this store again.");
        var lock=locks.computeIfAbsent(key,k->new ReentrantLock());boolean acquired=false;
        WorkspaceAccessRepository.ShopifyConnection connection=null;
        try{
            acquired=lock.tryLock(1,TimeUnit.SECONDS);
            if(!acquired)throw new IllegalStateException("This store is already being verified. Please wait and try again.");
            if(clock.instant().isBefore(retryAfter.getOrDefault(key,Instant.MIN)))throw new IllegalStateException("Shopify requested a pause. Please wait before verifying this store again.");
            connection=repository.shopifyConnection(tenant,id);
            if("DISABLED".equals(connection.status()))throw new IllegalArgumentException("This store was disconnected. Add it again before connecting.");
            String shop=shopDomain(connection.shop());
            var values=supplied==null?new LinkedHashMap<>(crypto.credentials(tenant,id)):supplied;
            if(!replace&&!shop.equals(values.get("shopDomain")))throw new IllegalArgumentException("Saved authorization does not match this store. Reconnect it.");
            if(values.getOrDefault("clientId","").isBlank()||values.getOrDefault("clientSecret","").isBlank())throw new IllegalStateException("Saved Shopify app credentials are incomplete. Reconnect this store.");
            Instant now=clock.instant();
            boolean valid=false;
            try{valid=Instant.parse(values.getOrDefault("expiresAt","")).isAfter(now.plusSeconds(60));}catch(DateTimeException ignored){}
            if(replace||!valid||values.getOrDefault("accessToken","").isBlank()){
                var response=request(key,shop,"/admin/oauth/access_token",MarketplaceCredentialService.form(Map.of(
                    "grant_type","client_credentials","client_id",values.get("clientId"),"client_secret",values.get("clientSecret"))),null);
                var body=json.readTree(response);
                String token=body.path("access_token").asText("");long lifetime=body.path("expires_in").asLong(0);
                if(token.isBlank()||lifetime<=0||lifetime>86400)throw new IllegalArgumentException("Shopify did not return a valid expiring token. Check the app setup.");
                values.put("accessToken",token);values.put("expiresAt",now.plusSeconds(lifetime).toString());
                values.put("scopes",body.path("scope").asText(""));
            }
            var identity=json.readTree(request(key,shop,"/admin/api/"+API_VERSION+"/graphql.json",
                "{\"query\":\"query ConnectionIdentity { shop { id name myshopifyDomain ianaTimezone currencyCode } }\"}",values.get("accessToken")));
            if(identity.has("errors"))throw new IllegalArgumentException("Shopify could not verify store identity. Review the app's installed permissions and try again.");
            var verified=identity.path("data").path("shop");String gid=verified.path("id").asText("");
            if(!shop.equals(shopDomain(verified.path("myshopifyDomain").asText("")))||!gid.matches("gid://shopify/Shop/[0-9]+"))
                throw new IllegalArgumentException("Shopify returned a different store identity. No authorization was saved.");
            if(repository.loadCredentials(tenant,id)!=null){
                String priorId=crypto.credentials(tenant,id).get("shopId");
                if(priorId!=null&&!gid.equals(priorId))throw new IllegalArgumentException("This domain now identifies a different Shopify store. Existing authorization and history were retained; contact your administrator.");
            }
            String zone=verified.path("ianaTimezone").asText("");ZoneId.of(zone);
            String currency=verified.path("currencyCode").asText("");Currency.getInstance(currency);
            values.put("shopDomain",shop);values.put("shopId",gid);values.put("shopName",verified.path("name").asText(""));
            values.put("timezone",zone);values.put("currency",currency);values.put("verifiedAt",clock.instant().toString());values.put("apiVersion",API_VERSION);
            var envelope=crypto.encrypt(values);
            repository.publishShopifyCredentials(tenant,id,connection.updatedAt(),envelope.payload(),envelope.nonce(),zone,authorize);
        }catch(AuthorizationRejected ex){
            if(!replace&&connection!=null)repository.markShopifyVerificationRequired(tenant,id,connection.updatedAt());
            throw ex;
        }catch(DateTimeException ex){throw new IllegalArgumentException("Shopify returned incomplete store settings. Existing authorization was retained.");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Shopify verification was interrupted. Existing authorization was retained.");}
        finally{if(acquired)lock.unlock();}
    }
    public static class AuthorizationRejected extends IllegalArgumentException { AuthorizationRejected(String message){super(message);} }
    public static class RateLimited extends IllegalArgumentException {
        private final Instant retryAt;
        RateLimited(Instant retryAt){super("Shopify requested a rate-limit pause. Existing data was kept.");this.retryAt=retryAt;}
        public Instant retryAt(){return retryAt;}
    }
    private String request(String key,String shop,String path,String body,String token){
        try{
            var builder=HttpRequest.newBuilder(URI.create("https://"+shop+path)).timeout(Duration.ofSeconds(15))
                .header("Accept","application/json").header("Content-Type",token==null?"application/x-www-form-urlencoded":"application/json");
            if(token!=null)builder.header("X-Shopify-Access-Token",token);
            var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==429){
                String value=response.headers().firstValue("Retry-After").orElse("60");Instant retry;
                try{retry=clock.instant().plusSeconds(Math.max(1,Long.parseLong(value)));}
                catch(RuntimeException ex){try{retry=ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException invalid){retry=clock.instant().plusSeconds(60);}}
                retryAfter.put(key,retry.isAfter(clock.instant())?retry:clock.instant().plusSeconds(1));
                throw new RateLimited(retryAfter.get(key));
            }
            if(response.statusCode()==401||response.statusCode()==403)throw new AuthorizationRejected("Shopify rejected this app or its permissions. Confirm the app and store share a Shopify organization, release and install the app there, then reconnect. Existing authorization was retained.");
            if(response.statusCode()>=500)throw new IllegalStateException("Shopify is temporarily unavailable. Existing data was kept.");
            if(response.statusCode()/100!=2)throw new IllegalArgumentException("Shopify could not verify the connection. Try again later. Existing authorization was retained.");
            if(response.body()==null||response.body().length()>4_000_000)throw new IllegalArgumentException("Shopify response exceeded the safe page size. Existing data was kept.");
            tools.jackson.databind.JsonNode parsed;
            try{parsed=json.readTree(response.body());}catch(RuntimeException ex){throw new IllegalArgumentException("Shopify returned an unreadable response. Existing authorization was retained.");}
            for(var error:parsed.path("errors"))if("THROTTLED".equals(error.path("extensions").path("code").asText(""))){
                var cost=parsed.path("extensions").path("cost");double rate=cost.path("throttleStatus").path("restoreRate").asDouble(0);
                double missing=cost.path("requestedQueryCost").asDouble(0)-cost.path("throttleStatus").path("currentlyAvailable").asDouble(0);
                long seconds=rate>0&&missing>0?(long)Math.ceil(missing/rate):60;
                retryAfter.put(key,clock.instant().plusSeconds(Math.max(1,seconds)));
                throw new RateLimited(retryAfter.get(key));
            }
            return response.body();
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Shopify verification was interrupted. Existing authorization was retained.");}
        catch(java.io.IOException ex){throw new IllegalStateException("Shopify could not be reached. Try again later. Existing authorization was retained.");}
    }
}
