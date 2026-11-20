package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;

import com.nextaicommerce.platform.receiving.InventoryRepository.InventoryView;
import com.nextaicommerce.platform.receiving.InventoryRepository.LedgerView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryShelfLifeTest {
    private static final LocalDate TODAY=LocalDate.of(2026,8,28);

    @Test void appliesSellableAndWarningBoundariesFromToday(){
        assertThat(position(TODAY.minusDays(1)).shelfStatus(TODAY,10,30)).isEqualTo("EXPIRED");
        assertThat(position(TODAY.plusDays(10)).shelfStatus(TODAY,10,30)).isEqualTo("CANNOT_SELL");
        assertThat(position(TODAY.plusDays(11)).shelfStatus(TODAY,10,30)).isEqualTo("ACT_SOON");
        assertThat(position(TODAY.plusDays(30)).shelfStatus(TODAY,10,30)).isEqualTo("ACT_SOON");
        assertThat(position(TODAY.plusDays(31)).shelfStatus(TODAY,10,30)).isEqualTo("HEALTHY");
        assertThat(position(null).shelfStatus(TODAY,10,30)).isEqualTo("UNDATED");
    }

    @Test void presentsHumanReadableCountdownsAndActionPlans(){
        assertThat(position(TODAY).expirationMessage(TODAY)).isEqualTo("Expires today");
        assertThat(position(TODAY.plusDays(1)).expirationMessage(TODAY)).isEqualTo("1 day left");
        assertThat(position(TODAY.minusDays(2)).expirationMessage(TODAY)).isEqualTo("2 days expired");
        var planned=new InventoryView(UUID.randomUUID(),"Product","SKU","UPC",TODAY.plusDays(40),
            BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ONE,"USD","FEFO","DONATE","Food bank","FINAL",
            BigDecimal.TEN,null,null);
        assertThat(planned.actionLabel()).isEqualTo("Donation planned");
        assertThat(planned.marketplaceStoppedByPlan()).isTrue();
        assertThat(planned.shelfStatus(TODAY,10,30)).isEqualTo("HEALTHY");
        var held=new InventoryView(UUID.randomUUID(),"Product","SKU","UPC",TODAY.plusDays(40),
            BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ONE,"USD","FEFO","HOLD","Review later","FINAL",
            BigDecimal.TEN,null,null);
        assertThat(held.shelfStatus(TODAY,10,30)).isEqualTo("HEALTHY");
        assertThat(new InventoryRepository.ShelfLifePolicy(10,30).autoZeroMarketplaceSellable()).isTrue();
        assertThat(new InventoryRepository.ShelfLifePolicy(10,30).defaultSaleDiscountPercent())
            .isEqualByComparingTo("10.00");
    }

    @Test void linksAmazonLedgerMovementsByHumanReadableOrderId(){
        var movement=new LedgerView(UUID.randomUUID(),UUID.randomUUID(),Instant.now(),"Product","SKU","UPC",
            "SHIPMENT",BigDecimal.ONE.negate(),null,BigDecimal.ONE,"USD","AMAZON_ORDER",
            "111-1234567-1234567","Amazon reported that the order left the warehouse.",null,"FINAL");
        assertThat(movement.sourceLabel()).isEqualTo("Amazon order");
        assertThat(movement.sourceUrl()).isEqualTo("/app/orders?q=111-1234567-1234567");
    }

    @Test void keepsRealAmazonMixedCaseStatusesVisibleAsReservations(){
        assertThat(InventoryRepository.RESERVABLE_ORDER_STATUS_SQL)
            .isEqualTo("upper(trim(coalesce(orders.order_status,''))) IN ('PENDING','UNSHIPPED')")
            .doesNotContain("regexp_replace");
    }

    private static InventoryView position(LocalDate expiration){
        return new InventoryView(UUID.randomUUID(),"Product","SKU","UPC",expiration,BigDecimal.TEN,
            BigDecimal.ZERO,BigDecimal.ONE,"USD",expiration==null?"FIFO":"FEFO",null,null,"FINAL",BigDecimal.TEN,null,null);
    }
}
