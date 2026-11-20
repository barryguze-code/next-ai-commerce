package com.nextaicommerce.platform.sync;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Prevent incidental PII from legacy order responses entering raw source archives. */
public final class OrderPiiRedactor {
    private OrderPiiRedactor(){}
    private static final Set<String> KEYS=Set.of("shippingaddress","buyerinfo","buyername","buyeremail","buyerphone","recipient","deliveryaddress","giftmessage","giftwraptext","buyerrequestedcancelreason","customizedurl","shippingphone","shipphone");
    public static JsonNode redact(JsonNode source){JsonNode copy=source.deepCopy();clean(copy);return copy;}
    private static void clean(JsonNode value){
        if(value.isObject()) {var object=(ObjectNode)value;for(String key:java.util.List.copyOf(object.propertyNames())){if(KEYS.contains(key.toLowerCase(java.util.Locale.ROOT)))object.remove(key);else clean(object.get(key));}}
        else if(value.isArray())for(JsonNode child:value)clean(child);
    }
}
