package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.nextaicommerce.platform.shopify.ShopifyOrderLifecycle.Ownership;

class ShopifyOrderClientTest {
    private final ShopifyOrderClient client=new ShopifyOrderClient(null);
    private static final String BODY="""
      {"data":{"shop":{"id":"gid://shopify/Shop/1","currencyCode":"USD"},"orders":{
        "pageInfo":{"hasNextPage":false,"endCursor":"next"},"nodes":[{
          "id":"gid://shopify/Order/1","name":"#1001","createdAt":"2026-10-27T10:00:00Z","updatedAt":"2026-10-27T10:01:00Z",
          "cancelledAt":null,"test":false,"displayFinancialStatus":"PENDING","displayFulfillmentStatus":"UNFULFILLED",
          "currentTotalPriceSet":{"shopMoney":{"amount":"12.50","currencyCode":"USD"}},
          "lineItems":{"pageInfo":{"hasNextPage":false},"nodes":[{
            "id":"gid://shopify/LineItem/1","title":"Product","sku":"000001-EAx3","quantity":3,"currentQuantity":3,"unfulfilledQuantity":3,"requiresShipping":true,
            "variant":{"id":"gid://shopify/ProductVariant/1"},"image":null,"discountedTotalSet":{"shopMoney":{"amount":"12.50","currencyCode":"USD"}}
          }]},
          "fulfillmentOrders":{"pageInfo":{"hasNextPage":false},"nodes":[{
            "id":"gid://shopify/FulfillmentOrder/1","status":"OPEN","lineItems":{"pageInfo":{"hasNextPage":false},"nodes":[{
              "id":"gid://shopify/FulfillmentOrderLineItem/1","remainingQuantity":3,"totalQuantity":3,"lineItem":{"id":"gid://shopify/LineItem/1"}
            }]}
          }]},"fulfillmentsCount":{"count":0,"precision":"EXACT"},"fulfillments":[]
        }]}}
        ,"extensions":{"cost":{"requestedQueryCost":450,"throttleStatus":{"currentlyAvailable":500,"restoreRate":50}}}}
      """;
    private ShopifyOrderClient.Page parse(String body){return client.parse(new ShopifyConnectionService.CatalogResponse(body,"gid://shopify/Shop/1","USD",Instant.EPOCH),null);}
    @Test void exactIdentityAndCompleteMerchantWorkPreserveLeadingZeros(){
        var p=parse(BODY);assertThat(p.orders()).hasSize(1);var o=p.orders().getFirst();assertThat(o.complete()).isTrue();
        var line=o.lines().getFirst();assertThat(line.sku()).isEqualTo("000001-EAx3");assertThat(line.ownership()).isEqualTo(Ownership.MERCHANT);
        assertThat(line.unfulfilled()).isEqualTo(3);assertThat(line.image()).isNull();assertThat(o.payment()).isEqualTo("PENDING");
    }
    @Test void unseenAssignmentsDoNotBecomeMerchantOrStandalone(){
        var p=parse(BODY.replace("\"remainingQuantity\":3","\"remainingQuantity\":2"));
        assertThat(p.orders().getFirst().lines().getFirst().ownership()).isEqualTo(Ownership.UNKNOWN);
    }
    @Test void truncatedNestedEvidenceIsExplicitlyHeld(){
        for(String body:new String[]{BODY.replace("\"pageInfo\":{\"hasNextPage\":false},\"nodes\"","\"pageInfo\":{\"hasNextPage\":true},\"nodes\""),BODY.replace("\"count\":0","\"count\":1")}){
            var o=parse(body).orders().getFirst();assertThat(o.complete()).isFalse();assertThat(o.review()).contains("held");
        }
    }
    @Test void nullAndBlankSkuAreSourceDataNotIdentity(){
        for(String raw:new String[]{"null","\"\""}){
            var l=parse(BODY.replace("\"000001-EAx3\"",raw)).orders().getFirst().lines().getFirst();
            assertThat(l.gid()).isEqualTo("gid://shopify/LineItem/1");
        }
    }
    @Test void rejectsMissingScopeErrorsWrongShopMalformedQuantitiesAndMissingFields(){
        for(String raw:new String[]{"{\"errors\":[{\"message\":\"Access denied\"}]}",BODY.replace("gid://shopify/Shop/1","gid://shopify/Shop/2"),BODY.replace("\"quantity\":3","\"quantity\":-1"),BODY.replace("\"requiresShipping\":true","\"other\":true"),BODY.replace("\"requestedQueryCost\":450","\"requestedQueryCost\":1001")})
            assertThatThrownBy(()->parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void queryIsReadOnlyAndDoesNotRequestCustomerOrPaymentCredentials(){
        assertThat(ShopifyOrderClient.QUERY).startsWith("query LocalOrders").doesNotContain("mutation","customer","email","phone","destination","address","paymentGateway","transactions","fulfillmentService");
        assertThat(ShopifyOrderClient.QUERY).contains("fulfillmentOrders(first:5,displayable:false)","fulfillmentsCount","currentQuantity","unfulfilledQuantity");
    }
}
