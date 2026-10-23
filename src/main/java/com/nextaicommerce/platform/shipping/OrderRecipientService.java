package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderRecipientService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderRecipientService.class);
    private final OrderRecipientRepository repository;private final ShippingLabelCrypto crypto;private final ObjectMapper json;private final AmazonSpApiClient amazon;private final boolean enabled;
    public OrderRecipientService(OrderRecipientRepository repository,ShippingLabelCrypto crypto,ObjectMapper json,AmazonSpApiClient amazon,@Value("${app.amazon.recipient-import-enabled:false}") boolean enabled){this.repository=repository;this.crypto=crypto;this.json=json;this.amazon=amazon;this.enabled=enabled;}
    public record View(String status,OrderRecipient recipient) { @Override public String toString(){return "Recipient view [REDACTED]";} }
    public void enqueue(UUID tenant,UUID store,Collection<String> orders){
        if(!enabled||!crypto.configured())return;
        try{repository.enqueue(tenant,store,orders);}catch(RuntimeException ex){log.warn("Recipient queue unavailable; ordinary order import continues.");}
    }
    public View get(UUID tenant,UUID store,String order){
        if(!enabled)return new View("DISABLED",null);
        if(!crypto.configured())return new View("ENCRYPTION_UNAVAILABLE",null);
        var stored=repository.read(tenant,store,order);
        if(stored==null){enqueue(tenant,store,List.of(order));return new View("NOT_AVAILABLE",null);}
        if(stored.payload()==null){if("EXPIRED".equals(stored.status()))enqueue(tenant,store,List.of(order));return new View(stored.status(),null);}
        byte[] plain=null;
        try{plain=crypto.decrypt(stored.payload(),stored.nonce(),context(tenant,store,order));return new View(stored.status(),json.readValue(plain,OrderRecipient.class));}
        catch(RuntimeException ex){return new View("UNAVAILABLE",null);}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    public void enqueueWindow(UUID tenant,UUID store,java.time.Instant start,java.time.Instant end){
        if(!enabled||!crypto.configured()||start==null||end==null)return;
        try{repository.enqueueWindow(tenant,store,start,end);}catch(RuntimeException ex){log.warn("Recipient report queue unavailable; ordinary order import continues.");}
    }
    public void process(UUID tenant){
        if(!enabled||!crypto.configured())return;
        var command=repository.claim(tenant);if(command==null)return;
        try {
            var recipient=amazon.recipient(tenant,command.store(),AmazonMerchantFulfillmentClient.endpoint(command.marketplace()),command.order());
            byte[] plain=json.writeValueAsBytes(recipient);
            try{repository.complete(command,recipient.complete()?"AVAILABLE":"INCOMPLETE",crypto.encrypt(plain,context(tenant,command.store(),command.order())));}
            finally{Arrays.fill(plain,(byte)0);}
            if(!recipient.complete())log.warn("Amazon recipient fields are incomplete or suppressed; no replacement values were created.");
        }catch(AmazonSpApiClient.AmazonApiException ex){repository.failed(command,ex.status()==401||ex.status()==403);log.warn("Amazon recipient fetch deferred; HTTP status {}. Customer values are not logged.",ex.status());}
        catch(RuntimeException ex){repository.failed(command,false);log.warn("Recipient processing deferred; customer values and exception details are suppressed.");}
    }
    public FedExPayloads.Address forShipping(UUID tenant,UUID store,String order){
        var view=get(tenant,store,order);var r=view.recipient();
        if(!"AVAILABLE".equals(view.status())||r==null||!r.complete())throw new IllegalStateException("Amazon recipient details are unavailable or incomplete. Review recipient access before buying a label.");
        if(!List.of("RESIDENTIAL","COMMERCIAL").contains(r.addressType()))throw new IllegalStateException("The delivery address type needs review before shipping.");
        return new FedExPayloads.Address(r.name(),r.phone(),r.street(),r.city(),r.state(),r.postalCode(),r.country(),"RESIDENTIAL".equals(r.addressType()),r.street2(),r.street3());
    }
    static byte[] context(UUID tenant,UUID store,String order){return ("order-recipient:v1:"+tenant+":"+store+":"+order).getBytes(StandardCharsets.UTF_8);}
}
