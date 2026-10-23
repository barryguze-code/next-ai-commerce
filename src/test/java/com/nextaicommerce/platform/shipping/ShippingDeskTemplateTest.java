package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShippingDeskTemplateTest {
    @Test void shippingHubDoesNotAdvertiseTheRemovedOrdersEntry() throws Exception {
        String html=Files.readString(Path.of("src/main/resources/templates/shipping.html"));
        assertThat(html).contains("Merchant-fulfilled orders", "Shipping-label purchase is not available from the Orders table.",
            "Review merchant-fulfilled orders and shipping settings.", "<h3>Saved shipment history</h3>", "Saved shipment history is retained.", "/app/orders?f_channel=FBM", "/app/shipping/preferences", "/app/connections/carriers")
            .doesNotContain("Ship directly from Orders", "Open the same order’s Buy Shipping dialog", "data-create-batch", "Choose an order, compare carrier rates", "<h3>Reprint a label</h3>");
    }
}
