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
    @org.springframework.beans.factory.annotation.Autowired private CarrierOrderContext carrierOrders;
    @org.springframework.beans.factory.annotation.Value("${app.fedex.sandbox-label-test-enabled:false}") private boolean labelTest;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private LocalShippingFixtures fixtures;
    @org.springframework.beans.factory.annotation.Value("${app.fedex.simulation-enabled:false}") private boolean simulation;
    @org.springframework.beans.factory.annotation.Autowired private FedExSandboxRates sandboxRates;
    @org.springframework.beans.factory.annotation.Autowired private LocalFedExShippingUat localUat;
    @org.springframework.beans.factory.annotation.Autowired private AmazonFedExSandboxShipping amazonSandbox;
    @org.springframework.beans.factory.annotation.Autowired private com.nextaicommerce.platform.shopify.ShopifyOrderClient shopifyRecipients;
    public FedExShippingService(BuyShippingService orders,CarrierConnectionRepository connections,CarrierConnectionService credentials,
        FedExShipmentRepository repository,FedExClient fedex,ShippingLabelCrypto crypto,CarrierLabelComposer composer,ObjectMapper json,CarrierShipmentConfirmation confirmation,ShippingPreferences preferences,OrderRecipientService recipients) {
        this.orders=orders;this.connections=connections;this.credentials=credentials;this.repository=repository;this.fedex=fedex;this.crypto=crypto;this.composer=composer;this.json=json;
        this.confirmation=confirmation;
        this.preferences=preferences;
        this.recipients=recipients;
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.profit.ProfitCostRepository planningCosts;
    public record PlanningEstimate(String carrier,java.math.BigDecimal amount,String currency) {}
    private PlanningEstimate upsEstimate(UUID tenant){
        if(!simulation||planningCosts==null)return null;
        var amount=com.nextaicommerce.platform.profit.ProfitShippingRates.cost(planningCosts.rates(tenant),com.nextaicommerce.platform.profit.ProfitShippingRates.PackageType.UPS,java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")));
        return amount==null?null:new PlanningEstimate("UPS",amount,"USD");
    }
    public record Context(List<CarrierConnectionRepository.Connection> carriers,List<BuyShippingRepository.Address> senders,
        FedExPayloads.Parcel dimensions,List<FedExShipmentRepository.Shipment> shipments,ShippingPreferences.Settings preferences,OrderRecipientService.View recipient,List<FedExPackaging.Option> packagingOptions,String packagingNotice,boolean simulated,String orderReference,String preferredService,String sandboxRateAccount,boolean rateOnlyOrder,String senderPreferenceKey,PlanningEstimate upsPlanningEstimate) {
        public Context(List<CarrierConnectionRepository.Connection> carriers,List<BuyShippingRepository.Address> senders,FedExPayloads.Parcel dimensions,List<FedExShipmentRepository.Shipment> shipments,ShippingPreferences.Settings preferences,OrderRecipientService.View recipient,List<FedExPackaging.Option> packagingOptions,String packagingNotice,boolean simulated,String orderReference,String preferredService,String sandboxRateAccount,boolean rateOnlyOrder,String senderPreferenceKey){this(carriers,senders,dimensions,shipments,preferences,recipient,packagingOptions,packagingNotice,simulated,orderReference,preferredService,sandboxRateAccount,rateOnlyOrder,senderPreferenceKey,null);}
    }
    public record QuoteInput(UUID carrierId,UUID senderId,FedExPayloads.Address recipient,FedExPayloads.Parcel parcel,boolean saveDimensions,boolean testRecipient) { @Override public String toString(){return "FedEx quote input [REDACTED]";} }
    public record Quote(UUID shipmentId,List<FedExPayloads.Rate> rates,String environment,boolean simulated,String preferredService) {}
    public Context context(UUID tenant,UUID store,String order) {
        if(simulation&&!LocalShippingFixtures.identity(order)&&sandboxRates!=null&&sandboxRates.account()!=null)return importedRateContext(tenant,store,order);
        var context=carrierOrders.context(tenant,store,order);eligible(context);simulationScope(tenant,store,order);
        var parcel=repository.orderPackage(tenant,store,order,key(context));String notice="";
        if(parcel==null)parcel=repository.dimensions(tenant,store,key(context));
        // Legacy SKU defaults are safe only for one unit; never apply a first-SKU box to a bundle.
        if(!simulation&&parcel==null&&composition(context).size()==1&&composition(context).values().iterator().next()==1){
            parcel=FedExPackaging.profile(context.suggestedPackage());
            if(parcel!=null)notice="Converted saved package. Review packaging and actual packed weight before saving.";
        }
        if(!simulation&&parcel==null){
            String legacy=carrierOrders.shopify(tenant,store)?null:repository.legacyPackaging(tenant,store,legacySignature(context));
            String id=FedExPackaging.legacy(legacy);
            if(id!=null){parcel=FedExPackaging.normalize(new FedExPayloads.Parcel(null,null,null,null,id));notice="Suggested from matching packaging history. Enter actual packed weight and review before saving.";}
            else if(legacy!=null)notice="Previous packaging: "+legacy+". Choose a physical packaging variant and enter packed weight.";
        }
        if(carrierOrders.shopify(tenant,store))notice="Sandbox label preview only. Imported history stays reporting-only; this action never marks an order shipped or deducts stock. "+notice;
        if(simulation)notice=parcel==null?"Manual values — enter dimensions and actual packed weight.":"Saved package defaults — review dimensions and weight.";
        var fixture=simulation?fixtures.get(tenant,store,order):null;
        OrderRecipientService.View recipient;
        if(simulation){var a=fixture.definition().recipient();if(a==null)a=new FedExPayloads.Address("Local Test Recipient","9015550100","10 FedEx Parkway","Collierville","TN","38017","US",false);
            recipient=new OrderRecipientService.View("AVAILABLE",new OrderRecipient(a.name(),a.phone(),a.street(),a.street2(),a.street3(),a.city(),a.state(),a.postalCode(),a.country(),a.residential()?"RESIDENTIAL":"COMMERCIAL"));
        }else recipient=labelTest||carrierOrders.shopify(tenant,store)?new OrderRecipientService.View("TEST_RECIPIENT_REQUIRED",null):recipients.get(tenant,store,order);
        return new Context(connections.list(tenant).stream().filter(c->c.simulated()==simulation).toList(),context.addresses(),parcel,repository.list(tenant,store,order),preferences.get(tenant,store),recipient,FedExPackaging.OPTIONS,notice,simulation,fixture==null?order:fixture.name(),fixture==null?null:fixture.definition().preferredService(),sandboxRates==null?null:sandboxRates.account(),false,tenant+":"+store,upsEstimate(tenant));
    }
    public BuyShippingRepository.Address saveSender(UUID tenant,UUID store,String order,BuyShippingRepository.Address input){
        carrierOrders.context(tenant,store,order);
        if(input.id()!=null)throw new IllegalArgumentException("Use the address settings to edit an existing sender.");
        return orders.saveAddress(tenant,store,input);
    }
    public record RateOnlyInput(UUID senderId,FedExPayloads.Parcel parcel,FedExPayloads.Address recipient) { @Override public String toString(){return "Sandbox rate input [protected]";} }
    private Context importedRateContext(UUID tenant,UUID store,String order){
        boolean localAmazon=amazonSandbox!=null&&amazonSandbox.enabled()&&!carrierOrders.shopify(tenant,store);
        var history=localAmazon?amazonSandbox.history(tenant,store,order):localUat!=null&&localUat.enabled()&&carrierOrders.shopify(tenant,store)?localUat.history(tenant,store,order):List.<FedExShipmentRepository.Shipment>of();
        var c=history.stream().anyMatch(s->"PURCHASED".equals(s.state()))?carrierOrders.context(tenant,store,order):carrierOrders.rateOnlyContext(tenant,store,order);if(history.stream().noneMatch(s->"PURCHASED".equals(s.state())))eligible(c);
        var parcel=repository.orderPackage(tenant,store,order,key(c));if(parcel==null)parcel=repository.dimensions(tenant,store,key(c));if(parcel==null)parcel=FedExPackaging.profile(c.suggestedPackage());
        OrderRecipientService.View recipient;String recipientNotice="";
        if(carrierOrders.shopify(tenant,store)){
            try{var r=shopifyRecipients.recipient(tenant,store,carrierOrders.shopifyOrderGid(tenant,store,order));recipient=new OrderRecipientService.View("AVAILABLE",r);}
            catch(IllegalArgumentException|IllegalStateException ex){recipient=new OrderRecipientService.View("NOT_AVAILABLE",null);recipientNotice=ex.getMessage();}
        }else if(localAmazon){
            if(history.stream().anyMatch(shipment->"PURCHASED".equals(shipment.state())))recipient=new OrderRecipientService.View("ARCHIVED_LABEL",null);
            else{recipient=amazonSandbox.recipient(tenant,store,order);if(!"AVAILABLE".equals(recipient.status()))recipientNotice=AmazonFedExSandboxShipping.recipientNotice(recipient);}
        }else recipient=recipients.get(tenant,store,order);
        return new Context(List.of(),c.addresses(),parcel,history,preferences.get(tenant,store),recipient,FedExPackaging.OPTIONS,recipientNotice,false,carrierOrders.reference(tenant,store,order),null,sandboxRates.account(),true,tenant+":"+store,upsEstimate(tenant));
    }
    public FedExSandboxRates.Quote sandboxRates(UUID tenant,UUID store,String order,RateOnlyInput input,String actor){
        if(amazonSandbox!=null&&amazonSandbox.enabled()&&!carrierOrders.shopify(tenant,store))return amazonSandbox.quote(tenant,store,order,input,actor);
        if(localUat!=null&&localUat.enabled()&&carrierOrders.shopify(tenant,store))return localUat.quote(tenant,store,order,input,actor);
        if(!simulation)throw new IllegalStateException("Rate-only testing requires local mode.");
        var context=carrierOrders.rateOnlyContext(tenant,store,order);eligible(context);
        var sender=context.addresses().stream().filter(a->a.id().equals(input.senderId())).findFirst().orElseThrow(()->new IllegalArgumentException("Choose a ship-from address in this account."));
        var from=new FedExPayloads.Address(sender.contactName(),sender.phone(),sender.line1(),sender.city(),sender.state(),sender.postalCode(),sender.countryCode(),false);
        return sandboxRates.quote(tenant,store,from,input.recipient(),input.parcel());
    }
    public void saveRecipient(UUID tenant,UUID store,String order,FedExPayloads.Address recipient){
        if(!simulation)throw new IllegalStateException("Address editing is unavailable for this shipping mode.");
        simulationScope(tenant,store,order);fixtures.saveRecipient(tenant,store,order,recipient);
    }
    public Quote quote(UUID tenant,UUID store,String order,QuoteInput input,String actor) {
        if(!crypto.configured())throw new IllegalStateException("Configure credential encryption before using FedEx.");
        var context=carrierOrders.context(tenant,store,order);eligible(context);simulationScope(tenant,store,order);
        var previous=repository.protectedShipment(tenant,store,order);if(previous!=null)throw new IllegalStateException(previous.state().equals("PURCHASED")?"A sandbox label already exists. Open the saved label from shipment history instead of buying another.":"A previous label outcome needs review. Do not generate another label.");
        var carrier=connections.find(tenant,input.carrierId());sandbox(carrier.environment());
        if(carrier.simulated()!=simulation)throw new IllegalArgumentException("Choose a connection matching the current simulation or carrier mode.");
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
        String selectedPackage=FedExPackaging.option(parcel.packagingId()).label().replace("FedEx ","").replace(" (deep)","");
        if("YOUR_PACKAGING".equals(parcel.packagingId()))selectedPackage="Custom packaging";
        final String packageName=selectedPackage;
        var packing=(simulation||carrierOrders.shopify(tenant,store)?carrierOrders.packing(tenant,store,order):repository.packing(tenant,store,order,labelTest)).stream().map(line->line.withPackage("YOUR_PACKAGING".equals(parcel.packagingId())&&line.packageName()!=null&&!line.packageName().isBlank()?line.packageName():packageName)).toList();
        if(simulation||labelTest||carrierOrders.shopify(tenant,store))composer.validateCompact(packing);
        UUID id=repository.quote(tenant,store,order,carrier,crypto.encrypt(payload.toString().getBytes(StandardCharsets.UTF_8)),rates,packing,actor);
        return new Quote(id,rates,carrier.environment(),simulation,simulation?fixtures.get(tenant,store,order).definition().preferredService():null);
    }
    public void savePackage(UUID tenant,UUID store,String order,FedExPayloads.Parcel parcel,boolean reuse){
        parcel=FedExPackaging.normalize(parcel);FedExPayloads.validate(parcel);var context=carrierOrders.context(tenant,store,order);eligible(context);simulationScope(tenant,store,order);
        // Compatibility projection for the existing Amazon queue; no free-form profile authoring here.
        if(!simulation&&!labelTest&&!carrierOrders.shopify(tenant,store))orders.savePackage(tenant,store,order,new BuyShippingService.PackageInput(null,FedExPackaging.option(parcel.packagingId()).label(),null,parcel.length(),parcel.width(),parcel.height(),"inches",parcel.weight().multiply(new java.math.BigDecimal("16")),"oz","FedEx","AMBIENT",false,context.items().stream().filter(i->i.remaining()>0).map(i->new BuyShippingService.ItemQuantity(i.orderItemId(),i.remaining())).toList()));
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
    public FedExShipmentRepository.Shipment purchase(UUID tenant,UUID store,String order,UUID quote,String service,String actor){
        if(amazonSandbox!=null&&amazonSandbox.enabled()&&!carrierOrders.shopify(tenant,store))return amazonSandbox.purchase(tenant,store,order,quote,service);
        if(localUat!=null&&localUat.enabled()&&!LocalShippingFixtures.identity(order)&&carrierOrders.shopify(tenant,store))return localUat.purchase(tenant,store,order,quote,service,actor);
        return purchase(tenant,store,order,quote,service);
    }
    public FedExShipmentRepository.Shipment purchase(UUID tenant,UUID store,String order,UUID quote,String service) {
        if(amazonSandbox!=null&&amazonSandbox.enabled()&&!carrierOrders.shopify(tenant,store))throw new IllegalStateException("Use the authenticated Amazon sandbox purchase entry.");
        if(localUat!=null&&localUat.enabled()&&!LocalShippingFixtures.identity(order)&&carrierOrders.shopify(tenant,store))throw new IllegalStateException("Use the authenticated local UAT purchase entry.");
        var context=carrierOrders.context(tenant,store,order);eligible(context);simulationScope(tenant,store,order);
        var previous=repository.protectedShipment(tenant,store,order);
        if(previous!=null){mode(previous);if("PURCHASED".equals(previous.state()))return previous;throw new IllegalStateException("A previous label outcome needs review. Do not generate another label.");}
        var shipment=find(tenant,store,order,quote);sandbox(shipment.environment());
        if("PURCHASED".equals(shipment.state()))return shipment;
        var carrier=connections.find(tenant,shipment.carrierId());sandbox(carrier.environment());
        if(carrier.simulated()!=simulation)throw new IllegalStateException("Carrier mode changed; use the matching label history.");
        var keys=credentials.credentials(tenant,carrier);
        var request=repository.claim(tenant,store,order,quote,service,carrier.revision());
        if(request==null)throw new IllegalStateException("Rate expired, connection changed, or purchase already started. Refresh shipment history before trying again.");
        try {
            var payload=FedExPayloads.shipRequest((ObjectNode)json.readTree(crypto.decrypt(request.payload(),request.nonce())),service);
            var response=fedex.ship(tenant,carrier.id(),carrier.revision(),FedExClient.Environment.SANDBOX,keys,payload);
            var label=simulation?FedExSimulation.parseLabel(response):FedExPayloads.label(response);
            repository.purchased(tenant,quote,label.trackingNumber(),crypto.encrypt(label.pdf()),simulation||labelTest||carrierOrders.shopify(tenant,store)?null:confirmation.payload(context,label.trackingNumber(),service,quote));
        }catch(FedExClient.CarrierException ex){repository.failure(tenant,quote,ex.uncertain());throw ex;}
        catch(RuntimeException ex){repository.failure(tenant,quote,true);throw new IllegalStateException("Shipment needs review. Do not buy another label until its status is confirmed.");}
        // Sandbox tracking MUST NOT reach Amazon's production confirmShipment endpoint.
        return find(tenant,store,order,quote);
    }
    public byte[] label(UUID tenant,UUID store,String order,UUID id,CarrierLabelComposer.Layout layout) {
        if(amazonSandbox!=null&&amazonSandbox.enabled()&&!LocalShippingFixtures.identity(order)&&!carrierOrders.shopify(tenant,store))return amazonSandbox.label(tenant,store,order,id);
        if(localUat!=null&&localUat.enabled()&&!LocalShippingFixtures.identity(order)&&carrierOrders.shopify(tenant,store))return localUat.label(tenant,store,order,id);
        simulationScope(tenant,store,order);var shipment=find(tenant,store,order,id);var stored=repository.label(tenant,store,id);
        if(stored==null)throw new IllegalStateException("Label not ready. Refresh this order's shipment history.");
        List<CarrierLabelComposer.Line> lines=json.readValue(shipment.packing().toString(),json.getTypeFactory().constructCollectionType(List.class,CarrierLabelComposer.Line.class));
        var settings=preferences.get(tenant,store);
        var chosen=layout==null?(simulation||labelTest||carrierOrders.shopify(tenant,store)?CarrierLabelComposer.Layout.SANDBOX_80_20:settings.layout()):layout;
        byte[] result=composer.compose(crypto.decrypt(stored.payload(),stored.nonce()),order,lines,chosen,CarrierConnectionService.environment(shipment.environment()));
        if(simulation)return FedExSimulation.watermark(result);
        return !labelTest&&!carrierOrders.shopify(tenant,store)&&"LETTER".equals(settings.labelSize())?composer.letter(result):result;
    }
    private FedExShipmentRepository.Shipment find(UUID tenant,UUID store,String order,UUID id){var shipment=repository.list(tenant,store,order).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Shipment not found in this store and order."));mode(shipment);return shipment;}
    private void mode(FedExShipmentRepository.Shipment shipment){if(shipment.simulated()!=simulation)throw new IllegalStateException("Simulated and genuine carrier labels require their matching mode.");}
    private void simulationScope(UUID tenant,UUID store,String order){
        if(simulation){if(!LocalShippingFixtures.identity(order))throw new IllegalArgumentException("Simulation accepts only explicitly created local fixture orders.");if(fixtures==null)throw new IllegalStateException("Local fixtures are unavailable.");fixtures.get(tenant,store,order);}
        else if(LocalShippingFixtures.identity(order))throw new IllegalStateException("Local fixture orders cannot use a real carrier.");
    }
    private static void sandbox(String environment){if(!"SANDBOX".equals(environment))throw new IllegalStateException("Live FedEx shipping is not activated. Use a sandbox connection for this release.");}
    private void eligible(BuyShippingService.Context c){
        if(!(List.of("Pending","Unshipped","PartiallyShipped").contains(c.order().status())||(labelTest&&"SandboxLabelTest".equals(c.order().status())))||!List.of("MFN","MERCHANT","FBM").contains(c.order().fulfillmentChannel()==null?"":c.order().fulfillmentChannel().toUpperCase()))throw new IllegalStateException("Choose an unshipped, merchant-fulfilled order, or enable the local sandbox label test for imported history.");
        if(c.items().isEmpty()||c.items().stream().noneMatch(i->i.remaining()>0))throw new IllegalStateException("This order has no remaining items.");
        if(!c.committedQuantities().isEmpty())throw new IllegalStateException("This order already has committed shipping quantities. Review existing shipments first.");
    }
    private static java.util.SortedMap<String,Integer> composition(BuyShippingService.Context c){var counts=new java.util.TreeMap<String,Integer>();for(var i:c.items())if(i.remaining()>0){if(i.sellerSku()==null||i.sellerSku().isBlank())throw new IllegalArgumentException("Package items must have a SKU.");counts.merge(i.sellerSku(),i.remaining(),Math::addExact);}return counts;}
    static String legacySignature(BuyShippingService.Context c){return composition(c).entrySet().stream().map(e->e.getKey()+"-"+e.getValue()).collect(java.util.stream.Collectors.joining(","));}
    static String key(BuyShippingService.Context c){try{String value=((c.order()!=null&&LocalShippingFixtures.identity(c.order().amazonOrderId()))?"LOCAL_SIMULATION|":"")+composition(c).entrySet().stream().map(e->e.getKey().length()+":"+e.getKey()+":"+e.getValue()).sorted().reduce("",(a,b)->a+"|"+b);return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("Could not identify this package composition.");}}
}
