package com.nextaicommerce.platform.shopify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShopifyOrderStatusTest {
    ShopifyOrderReadRepository.Order order(String payment,String fulfillment,boolean cancelled,boolean marked){
        return new ShopifyOrderReadRepository.Order(UUID.randomUUID(),"gid://shopify/Order/1","#1001",Instant.EPOCH,payment,fulfillment,"USD",BigDecimal.TEN,true,cancelled,null,"https://fixture.myshopify.com/admin/orders/1",List.of(),marked);
    }
    @Test void nativeEvidenceUsesSharedStatusVocabularyWithoutLosingPartialOrCancelledFacts(){
        assertThat(order("PENDING","UNFULFILLED",false,false).statusLabel()).isEqualTo("Pending");
        assertThat(order("PAID","UNFULFILLED",false,false).statusLabel()).isEqualTo("Unshipped");
        assertThat(order("PAID","PARTIALLY_FULFILLED",false,false).statusLabel()).isEqualTo("Partially shipped");
        assertThat(order("PAID","FULFILLED",false,false).statusLabel()).isEqualTo("Shipped");
        assertThat(order("REFUNDED","FULFILLED",true,false).statusLabel()).isEqualTo("Cancelled");
        assertThat(order("PAID","UNFULFILLED",false,true).statusLabel()).isEqualTo("Waiting for pickup");
    }
    @Test void aVisibleUnshippedStatusAloneCannotAuthorizePacking(){
        assertThat(order("PAID","UNFULFILLED",false,false).pickupEligible()).isFalse();
        assertThat(order("PENDING","UNFULFILLED",false,false).statusTone()).isEqualTo("pending");
        assertThat(order("PAID","UNFULFILLED",false,false).statusTone()).isEqualTo("unshipped");
        assertThat(order("PAID","FULFILLED",false,false).rowClass()).isEqualTo("state-shipped");
        var history=new ShopifyOrderReadRepository.Line(UUID.randomUUID(),null,null,"000001","Product",null,1,1,BigDecimal.TEN,"USD","REPORTING_ONLY","REPORTING_ONLY",null,List.of(),null,null);
        var o=order("PAID","UNFULFILLED",false,false);
        assertThat(new ShopifyOrderReadRepository.Order(o.id(),o.gid(),o.name(),o.created(),o.payment(),o.fulfillment(),o.currency(),o.total(),true,false,null,o.adminUrl(),List.of(history)).pickupEligible()).isFalse();
    }
}
