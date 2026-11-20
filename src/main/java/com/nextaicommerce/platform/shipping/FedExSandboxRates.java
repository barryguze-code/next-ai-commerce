package com.nextaicommerce.platform.shipping;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Explicit local public-fixture rate connection. No shipment or database capability. */
@Component
public class FedExSandboxRates {
    private final ObjectMapper json;
    private final String account;
    private final FedExClient client;
    private final boolean labels;
    private final FedExClient.Credentials credentials;
    private final Cache<String,Instant> requested=Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofSeconds(5)).build();
    @Autowired public FedExSandboxRates(ObjectMapper json,Environment env) {this(json,env,null);}
    FedExSandboxRates(ObjectMapper json,Environment env,FedExClient.Transport testTransport) {
        this.json=json;labels=env.getProperty("app.fedex.local-shipping-uat.enabled",Boolean.class,false);
        if(!env.getProperty("app.fedex.sandbox-rate-only.enabled",Boolean.class,false)){account=null;client=null;credentials=null;return;}
        if(env.getActiveProfiles().length!=1||!env.acceptsProfiles(Profiles.of("local","fee-release-uat"))
            ||!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://127[.]0[.]0[.]1:55432/[a-z0-9_]+")
            ||!env.getProperty("app.fedex.simulation-enabled",Boolean.class,false)
            ||!"SANDBOX".equals(env.getProperty("app.fedex.platform-environment","SANDBOX")))
            throw new IllegalStateException("Rate-only testing requires the approved local sandbox simulation.");
        for(String key:List.of("app.fedex.external-enabled","app.fedex.confirmation-enabled","app.amazon.write-enabled",
            "app.amazon.inventory-publication-enabled","app.amazon.buy-shipping-enabled","app.shopify.inventory.write-enabled",
            "app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled"))
            if(!"false".equals(env.getProperty(key,"false")))throw new IllegalStateException("Rate-only testing requires external operations OFF: "+key);
        account=env.getProperty("app.fedex.sandbox-rate-only.account-number","");
        if(!account.matches("[0-9]{9}"))throw new IllegalStateException("Configure the separate public sandbox rate-test account.");
        credentials=new FedExClient.Credentials(env.getProperty("FEDEX_CLIENT_ID"),env.getProperty("FEDEX_CLIENT_SECRET"));
        var transport=testTransport==null?FedExClient.defaultTransport():testTransport;
        client=new FedExClient(json,true,request->{
            if(!"https".equals(request.uri().getScheme())||!"apis-sandbox.fedex.com".equals(request.uri().getHost())
                ||!"POST".equals(request.method())||!(List.of("/oauth/token","/rate/v1/comprehensiverates/quotes").contains(request.uri().getPath())||(labels&&"/ship/v1/shipments".equals(request.uri().getPath()))))
                throw new IllegalStateException("Sandbox rate-only endpoint rejected.");
            return transport.send(request);
        },FedExClient.RateApi.COMPREHENSIVE);
    }
    public String account(){return account;}
    public record Quote(List<FedExPayloads.Rate> rates,boolean rateOnly,String environment,String route,UUID shipmentId) {public Quote(List<FedExPayloads.Rate> rates,boolean rateOnly,String environment,String route){this(rates,rateOnly,environment,route,null);}}
    public FedExPayloads.Label ship(UUID tenant,UUID store,tools.jackson.databind.node.ObjectNode payload){
        if(!labels||account==null)throw new IllegalStateException("Local sandbox labels are disabled.");
        if(!account.equals(payload.path("accountNumber").path("value").asText()))throw new IllegalArgumentException("Sandbox account changed.");
        UUID connection=UUID.nameUUIDFromBytes(("sandbox-label:"+tenant+":"+store).getBytes(StandardCharsets.UTF_8));
        return FedExPayloads.label(client.ship(tenant,connection,1,FedExClient.Environment.SANDBOX,credentials,payload));
    }
    public Quote quote(UUID tenant,UUID store,FedExPayloads.Address from,FedExPayloads.Address to,FedExPayloads.Parcel parcel) {
        if(account==null)throw new IllegalStateException("Sandbox rate-only testing is disabled.");
        java.util.Objects.requireNonNull(tenant);java.util.Objects.requireNonNull(store);
        parcel=FedExPackaging.normalize(parcel);FedExPayloads.validate(parcel);
        FedExPayloads.validate(from);FedExPayloads.validate(to);
        String scope=tenant+":"+store;
        if(requested.asMap().putIfAbsent(scope,Instant.now())!=null)throw new IllegalStateException("Wait five seconds before requesting another sandbox quote.");
        var payload=FedExPayloads.rateRequest(json,account,from,to,parcel);
        var shipment=(tools.jackson.databind.node.ObjectNode)payload.path("requestedShipment");
        for(String party:List.of("shipper","recipient")){var value=(tools.jackson.databind.node.ObjectNode)shipment.path(party);value.remove("contact");((tools.jackson.databind.node.ObjectNode)value.path("address")).remove(List.of("streetLines","city"));}
        UUID connection=UUID.nameUUIDFromBytes(("sandbox-rate-only:"+scope).getBytes(StandardCharsets.UTF_8));
        var rates=FedExPayloads.rates(client.sandboxParentRates(tenant,connection,credentials,payload));
        return new Quote(rates,true,"SANDBOX","Sandbox quote "+from.postalCode()+" → "+to.postalCode()+"; no label can be created.");
    }
}
