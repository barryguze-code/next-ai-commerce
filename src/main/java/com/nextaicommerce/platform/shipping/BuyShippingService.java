package com.nextaicommerce.platform.shipping;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class BuyShippingService {
    private final BuyShippingRepository repository;
    private final AmazonMerchantFulfillmentClient amazon;
    private final ObjectMapper json;
    private final ShippingLabelCrypto crypto;

    public BuyShippingService(BuyShippingRepository repository,AmazonMerchantFulfillmentClient amazon,ObjectMapper json,ShippingLabelCrypto crypto){
        this.repository=repository;this.amazon=amazon;this.json=json;this.crypto=crypto;
    }

    public record Context(BuyShippingRepository.StoreSettings settings,BuyShippingRepository.OrderInfo order,
            List<BuyShippingRepository.OrderItem> items,List<BuyShippingRepository.Address> addresses,
            List<BuyShippingRepository.PackageProfile> profiles,BuyShippingRepository.PackageProfile suggestedPackage,
            List<BuyShippingRepository.ShipmentView> shipments,Map<String,Integer> committedQuantities){}
    public record ItemQuantity(String orderItemId,int quantity){}
    public record PackageInput(UUID profileId,String name,String containerCode,BigDecimal length,BigDecimal width,
            BigDecimal height,String dimensionUnit,BigDecimal weight,String weightUnit,String preferredCarrier,
            String temperatureClass,boolean saveForItems,List<ItemQuantity> items){}
    public record QuoteInput(UUID shipFromAddressId,boolean packingSlip,Instant plannedShipDate,List<PackageInput> packages){}
    public record PackageQuote(UUID shipmentId,int packageSequence,List<BuyShippingRepository.Offer> offers,List<String> notices){}
    public record QuoteResult(List<PackageQuote> packages){}

    public Context context(UUID tenantId,UUID connectionId,String orderId){
        var settings=repository.settings(tenantId,connectionId);if(settings==null)throw new IllegalArgumentException("Choose an Amazon store first.");
        var order=repository.order(tenantId,connectionId,orderId);if(order==null)throw new IllegalArgumentException("Order was not found in this Amazon store.");
        return new Context(settings,order,repository.items(tenantId,connectionId,orderId),repository.addresses(tenantId,connectionId),
            repository.profiles(tenantId,connectionId),repository.defaultProfile(tenantId,connectionId,orderId),repository.shipments(tenantId,connectionId,orderId),
            repository.committedQuantities(tenantId,connectionId,orderId));
    }

    public BuyShippingRepository.Address saveAddress(UUID tenantId,UUID connectionId,BuyShippingRepository.Address input){
        return repository.saveAddress(tenantId,connectionId,input);
    }

    public QuoteResult quote(UUID tenantId,UUID connectionId,String orderId,QuoteInput input,String actor){
        Context context=context(tenantId,connectionId,orderId);
        if("DISABLED".equals(context.settings().mode()))throw new IllegalStateException("Buy Shipping is disabled for this store.");
        ensureEligible(context.order());
        var address=context.addresses().stream().filter(a->a.id().equals(input.shipFromAddressId())).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Choose a saved ship-from address."));
        if(input.packages()==null||input.packages().isEmpty())throw new IllegalArgumentException("Add at least one package.");
        if(input.packages().size()>20)throw new IllegalArgumentException("Use 20 packages or fewer in one purchase group.");
        Map<String,Integer> committed=repository.committedQuantities(tenantId,connectionId,orderId);
        Map<String,Integer> remaining=new HashMap<>();for(var item:context.items())remaining.put(item.orderItemId(),Math.max(0,item.remaining()-committed.getOrDefault(item.orderItemId(),0)));
        Map<String,Integer> used=new HashMap<>();
        for(PackageInput box:input.packages())validatePackage(box,remaining,used);
        List<PackageQuote> results=new ArrayList<>();
        int sequence=0;
        for(PackageInput box:input.packages()){
            sequence++;
            UUID profileId=box.profileId();
            if(profileId!=null&&context.profiles().stream().noneMatch(profile->profile.id().equals(box.profileId())))
                throw new IllegalArgumentException("Saved package was not found in this Amazon store.");
            if(box.saveForItems()){
                profileId=repository.saveProfile(tenantId,connectionId,new BuyShippingRepository.PackageProfile(profileId,
                    box.name()==null||box.name().isBlank()?"Saved package":box.name(),box.containerCode(),box.length(),box.width(),box.height(),
                    dimensionUnit(box.dimensionUnit()),box.weight(),weightUnit(box.weightUnit()),box.preferredCarrier(),temperature(box.temperatureClass())),
                    box.items().stream().map(item->context.items().stream().filter(source->source.orderItemId().equals(item.orderItemId()))
                        .map(BuyShippingRepository.OrderItem::sellerSku).findFirst().orElse(null)).toList());
            }
            JsonNode request=details(orderId,context.order(),address,box,input.plannedShipDate());
            var snapshot=json.createObjectNode().put("name",box.name()==null?"Package "+sequence:box.name())
                .put("containerCode",box.containerCode()==null?"":box.containerCode()).put("length",box.length()).put("width",box.width())
                .put("height",box.height()).put("dimensionUnit",dimensionUnit(box.dimensionUnit())).put("weight",box.weight())
                .put("weightUnit",weightUnit(box.weightUnit())).put("preferredCarrier",box.preferredCarrier()==null?"":box.preferredCarrier())
                .put("temperatureClass",temperature(box.temperatureClass()));
            UUID shipmentId=repository.createRatedShipment(tenantId,connectionId,orderId,address.id(),profileId,sequence,
                sha256(request.toString()),request,snapshot,input.packingSlip(),actor);
            for(ItemQuantity item:box.items()){
                var source=context.items().stream().filter(value->value.orderItemId().equals(item.orderItemId())).findFirst().orElseThrow();
                var display=json.createObjectNode().put("sellerSku",source.sellerSku()).put("title",source.title()).put("quantity",item.quantity());
                repository.addShipmentItem(tenantId,shipmentId,connectionId,item.orderItemId(),item.quantity(),display);
            }
            try{
                var quote=amazon.eligible(tenantId,connectionId,context.settings().marketplaceId(),request);
                List<BuyShippingRepository.Offer> offers=quote.rates().stream().map(rate->new BuyShippingRepository.Offer(UUID.randomUUID(),
                    rate.serviceId(),rate.offerId(),rate.carrierName(),rate.serviceName(),rate.amount(),rate.currency(),rate.shipDate(),
                    rate.earliestDelivery(),rate.latestDelivery(),rate.cheapest(),rate.fastest(),rate.requiresSellerInput(),rate.labelFormats(),rate.payload())).toList();
                repository.saveOffers(tenantId,shipmentId,offers,quote.response(),quote.requestId());
                results.add(new PackageQuote(shipmentId,sequence,repository.offers(tenantId,shipmentId),quote.notices()));
            }catch(RuntimeException ex){repository.failure(tenantId,shipmentId,"FAILED","RATE_ERROR",friendly(ex));throw ex;}
        }
        return new QuoteResult(List.copyOf(results));
    }

    public void queuePurchase(UUID tenantId,UUID connectionId,String orderId,UUID shipmentId,UUID offerId,String actor){
        if(!crypto.configured())throw new IllegalStateException("Label encryption is not configured. Set APP_CREDENTIAL_ENCRYPTION_KEY before enabling purchases.");
        ensureEligible(context(tenantId,connectionId,orderId).order());
        repository.queuePurchase(tenantId,connectionId,orderId,shipmentId,offerId,actor);
    }
    public void queueRefund(UUID tenantId,UUID connectionId,UUID shipmentId,String actor){repository.queueRefund(tenantId,connectionId,shipmentId,actor);}
    public List<BuyShippingRepository.ShipmentView> status(UUID tenantId,UUID connectionId,String orderId){return repository.shipments(tenantId,connectionId,orderId);}
    public byte[] label(UUID tenantId,UUID connectionId,UUID shipmentId,ShippingLabelCrypto crypto){
        var artifact=repository.artifact(tenantId,connectionId,shipmentId);if(artifact==null)throw new IllegalArgumentException("This label is not ready yet.");
        return crypto.decrypt(artifact.encryptedPayload(),artifact.nonce());
    }

    private JsonNode details(String orderId,BuyShippingRepository.OrderInfo order,BuyShippingRepository.Address address,PackageInput box,Instant requestedShipDate){
        var root=json.createObjectNode().put("AmazonOrderId",orderId);
        var items=root.putArray("ItemList");for(ItemQuantity item:box.items())items.addObject().put("OrderItemId",item.orderItemId()).put("Quantity",item.quantity());
        var dimensions=root.putObject("PackageDimensions");dimensions.put("Length",box.length()).put("Width",box.width()).put("Height",box.height())
            .put("Unit",dimensionUnit(box.dimensionUnit()));
        root.putObject("Weight").put("Value",box.weight()).put("Unit",weightUnit(box.weightUnit()));
        Instant plannedShip=requestedShipDate!=null?requestedShipDate:
            (order.earliestShip()!=null&&order.earliestShip().isAfter(Instant.now())?order.earliestShip():Instant.now());
        root.put("ShipDate",plannedShip.toString());
        if(order.latestDelivery()!=null)root.put("MustArriveByDate",order.latestDelivery().toString());
        var from=root.putObject("ShipFromAddress");from.put("Name",address.contactName());put(from,"CompanyName",address.companyName());
        from.put("AddressLine1",address.line1());put(from,"AddressLine2",address.line2());put(from,"AddressLine3",address.line3());
        from.put("City",address.city());put(from,"StateOrProvinceCode",address.state());from.put("PostalCode",address.postalCode())
            .put("CountryCode",address.countryCode()).put("Phone",address.phone());put(from,"Email",address.email());
        root.putObject("ShippingServiceOptions").put("DeliveryExperience","DeliveryConfirmationWithoutSignature").put("CarrierWillPickUp",false)
            .put("CarrierWillPickUpOption","ShipperWillDropOff").put("LabelFormat","PDF");
        return root;
    }
    private static void validatePackage(PackageInput box,Map<String,Integer> remaining,Map<String,Integer> used){
        positive(box.length(),"Length");positive(box.width(),"Width");positive(box.height(),"Height");positive(box.weight(),"Weight");
        dimensionUnit(box.dimensionUnit());weightUnit(box.weightUnit());
        if(box.items()==null||box.items().isEmpty())throw new IllegalArgumentException("Choose at least one order item for every package.");
        for(ItemQuantity item:box.items()){
            if(!remaining.containsKey(item.orderItemId()))throw new IllegalArgumentException("A package contains an item outside this order.");
            if(item.quantity()<=0)throw new IllegalArgumentException("Package item quantities must be greater than zero.");
            int total=used.merge(item.orderItemId(),item.quantity(),Integer::sum);
            if(total>remaining.get(item.orderItemId()))throw new IllegalArgumentException("The split package quantity is greater than the unshipped order quantity.");
        }
    }
    private static void ensureEligible(BuyShippingRepository.OrderInfo order){
        String status=order.status()==null?"":order.status().replaceAll("[^A-Za-z]","").toUpperCase();
        if(!List.of("PENDING","UNSHIPPED").contains(status))throw new IllegalStateException("Buy Shipping is available only for Pending or Unshipped orders.");
        if(List.of("AFN","AMAZON").contains(order.fulfillmentChannel()==null?"":order.fulfillmentChannel().toUpperCase()))
            throw new IllegalStateException("Amazon-fulfilled orders do not need a merchant shipping label.");
    }
    private static void positive(BigDecimal value,String label){if(value==null||value.signum()<=0)throw new IllegalArgumentException(label+" must be greater than zero.");}
    private static String dimensionUnit(String value){if("centimeters".equalsIgnoreCase(value))return "centimeters";if("inches".equalsIgnoreCase(value))return "inches";throw new IllegalArgumentException("Choose inches or centimeters.");}
    private static String weightUnit(String value){if("g".equalsIgnoreCase(value))return "g";if("oz".equalsIgnoreCase(value))return "oz";throw new IllegalArgumentException("Choose ounces or grams.");}
    private static String temperature(String value){String normalized=value==null?"AMBIENT":value.trim().toUpperCase();
        if(!List.of("AMBIENT","REFRIGERATED","FROZEN").contains(normalized))throw new IllegalArgumentException("Choose ambient, refrigerated, or frozen.");return normalized;}
    private static void put(tools.jackson.databind.node.ObjectNode node,String field,String value){if(value!=null&&!value.isBlank())node.put(field,value);}
    private static String sha256(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    static String friendly(Throwable ex){String value=ex.getMessage();return value==null||value.isBlank()?"Amazon could not complete the request.":value;}
}
