package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.sync.AmazonMarketplaceTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ShippingDeskService {
    private final ShippingDeskRepository desk;private final BuyShippingRepository shippingRepository;
    private final BuyShippingService shipping;private final ShippingLabelCrypto crypto;private final ShippingBatchPdfComposer pdf;
    private final ObjectMapper json;
    public ShippingDeskService(ShippingDeskRepository desk,BuyShippingRepository shippingRepository,BuyShippingService shipping,
            ShippingLabelCrypto crypto,ShippingBatchPdfComposer pdf,ObjectMapper json){
        this.desk=desk;this.shippingRepository=shippingRepository;this.shipping=shipping;this.crypto=crypto;this.pdf=pdf;this.json=json;
    }

    public record Workspace(ShippingDeskRepository.PolicySettings policy,List<ShippingDeskRepository.Candidate> candidates,
            List<ShippingDeskRepository.BatchSummary> batches,List<ShippingDeskRepository.LabelView> labels,boolean hasDefaultAddress,
            String purchaseMode){}
    public record CreateBatch(List<String> orderIds,String name){}
    public record ConfirmBatch(BigDecimal expectedTotal){}
    public record BatchDetail(ShippingDeskRepository.BatchSummary batch,List<ShippingDeskRepository.BatchOrderView> orders){}

    public Workspace workspace(UUID tenantId,UUID connectionId){
        var settings=shippingRepository.settings(tenantId,connectionId);if(settings==null)throw new IllegalArgumentException("Choose an Amazon store first.");
        return new Workspace(desk.policy(tenantId,connectionId),desk.candidates(tenantId,connectionId),desk.batches(tenantId,connectionId),
            desk.labels(tenantId,connectionId),shippingRepository.addresses(tenantId,connectionId).stream().anyMatch(BuyShippingRepository.Address::isDefault),settings.mode());
    }

    public UUID create(UUID tenantId,UUID connectionId,CreateBatch request,String actor){
        var settings=shippingRepository.settings(tenantId,connectionId);
        if(settings==null||"DISABLED".equals(settings.mode()))throw new IllegalStateException("Buy Shipping is disabled for this Amazon store.");
        if(request==null||request.orderIds()==null||request.orderIds().isEmpty())throw new IllegalArgumentException("Select at least one package-ready order.");
        if(request.orderIds().size()>50)throw new IllegalArgumentException("Create batches of 50 orders or fewer.");
        Map<String,ShippingDeskRepository.Candidate> available=new HashMap<>();for(var item:desk.candidates(tenantId,connectionId))available.put(item.orderId(),item);
        List<ShippingDeskRepository.Candidate> selected=request.orderIds().stream().distinct().map(available::get).toList();
        if(selected.stream().anyMatch(java.util.Objects::isNull)||selected.size()!=request.orderIds().stream().distinct().count())
            throw new IllegalStateException("One or more orders are no longer package-ready. Refresh the shipping desk and review them.");
        if(shippingRepository.addresses(tenantId,connectionId).stream().noneMatch(BuyShippingRepository.Address::isDefault))
            throw new IllegalStateException("Save a default ship-from address from an order before creating a batch.");
        String name=request.name()==null||request.name().isBlank()?"Shipping batch · "+java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy · h:mm a")
            .withZone(java.time.ZoneId.systemDefault()).format(Instant.now()):request.name();
        if(name.trim().length()>160)throw new IllegalArgumentException("Batch name must be 160 characters or fewer.");
        return desk.createBatch(tenantId,connectionId,name,selected,desk.policy(tenantId,connectionId),actor);
    }

    public void rate(ShippingDeskRepository.BatchCommand command){
        try{
            var policySettings=desk.batchPolicy(command.tenantId(),command.batchId());
            var policy=policy(policySettings);var zone=AmazonMarketplaceTime.zone(command.marketplaceId());
            var schedule=ColdChainShippingPolicy.schedule(policy,zone,command.profile().temperatureClass(),command.serviceLevel(),command.customerShipping(),Instant.now());
            var context=shipping.context(command.tenantId(),command.connectionId(),command.orderId());
            var address=context.addresses().stream().filter(BuyShippingRepository.Address::isDefault).findFirst()
                .orElseThrow(()->new IllegalStateException("The default ship-from address is missing."));
            List<BuyShippingService.ItemQuantity> items=context.items().stream().map(item->new BuyShippingService.ItemQuantity(item.orderItemId(),
                Math.max(0,item.remaining()-context.committedQuantities().getOrDefault(item.orderItemId(),0)))).filter(item->item.quantity()>0).toList();
            if(items.isEmpty())throw new IllegalStateException("All unshipped units already have labels.");
            var profile=command.profile();var box=new BuyShippingService.PackageInput(profile.id(),profile.name(),profile.containerCode(),profile.length(),
                profile.width(),profile.height(),profile.dimensionUnit(),profile.weight(),profile.weightUnit(),profile.preferredCarrier(),
                profile.temperatureClass(),false,items);
            Instant planned=schedule.handoffDate().atTime(LocalTime.of(9,0)).atZone(zone).toInstant();
            var quote=shipping.quote(command.tenantId(),command.connectionId(),command.orderId(),
                new BuyShippingService.QuoteInput(address.id(),context.settings().printPackingSlip(),planned,List.of(box)),command.actor());
            var packageQuote=quote.packages().getFirst();
            List<AmazonMerchantFulfillmentClient.Rate> policyOffers=packageQuote.offers().stream().map(this::rate).toList();
            var choice=ColdChainShippingPolicy.choose(policy,zone,profile.temperatureClass(),schedule.handoffDate(),policyOffers);
            if(choice.offer()==null){desk.attention(command.tenantId(),command.itemId(),choice.reason());return;}
            var selected=packageQuote.offers().stream().filter(offer->same(offer,choice.offer())).findFirst().orElseThrow();
            var decision=json.createObjectNode().put("reason",choice.reason()).put("handoffDate",schedule.handoffDate().toString())
                .put("operationNote",schedule.note()).put("extraIce",schedule.extraIce()).put("temperatureClass",profile.temperatureClass())
                .put("customerShipping",command.customerShipping()).put("serviceLevel",command.serviceLevel()==null?"":command.serviceLevel());
            desk.rated(command.tenantId(),command.itemId(),packageQuote.shipmentId(),selected.id(),selected.amount(),selected.currency(),
                schedule.handoffDate(),schedule.extraIce(),schedule.note()+" · "+choice.reason(),decision);
        }catch(RuntimeException ex){desk.attention(command.tenantId(),command.itemId(),BuyShippingService.friendly(ex));}
    }

    @Transactional
    public void purchase(UUID tenantId,UUID connectionId,UUID batchId,ConfirmBatch request,String actor){
        var settings=shippingRepository.settings(tenantId,connectionId);
        if(settings==null||!"PURCHASE_ENABLED".equals(settings.mode()))
            throw new IllegalStateException("Label purchasing is still in safe preview mode for this store.");
        var summary=batch(tenantId,connectionId,batchId).batch();BigDecimal expected=request==null?null:request.expectedTotal();
        if(expected==null||summary.quotedTotal()==null||expected.compareTo(summary.quotedTotal())!=0)
            throw new IllegalStateException("The batch total changed. Review the rates again before purchasing.");
        desk.confirmBatch(tenantId,connectionId,batchId,actor);
        for(var item:desk.batchOrders(tenantId,connectionId,batchId))if("RATED".equals(item.state())){
            try{shipping.queuePurchase(tenantId,connectionId,item.orderId(),item.shipmentId(),item.selectedOfferId(),actor);desk.queued(tenantId,item.id());}
            catch(RuntimeException ex){desk.attention(tenantId,item.id(),BuyShippingService.friendly(ex));}
        }
    }

    public BatchDetail batch(UUID tenantId,UUID connectionId,UUID batchId){
        var summary=desk.batch(tenantId,connectionId,batchId);
        if(summary==null)throw new IllegalArgumentException("Shipping batch was not found.");
        return new BatchDetail(summary,desk.batchOrders(tenantId,connectionId,batchId));
    }
    public byte[] batchPdf(UUID tenantId,UUID connectionId,UUID batchId){
        var summary=batch(tenantId,connectionId,batchId).batch();
        if(!List.of("READY","PARTIAL").contains(summary.state()))
            throw new IllegalStateException("This batch is still processing. Print it after every purchase reaches a final state.");
        List<ShippingDeskRepository.BatchArtifact> artifacts=desk.batchArtifacts(tenantId,connectionId,batchId);
        byte[] merged=pdf.merge(artifacts.stream().map(item->crypto.decrypt(item.encryptedPayload(),item.nonce())).toList());
        for(var artifact:artifacts)desk.recordAccess(tenantId,artifact.shipmentId());return merged;
    }
    public ShippingDeskRepository.PolicySettings savePolicy(UUID tenantId,UUID connectionId,ShippingDeskRepository.PolicySettings value){return desk.savePolicy(tenantId,connectionId,value);}
    public void discard(UUID tenantId,UUID connectionId,UUID batchId){desk.discard(tenantId,connectionId,batchId);}

    private AmazonMerchantFulfillmentClient.Rate rate(BuyShippingRepository.Offer offer){return new AmazonMerchantFulfillmentClient.Rate(
        offer.serviceId(),offer.offerId(),offer.carrierName(),offer.serviceName(),offer.amount(),offer.currency(),offer.shipDate(),
        offer.earliestDelivery(),offer.latestDelivery(),offer.requiresSellerInput(),offer.labelFormats(),offer.payload(),offer.cheapest(),offer.fastest());}
    private static boolean same(BuyShippingRepository.Offer local,AmazonMerchantFulfillmentClient.Rate chosen){
        return java.util.Objects.equals(local.serviceId(),chosen.serviceId())&&java.util.Objects.equals(local.offerId(),chosen.offerId());}
    private static ColdChainShippingPolicy.Policy policy(ShippingDeskRepository.PolicySettings value){return new ColdChainShippingPolicy.Policy(
        value.handlingDays(),value.targetTransitDays(),value.cutoffTime(),value.blockedServiceTerms(),value.upsGroundPremiumLimit(),
        value.weekendHold(),value.paidOrExpeditedFridayHandoff());}
}
