package com.nextaicommerce.platform.receiving;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class InventoryLedgerSourceTest {
    private static InventoryRepository.LedgerView row(String source){return new InventoryRepository.LedgerView(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH,"Product","000001","012345678905","SHIPMENT",BigDecimal.ONE.negate(),null,null,"USD",source,"reference","Shopify order mentioned in manual notes",null,"FINAL");}
    @Test void onlyExplicitMarketplaceSourcesGetTheirExistingLogo(){
        assertThat(row("AMAZON_ORDER").sourceLogo()).isEqualTo("/images/platform/table/amazon.com-logo.png");
        assertThat(row("SHOPIFY_ORDER").sourceLogo()).isEqualTo("/images/channels/shopify-bag.png");
        assertThat(row("SHOPIFY_ORDER").sourceLabel()).isEqualTo("Shopify order");
        assertThat(row("WALMART_ORDER").sourceLogo()).isEqualTo("/images/channels/walmart.svg");
        for(String source:new String[]{"RECEIVING","MANUAL","ADJUSTMENT","PHYSICAL_COUNT","UNKNOWN"})assertThat(row(source).sourceLogo()).isNull();
    }
}
