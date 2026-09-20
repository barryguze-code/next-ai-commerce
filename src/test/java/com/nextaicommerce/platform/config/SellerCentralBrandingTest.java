package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SellerCentralBrandingTest {
    @Test void sellerShortcutsUseTheSuppliedLogoWithoutChangingShoppingLinks() throws Exception {
        for(String page:java.util.List.of("orders","marketplace-skus","packing-slip")){
            try(var input=getClass().getResourceAsStream("/templates/"+page+".html")){
                assertThat(new String(input.readAllBytes(),StandardCharsets.UTF_8))
                    .contains(page.equals("orders")?"/images/platform/table/seller-central.png":"/images/channels/amazon-seller.png")
                    .doesNotContain("/images/channels/amazon.svg");
            }
        }
        try(var input=getClass().getResourceAsStream("/static/js/platform-controls.js")){
            assertThat(new String(input.readAllBytes(),StandardCharsets.UTF_8))
                .contains("asset==='amazon'?'/images/channels/amazon-seller.png'","'amazon-product'");
        }
        try(var input=getClass().getResourceAsStream("/static/images/channels/amazon-seller.png")){
            assertThat(javax.imageio.ImageIO.read(input).getWidth()).isEqualTo(200);
        }
    }
}
