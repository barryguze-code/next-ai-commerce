package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix="app.amazon",name={"write-enabled","buy-shipping-enabled"},havingValue="true")
public class BuyShippingWorker {
    private static final Logger log=LoggerFactory.getLogger(BuyShippingWorker.class);
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;private final BuyShippingRepository repository;
    private final AmazonMerchantFulfillmentClient amazon;private final ShippingLabelComposer composer;private final ShippingLabelCrypto crypto;

    public BuyShippingWorker(JdbcTemplate jdbc,TransactionTemplate transactions,BuyShippingRepository repository,
            AmazonMerchantFulfillmentClient amazon,ShippingLabelComposer composer,ShippingLabelCrypto crypto){
        this.jdbc=jdbc;this.transactions=transactions;this.repository=repository;this.amazon=amazon;this.composer=composer;this.crypto=crypto;
    }

    @Scheduled(fixedDelayString="${app.amazon.buy-shipping-worker-delay-ms:1500}",initialDelayString="${app.amazon.buy-shipping-worker-initial-delay-ms:5000}")
    public void work(){
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(rs,row)->rs.getObject(1,UUID.class));
        for(UUID tenantId:tenants){
            transactions.executeWithoutResult(status->{setTenant(tenantId);repository.markAbandonedPurchasesUnknown(tenantId);});
            BuyShippingRepository.ShipmentCommand purchase=transactions.execute(status->{setTenant(tenantId);return repository.claim(tenantId,"PURCHASE_QUEUED","PURCHASE_IN_PROGRESS");});
            if(purchase!=null)purchase(purchase);
            BuyShippingRepository.ShipmentCommand refund=transactions.execute(status->{setTenant(tenantId);return repository.claim(tenantId,"REFUND_QUEUED","REFUND_PENDING");});
            if(refund!=null)refund(refund);
            BuyShippingRepository.ShipmentCommand pending=transactions.execute(status->{setTenant(tenantId);return repository.claimPendingRefund(tenantId);});
            if(pending!=null)checkRefund(pending);
            BuyShippingRepository.ShipmentCommand recovery=transactions.execute(status->{setTenant(tenantId);return repository.claimRecoverablePurchase(tenantId);});
            if(recovery!=null)recoverPurchase(recovery);
        }
    }

    private void purchase(BuyShippingRepository.ShipmentCommand command){
        log.info("Buy Shipping purchase started — order {}; package {}.",command.amazonOrderId(),command.id());
        AmazonMerchantFulfillmentClient.Purchase bought=null;
        try{
            bought=amazon.purchase(command.tenantId(),command.connectionId(),command.marketplaceId(),command.requestDetails(),
                command.selectedServiceId(),command.selectedOfferId());
            var lines=repository.packingLines(command.tenantId(),command.id());
            var composed=composer.compose(bought.labelPdf(),command.amazonOrderId(),lines,command.packingSlip(),repository.packingContext(command.tenantId(),command.id()));
            BigDecimal price=bought.price()==null||bought.price().signum()<0?command.rate():bought.price();
            String currency=bought.currency()==null?command.currency():bought.currency();
            repository.purchased(command.tenantId(),command.id(),bought.shipmentId(),bought.trackingId(),price,currency,
                bought.serviceId(),bought.carrierName(),bought.serviceName(),bought.shipDate(),bought.earliestDelivery(),
                bought.latestDelivery(),bought.response(),bought.requestId(),crypto.encrypt(bought.labelPdf()),crypto.encrypt(composed.pdf()),composed.pageCount());
            log.info("Buy Shipping purchase complete — order {}; tracking {}.",command.amazonOrderId(),bought.trackingId());
        }catch(AmazonSpApiClient.AmazonApiException rejected){
            repository.failure(command.tenantId(),command.id(),"FAILED","AMAZON_"+rejected.status(),rejected.getMessage());
            log.warn("Buy Shipping purchase rejected — order {}. {}",command.amazonOrderId(),rejected.getMessage());
        }catch(AmazonMerchantFulfillmentClient.LabelArtifactException artifact){
            repository.purchaseIdentityUnknown(command.tenantId(),command.id(),artifact.metadata(),
                "Amazon created the shipment, but its label file needs to be retrieved again. The application will retry retrieval without buying another label.");
            log.warn("Buy Shipping label retrieval will retry — order {}; shipment {}.",command.amazonOrderId(),artifact.metadata().shipmentId());
        }catch(RuntimeException uncertain){
            if(bought!=null)repository.purchaseIdentityUnknown(command.tenantId(),command.id(),metadata(bought),
                "Amazon created the shipment, but its printable file was not safely stored. The application will retrieve the same shipment without buying another label. "+BuyShippingService.friendly(uncertain));
            else repository.failure(command.tenantId(),command.id(),"PURCHASE_UNKNOWN","OUTCOME_UNKNOWN",
                "Amazon may have created this label, but confirmation was not safely stored. Check Buy Shipping in Seller Central before trying again. "+BuyShippingService.friendly(uncertain));
            log.error("Buy Shipping purchase outcome needs review — order {}. {}",command.amazonOrderId(),uncertain.getMessage(),uncertain);
        }
    }

    private static AmazonMerchantFulfillmentClient.PurchaseMetadata metadata(AmazonMerchantFulfillmentClient.Purchase purchase){
        return new AmazonMerchantFulfillmentClient.PurchaseMetadata(purchase.shipmentId(),purchase.trackingId(),purchase.serviceId(),
            purchase.carrierName(),purchase.serviceName(),purchase.price(),purchase.currency(),purchase.shipDate(),
            purchase.earliestDelivery(),purchase.latestDelivery(),purchase.response(),purchase.requestId());
    }

    private void recoverPurchase(BuyShippingRepository.ShipmentCommand command){
        try{
            var bought=amazon.retrievePurchase(command.tenantId(),command.connectionId(),command.marketplaceId(),command.amazonShipmentId(),command.selectedServiceId());
            var composed=composer.compose(bought.labelPdf(),command.amazonOrderId(),repository.packingLines(command.tenantId(),command.id()),command.packingSlip(),
                repository.packingContext(command.tenantId(),command.id()));
            BigDecimal price=bought.price()==null||bought.price().signum()<0?command.rate():bought.price();
            repository.purchased(command.tenantId(),command.id(),bought.shipmentId(),bought.trackingId(),price,
                bought.currency()==null?command.currency():bought.currency(),bought.serviceId(),bought.carrierName(),bought.serviceName(),
                bought.shipDate(),bought.earliestDelivery(),bought.latestDelivery(),bought.response(),bought.requestId(),
                crypto.encrypt(bought.labelPdf()),crypto.encrypt(composed.pdf()),composed.pageCount());
        }catch(RuntimeException unavailable){log.warn("Buy Shipping label retrieval will retry later — order {}; shipment {}.",command.amazonOrderId(),command.amazonShipmentId());}
    }

    private void refund(BuyShippingRepository.ShipmentCommand command){
        if(command.amazonShipmentId()==null){repository.failure(command.tenantId(),command.id(),"REFUND_REJECTED","MISSING_SHIPMENT","Amazon shipment ID is missing.");return;}
        log.info("Buy Shipping label refund started — order {}; shipment {}.",command.amazonOrderId(),command.amazonShipmentId());
        try{
            var result=amazon.refund(command.tenantId(),command.connectionId(),command.marketplaceId(),command.amazonShipmentId());
            String state=refundState(AmazonMerchantFulfillmentClient.shipmentState(result.response()));
            repository.refunded(command.tenantId(),command.id(),state,result.response(),result.requestId());
            log.info("Buy Shipping label refund accepted — order {}; shipment {}; state {}.",command.amazonOrderId(),command.amazonShipmentId(),state);
        }catch(AmazonSpApiClient.AmazonApiException rejected){
            repository.failure(command.tenantId(),command.id(),"REFUND_REJECTED","AMAZON_"+rejected.status(),rejected.getMessage());
        }catch(RuntimeException uncertain){
            repository.failure(command.tenantId(),command.id(),"REFUND_PENDING","OUTCOME_UNKNOWN",
                "Amazon may still be processing this refund. Check Seller Central before trying again.");
        }
    }
    private void checkRefund(BuyShippingRepository.ShipmentCommand command){
        try{
            var result=amazon.shipment(command.tenantId(),command.connectionId(),command.marketplaceId(),command.amazonShipmentId());
            repository.refunded(command.tenantId(),command.id(),refundState(result.status()),result.response(),result.requestId());
        }catch(AmazonSpApiClient.AmazonApiException rejected){
            if(rejected.status()>=400&&rejected.status()<500&&rejected.status()!=429)
                repository.failure(command.tenantId(),command.id(),"REFUND_REJECTED","AMAZON_"+rejected.status(),rejected.getMessage());
        }catch(RuntimeException unavailable){log.warn("Buy Shipping refund check will retry later — order {}.",command.amazonOrderId());}
    }
    private static String refundState(String amazonState){
        String normalized=amazonState==null?"":amazonState.replaceAll("[^A-Za-z]","").toUpperCase();
        if("REFUNDAPPLIED".equals(normalized))return "REFUND_APPLIED";
        if("REFUNDREJECTED".equals(normalized))return "REFUND_REJECTED";
        return "REFUND_PENDING";
    }
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
}
