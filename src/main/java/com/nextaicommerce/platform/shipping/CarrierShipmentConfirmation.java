package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Separate from label purchase: a marketplace failure must never buy another label. */
@Component
public class CarrierShipmentConfirmation {
    private final AmazonSpApiClient amazon;
    private final ObjectMapper json;
    public CarrierShipmentConfirmation(AmazonSpApiClient amazon,ObjectMapper json){this.amazon=amazon;this.json=json;}
    public JsonNode payload(BuyShippingService.Context context,String tracking,String service,UUID shipmentId){
        var body=json.createObjectNode().put("marketplaceId",context.order().marketplaceId());
        var detail=body.putObject("packageDetail").put("packageReferenceId",Long.toString((shipmentId.getLeastSignificantBits()&0x7fffffffL)+1))
            .put("carrierCode","FedEx").put("carrierName","FedEx").put("shippingMethod",service)
            .put("trackingNumber",tracking).put("shipDate",Instant.now().toString());
        var items=detail.putArray("orderItems");for(var item:context.items())if(item.remaining()>0)items.addObject().put("orderItemId",item.orderItemId()).put("quantity",item.remaining());
        return body;
    }
    public void confirm(UUID tenant,UUID store,String order,FedExClient.Environment environment,JsonNode payload){
        if(environment!=FedExClient.Environment.PRODUCTION)throw new IllegalArgumentException("Sandbox tracking must never be submitted to Amazon.");
        if(order==null||!order.matches("[0-9]{3}-[0-9]{7}-[0-9]{7}"))throw new IllegalArgumentException("Invalid Amazon order ID.");
        if(payload==null||payload.path("packageDetail").path("orderItems").isEmpty())throw new IllegalArgumentException("Shipment confirmation requires order items.");
        amazon.postAt(tenant,store,AmazonMerchantFulfillmentClient.endpoint(payload.path("marketplaceId").asText()),
            "/orders/v0/orders/"+order+"/shipmentConfirmation",payload.toString());
    }
}
