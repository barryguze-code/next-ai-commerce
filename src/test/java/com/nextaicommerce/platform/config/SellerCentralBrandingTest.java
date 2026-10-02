package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SellerCentralBrandingTest {
    @Test void marketplaceLinksUseDestinationSpecificPngs() throws Exception {
        for(String page:java.util.List.of("orders","marketplace-skus","packing-slip")){
            try(var input=getClass().getResourceAsStream("/templates/"+page+".html")){
                assertThat(new String(input.readAllBytes(),StandardCharsets.UTF_8))
                    .contains("/images/platform/table/seller-central.png")
                    .doesNotContain("/images/channels/amazon.svg");
            }
        }
        try(var input=getClass().getResourceAsStream("/static/js/platform-controls.js")){
            assertThat(new String(input.readAllBytes(),StandardCharsets.UTF_8))
                .contains("/images/platform/table/amazon.com-logo.png?v=","'amazon-product'","asset==='seller-central'","/images/platform/table/seller-central.png?v=",
                    "https://sellercentral.","https://www.");
        }
        try(var input=getClass().getResourceAsStream("/static/images/platform/table/amazon.com-logo.png")){
            assertThat(javax.imageio.ImageIO.read(input).getWidth()).isEqualTo(128);
        }
    }
}
