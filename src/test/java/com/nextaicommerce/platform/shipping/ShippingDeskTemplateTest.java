package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ShippingDeskTemplateTest {
    @Test void exposesDurableBulkAndReprintWorkflowsInPlainLanguage() throws Exception {
        String html=Files.readString(Path.of("src/main/resources/templates/shipping.html"));
        assertThat(html).contains("Package-ready orders","Create shipping batch","Shipping batches","Purchased labels",
            "Print batch again","Purchase batch labels","Cold-chain shipping policy","Never use services containing");
    }
}
