package com.nextaicommerce.platform.orders;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
class OrderDisplayTest {
    @Test void usTimesUsePacificIncludingDaylightSavingAndDateBoundary(){
        var format=OrderController.orderTime("ATVPDKIKX0DER");
        assertThat(format.format(Instant.parse("2026-09-13T19:22:00Z"))).isEqualTo("Sep 13 · 12:22 PM PDT");
        assertThat(format.format(Instant.parse("2026-01-13T19:22:00Z"))).isEqualTo("Jan 13 · 11:22 AM PST");
        assertThat(format.format(Instant.parse("2026-09-13T01:22:00Z"))).isEqualTo("Sep 12 · 6:22 PM PDT");
    }
    @Test void sellerIconTargetsInventoryForTheAsin(){
        var item=new OrderRepository.OrderItemView(null,"sku","B088WPRMDH","Pretzels",1,0,null,null,null,null,null,null,null,null,null,null,null);
        assertThat(item.inventoryUrl("ATVPDKIKX0DER")).isEqualTo("https://sellercentral.amazon.com/myinventory/inventory?searchTerm=B088WPRMDH");
        assertThat(item.inventoryUrl("A1F83G8C2ARO7P")).startsWith("https://sellercentral.amazon.co.uk/");
    }
}
