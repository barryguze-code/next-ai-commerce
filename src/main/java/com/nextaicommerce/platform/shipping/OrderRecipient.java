package com.nextaicommerce.platform.shipping;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Minimal fulfillment data; never include values in logs or exception messages. */
public record OrderRecipient(String name,String phone,String street,String street2,String street3,String city,String state,String postalCode,String country,String addressType) {
    @Override public String toString(){return "OrderRecipient[REDACTED]";}
    public boolean complete(){return List.of(name,phone,street,city,state,postalCode,country).stream().noneMatch(String::isBlank);}
    public static OrderRecipient parse(JsonNode response,String orderId){
        var order=response.path("order");
        if(!orderId.equals(order.path("orderId").asText()))throw new IllegalArgumentException("Recipient response did not match the requested order.");
        var a=order.path("recipient").path("deliveryAddress");
        return new OrderRecipient(field(a,"name"),field(a,"phone"),field(a,"addressLine1"),field(a,"addressLine2"),field(a,"addressLine3"),field(a,"city"),field(a,"stateOrRegion"),field(a,"postalCode"),field(a,"countryCode"),field(a,"addressType"));
    }
    private static String field(JsonNode node,String key){var value=node.path(key);if(!value.isTextual())return "";String result=value.asText().trim();if(result.length()>500||result.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Recipient response contains an unsupported field.");return result;}
}
