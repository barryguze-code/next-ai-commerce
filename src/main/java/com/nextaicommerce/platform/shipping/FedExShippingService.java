package com.nextaicommerce.platform.shipping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class FedExShippingService {
    private final BuyShippingService orders;
    private final CarrierConnectionRepository connections;
    private final CarrierConnectionService credentials;
    private final FedExShipmentRepository repository;
    private final FedExClient fedex;
    private final ShippingLabelCrypto crypto;
    private final CarrierLabelComposer composer;
    private final ObjectMapper json;
    private final CarrierShipmentConfirmation confirmation;
    private final ShippingPreferences preferences;
    private final OrderRecipientService recipients;
    public FedExShippingService(BuyShippingService orders,CarrierConnectionRepository connections,CarrierConnectionService credentials,
        FedExShipmentRepository repository,FedExClient fedex,ShippingLabelCrypto crypto,CarrierLabelComposer composer,ObjectMapper json,CarrierShipmentConfirmation confirmation,ShippingPreferences preferences,OrderRecipientService recipients) {
        this.orders=orders;this.connections=connections;this.credentials=credentials;this.repository=repository;this.fedex=fedex;this.crypto=crypto;this.composer=composer;this.json=json;
        this.confirmation=confirmation;
        this.preferences=preferences;
        this.recipients=recipients;
    }
    public record Context(List<CarrierConnectionRepository.Connection> carriers,List<BuyShippingRepository.Address> senders,
        FedExPayloads.Parcel dimensions,List<FedExShipmentRepository.Shipment> shipments,ShippingPreferences.Settings preferences,OrderRecipientService.View recipient,List<FedExPackaging.Option> packagingOptions,String packagingNotice) {}
    public record QuoteInput(UUID carrierId,UUID senderId,FedExPayloads.Address recipient,FedExPayloads.Parcel parcel,boolean saveDimensions,boolean testRecipient) { @Override public String toString(){return "FedEx quote input [REDACTED]";} }
    public record Quote(UUID shipmentId,List<FedExPayloads.Rate> rates,String environment) {}
    public Context context(UUID tenant,UUID store,String order) {
        var context=orders.context(tenant,store,order);eligible(context);
        var parcel=repository.orderPackage(tenant,store,order,key(context));String notice="";
        if(parcel==null)parcel=repository.dimensions(tenant,store,key(context));
        // Legacy SKU defaults are safe only for one unit; never apply a first-SKU box to a bundle.
        if(parcel==null&&composition(context).size()==1&&composition(context).values().iterator().next()==1){
            parcel=FedExPackaging.profile(context.suggestedPackage());
            if(parcel!=null)notice="Converted saved package. Review packaging and actual packed weight before saving.";
        }
        if(parcel==null){
            String legacy=repository.legacyPackaging(tenant,store,legacySignature(context));
            String id=FedExPackaging.legacy(legacy);
            if(id!=null){parcel=FedExPackaging.normalize(new FedExPayloads.Parcel(null,null,null,null,id));notice="Suggested from matching packaging history. Enter actual packed weight and review before saving.";}
            else if(legacy!=null)notice="Previous packaging: "+legacy+". Choose a physical packaging variant and enter packed weight.";
        }
        return new Context(connections.list(tenant),context.addresses(),parcel,repository.list(tenant,store,order),preferences.get(tenant,store),recipients.get(tenant,store,order),FedExPackaging.OPTIONS,notice);
    }
    public Quote quote(UUID tenant,UUID store,String order,QuoteInput input,String actor) {
        if(!crypto.configured())throw new IllegalStateException("Configure credential encryption before using FedEx.");
        var context=orders.context(tenant,store,order);eligible(context);
        var carrier=connections.find(tenant,input.carrierId());sandbox(carrier.environment());
        var sender=context.addresses().stream().filter(a->a.id().equals(input.senderId())).findFirst().orElseThrow(()->new IllegalArgumentException("Choose a saved ship-from address. Add one in Amazon Buy Shipping first."));
        var from=new FedExPayloads.Address(sender.contactName(),sender.phone(),sender.line1(),sender.city(),sender.state(),sender.postalCode(),sender.countryCode(),false);
        // Imported customer data is never silently submitted to a sandbox carrier.
        var recipient=recipientForCarrier(tenant,store,order,carrier.environment(),input);
        var parcel=FedExPackaging.normalize(input.parcel());
        ObjectNode payload=FedExPayloads.rateRequest(json,carrier.accountNumber(),from,recipient,parcel);
        var rates=FedExPayloads.rates(fedex.rates(tenant,carrier.id(),carrier.revision(),FedExClient.Environment.SANDBOX,credentials.credentials(tenant,carrier),payload));
        if(rates.isEmpty())throw new IllegalStateException("FedEx returned no account rates for this package and route.");
        repository.orderPackage(tenant,store,order,key(context),parcel);
        if(input.saveDimensions())repository.dimensions(tenant,store,key(context),parcel);
        UUID id=repository.quote(tenant,store,order,carrier,crypto.encrypt(payload.toString().getBytes(StandardCharsets.UTF_8)),rates,repository.packing(tenant,store,order),actor);
        return new Quote(id,rates,carrier.environment());
    }
    public void savePackage(UUID tenant,UUID store,String order,FedExPayloads.Parcel parcel,boolean reuse){
        parcel=FedExPackaging.normalize(parcel);FedExPayloads.validate(parcel);var context=orders.context(tenant,store,order);eligible(context);
        // Compatibility projection for the existing Amazon queue; no free-form profile authoring here.
        orders.savePackage(tenant,store,order,new BuyShippingService.PackageInput(null,FedExPackaging.option(parcel.packagingId()).label(),null,parcel.length(),parcel.width(),parcel.height(),"inches",parcel.weight().multiply(new java.math.BigDecimal("16")),"oz","FedEx","AMBIENT",false,context.items().stream().filter(i->i.remaining()>0).map(i->new BuyShippingService.ItemQuantity(i.orderItemId(),i.remaining())).toList()));
        repository.orderPackage(tenant,store,order,key(context),parcel);
        if(reuse)repository.dimensions(tenant,store,key(context),parcel);
    }
    FedExPayloads.Address recipientForCarrier(UUID tenant,UUID store,String order,String environment,QuoteInput input){
        if("SANDBOX".equals(environment)) {
            if(!input.testRecipient())throw new IllegalArgumentException("For sandbox rates, choose a manually entered test recipient. Imported customer details are for live shipping only.");
            return input.recipient();
        }
        // Never trust browser-submitted customer addresses for live shipment creation.
        return recipients.forShipping(tenant,store,order);
    }
    public FedExShipmentRepository.Shipment purchase(UUID tenant,UUID store,String order,UUID quote,String service) {
        var context=orders.context(tenant,store,order);eligible(context);
        var shipment=find(tenant,store,order,quote);sandbox(shipment.environment());
        if("PURCHASED".equals(shipment.state()))return shipment;
        var carrier=connections.find(tenant,shipment.carrierId());sandbox(carrier.environment());
        var keys=credentials.credentials(tenant,carrier);
        var request=repository.claim(tenant,store,order,quote,service,carrier.revision());
        if(request==null)throw new IllegalStateException("Rate expired, connection changed, or purchase already started. Refresh shipment history before trying again.");
        try {
            var payload=FedExPayloads.shipRequest((ObjectNode)json.readTree(crypto.decrypt(request.payload(),request.nonce())),service);
            var response=fedex.ship(tenant,carrier.id(),carrier.revision(),FedExClient.Environment.SANDBOX,keys,payload);
            var label=FedExPayloads.label(response);
            repository.purchased(tenant,quote,label.trackingNumber(),crypto.encrypt(label.pdf()),confirmation.payload(context,label.trackingNumber(),service,quote));
        }catch(FedExClient.CarrierException ex){repository.failure(tenant,quote,ex.uncertain());throw ex;}
        catch(RuntimeException ex){repository.failure(tenant,quote,true);throw new IllegalStateException("Shipment needs review. Do not buy another label until its status is confirmed.");}
        // Sandbox tracking MUST NOT reach Amazon's production confirmShipment endpoint.
        return find(tenant,store,order,quote);
    }
    public byte[] label(UUID tenant,UUID store,String order,UUID id,CarrierLabelComposer.Layout layout) {
        var shipment=find(tenant,store,order,id);var stored=repository.label(tenant,store,id);
        if(stored==null)throw new IllegalStateException("Label not ready. Refresh this order's shipment history.");
        List<CarrierLabelComposer.Line> lines=json.readValue(shipment.packing().toString(),json.getTypeFactory().constructCollectionType(List.class,CarrierLabelComposer.Line.class));
        var settings=preferences.get(tenant,store);
        byte[] result=composer.compose(crypto.decrypt(stored.payload(),stored.nonce()),order,lines,layout==null?settings.layout():layout,CarrierConnectionService.environment(shipment.environment()));
        return "LETTER".equals(settings.labelSize())?composer.letter(result):result;
    }
    private FedExShipmentRepository.Shipment find(UUID tenant,UUID store,String order,UUID id){return repository.list(tenant,store,order).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Shipment not found in this store and order."));}
    private static void sandbox(String environment){if(!"SANDBOX".equals(environment))throw new IllegalStateException("Live FedEx shipping is not activated. Use a sandbox connection for this release.");}
    private static void eligible(BuyShippingService.Context c){
        if(!List.of("Pending","Unshipped","PartiallyShipped").contains(c.order().status())||!List.of("MFN","MERCHANT","FBM").contains(c.order().fulfillmentChannel()==null?"":c.order().fulfillmentChannel().toUpperCase()))throw new IllegalStateException("Choose an unshipped, merchant-fulfilled order.");
        if(c.items().isEmpty()||c.items().stream().noneMatch(i->i.remaining()>0))throw new IllegalStateException("This order has no remaining items.");
        if(!c.committedQuantities().isEmpty())throw new IllegalStateException("This order already has committed shipping quantities. Review existing shipments first.");
    }
    private static java.util.SortedMap<String,Integer> composition(BuyShippingService.Context c){var counts=new java.util.TreeMap<String,Integer>();for(var i:c.items())if(i.remaining()>0){if(i.sellerSku()==null||i.sellerSku().isBlank())throw new IllegalArgumentException("Package items must have a SKU.");counts.merge(i.sellerSku(),i.remaining(),Math::addExact);}return counts;}
    static String legacySignature(BuyShippingService.Context c){return composition(c).entrySet().stream().map(e->e.getKey()+"-"+e.getValue()).collect(java.util.stream.Collectors.joining(","));}
    static String key(BuyShippingService.Context c){try{String value=composition(c).entrySet().stream().map(e->e.getKey().length()+":"+e.getKey()+":"+e.getValue()).sorted().reduce("",(a,b)->a+"|"+b);return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("Could not identify this package composition.");}}
}
