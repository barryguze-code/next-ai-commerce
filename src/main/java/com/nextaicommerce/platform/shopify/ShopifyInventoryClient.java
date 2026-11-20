package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed 2026-10 inventory protocol; no activation, product, price, or order mutation. */
@Component
public class ShopifyInventoryClient {
    public static final String READ="""
        query InventoryPublication($item: ID!, $location: ID!) {
          shop { id }
          inventoryItem(id: $item) { id tracked inventoryLevel(locationId: $location) {
            location { id isActive } quantities(names: ["available"]) { name quantity }
          } }
        }
        """;
    public static final String WRITE="""
        mutation InventoryPublication($input: InventorySetQuantitiesInput!, $idempotencyKey: String!) {
          inventorySetQuantities(input: $input) @idempotent(key: $idempotencyKey) {
            inventoryAdjustmentGroup { referenceDocumentUri changes { name quantityAfterChange } }
            userErrors { code field message }
          }
        }
        """;
    private final ShopifyConnectionService transport;
    private final ObjectMapper json=new ObjectMapper();
    public ShopifyInventoryClient(ShopifyConnectionService transport){this.transport=transport;}
    public record Target(UUID tenant,UUID connection,String shop,String item,String location){
        public Target {
            Objects.requireNonNull(tenant);Objects.requireNonNull(connection);
            gid(shop,"Shop");gid(item,"InventoryItem");gid(location,"Location");
        }
    }
    private static void gid(String value,String kind){if(value==null||!value.matches("gid://shopify/"+kind+"/[0-9]+"))throw new IllegalArgumentException("Invalid Shopify "+kind+" identity");}
    public int available(Target target,Runnable reserve){
        var body=json.createObjectNode().put("query",READ);
        body.putObject("variables").put("item",target.item()).put("location",target.location());
        var response=transport.inventory(target.tenant(),target.connection(),target.shop(),json.writeValueAsString(body),reserve,false);
        var data=checked(response.body()).path("data");var item=data.path("inventoryItem");var level=item.path("inventoryLevel");
        if(!target.shop().equals(data.path("shop").path("id").asText())||!target.item().equals(item.path("id").asText())
            ||!item.path("tracked").asBoolean()||!target.location().equals(level.path("location").path("id").asText())
            ||!level.path("location").path("isActive").asBoolean())throw new IllegalStateException("Inventory destination is not verified and active");
        var quantities=level.path("quantities");
        if(!quantities.isArray()||quantities.size()!=1||!"available".equals(quantities.get(0).path("name").asText())||!quantities.get(0).path("quantity").isIntegralNumber())
            throw new IllegalStateException("Available quantity is unknown");
        return quantities.get(0).path("quantity").intValue();
    }
    public void setAvailable(Target target,int expected,int desired,UUID operation,Runnable reserve){
        if(desired<0)throw new IllegalArgumentException("Desired inventory cannot be negative");
        Objects.requireNonNull(operation);
        var body=json.createObjectNode().put("query",WRITE);var vars=body.putObject("variables").put("idempotencyKey",operation.toString());
        String reference="nextaicommerce://inventory-publication/"+operation;
        var input=vars.putObject("input").put("name","available").put("reason","correction").put("referenceDocumentUri",reference);
        input.putArray("quantities").addObject().put("inventoryItemId",target.item()).put("locationId",target.location()).put("quantity",desired).put("changeFromQuantity",expected);
        var response=transport.inventory(target.tenant(),target.connection(),target.shop(),json.writeValueAsString(body),reserve,true);
        var result=checked(response.body()).path("data").path("inventorySetQuantities");var errors=result.path("userErrors");
        if(!errors.isArray())throw new IllegalStateException("Inventory submission result is incomplete");
        for(var error:errors){if("CHANGE_FROM_QUANTITY_STALE".equals(error.path("code").asText()))throw new StaleQuantity();}
        if(!errors.isEmpty())throw new IllegalStateException("Shopify rejected inventory submission");
        if(!reference.equals(result.path("inventoryAdjustmentGroup").path("referenceDocumentUri").asText()))throw new IllegalStateException("Inventory submission has no matching receipt");
        // The worker must still read back before marking the durable operation confirmed.
    }
    private JsonNode checked(String body){var root=json.readTree(body);if(root.has("errors"))throw new IllegalStateException("Shopify inventory response contains errors");return root;}
    public static class StaleQuantity extends IllegalStateException {public StaleQuantity(){super("Inventory changed; reconcile orders before preparing a new operation");}}
}
