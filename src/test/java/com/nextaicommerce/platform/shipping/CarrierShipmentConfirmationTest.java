package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import tools.jackson.databind.ObjectMapper;

class CarrierShipmentConfirmationTest {
    final AmazonSpApiClient amazon=mock(AmazonSpApiClient.class);final ObjectMapper json=new ObjectMapper();
    @Test void sandboxTrackingNeverCallsAmazon(){var c=new CarrierShipmentConfirmation(amazon,json);assertThatThrownBy(()->c.confirm(UUID.randomUUID(),UUID.randomUUID(),"114-1229726-6987419",FedExClient.Environment.SANDBOX,json.createObjectNode())).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(amazon);}
    @Test void confirmationUsesAmazonItemIdsAndNumericStablePackageReference(){
        var c=new CarrierShipmentConfirmation(amazon,json);var context=new BuyShippingService.Context(null,new BuyShippingRepository.OrderInfo("114-1229726-6987419","Unshipped","MFN","ATVPDKIKX0DER",null,null,null,null),List.of(new BuyShippingRepository.OrderItem("ITEM-ID","SKU","ASIN","Title",3,1)),List.of(),List.of(),null,List.of(),Map.of());UUID shipment=UUID.randomUUID(),tenant=UUID.randomUUID(),store=UUID.randomUUID();
        var payload=c.payload(context,"123456789012","FEDEX_GROUND",shipment);assertThat(payload.path("packageDetail").path("packageReferenceId").asText()).matches("[1-9][0-9]*");assertThat(payload.path("packageDetail").path("orderItems").get(0).path("quantity").asInt()).isEqualTo(2);
        c.confirm(tenant,store,context.order().amazonOrderId(),FedExClient.Environment.PRODUCTION,payload);
        verify(amazon).postAt(tenant,store,"https://sellingpartnerapi-na.amazon.com","/orders/v0/orders/114-1229726-6987419/shipmentConfirmation",payload.toString());
    }
}
