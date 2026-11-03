package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class InventoryControllerSafetyTest {
    private final UUID tenantId=UUID.randomUUID();
    private InventoryRepository inventory;
    private InventoryController controller;
    private Authentication authentication;
    private HttpSession session;

    @BeforeEach
    void setUp(){
        inventory=mock(InventoryRepository.class);
        @SuppressWarnings("rawtypes") ObjectProvider empty=mock(ObjectProvider.class);
        controller=new InventoryController(inventory,empty,empty,empty,empty,empty,empty);
        authentication=mock(Authentication.class);when(authentication.getName()).thenReturn("operator@example.com");
        session=mock(HttpSession.class);when(session.getAttribute("selectedTenantId")).thenReturn(tenantId);
    }

    @Test void shelfDisplayCacheCoalescesReadsSeparatesAccountsAndInvalidates(){
        var cache=new com.nextaicommerce.platform.config.PlatformReadCache(true,java.time.Duration.ofSeconds(10),32);
        controller.configureShelfReads(cache);
        when(inventory.inventory(any(UUID.class))).thenReturn(List.of());
        var model=new org.springframework.ui.ExtendedModelMap();
        controller.inventory(authentication,session,model);controller.inventory(authentication,session,model);
        verify(inventory,org.mockito.Mockito.times(1)).inventory(tenantId);
        UUID other=UUID.randomUUID();when(session.getAttribute("selectedTenantId")).thenReturn(other);
        controller.inventory(authentication,session,model);verify(inventory).inventory(other);
        cache.invalidate();when(session.getAttribute("selectedTenantId")).thenReturn(tenantId);
        controller.inventory(authentication,session,model);verify(inventory,org.mockito.Mockito.times(2)).inventory(tenantId);
    }

    @Test void explicitShortYearCorrectionPreservesOriginalBatchAndQuantity() {
        UUID item=UUID.randomUUID(),location=UUID.randomUUID();var old=LocalDate.of(2026,7,14);
        controller.changeExpiration(session,authentication,item,location,old,LocalDate.of(27,7,14),new BigDecimal("12"),new RedirectAttributesModelMap());
        verify(inventory).changeExpiration(tenantId,"operator@example.com",item,location,old,LocalDate.of(2027,7,14),new BigDecimal("12"),null);
    }

    @Test
    void manualReceiptRequiresExplanationBeforeWriting(){
        var response=controller.inlineReceipt(authentication,session,UUID.randomUUID(),BigDecimal.ONE,UUID.randomUUID(),LocalDate.now()," ");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(inventory,never()).receiveUninvoicedItem(any(),anyString(),any(),any(),any(),any(),any());
    }

    @Test
    void holdIsSavedLocallyWithoutQueuingAnAmazonQuantityChange(){
        var redirect=new RedirectAttributesModelMap();
        controller.action(authentication,session,UUID.randomUUID(),LocalDate.of(2026,9,15),
            "HOLD",null,"Check the batch",null,redirect);

        verify(inventory).applyDisposition(any(),anyString(),any(),any(),any(),
            org.mockito.ArgumentMatchers.eq("HOLD"),any(),any());
        verify(inventory,never()).queueMarketplaceAvailabilityZero(any(),anyString(),any(),any(),anyString());
        assertThat(redirect.getFlashAttributes().get("inventorySuccess").toString())
            .contains("Inventory action saved", "ledger history");
    }

    @Test
    void salePlanIsSavedLocallyWithoutQueuingAnAmazonPriceChange(){
        when(inventory.saveSalePlan(any(),anyString(),any(),any(),any(),
            org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean(),any())).thenReturn(2);
        var redirect=new RedirectAttributesModelMap();
        controller.promotion(authentication,session,UUID.randomUUID(),LocalDate.of(2026,9,15),
            new BigDecimal("10.00"),30,7,false,List.of("10\tSKU-A","12\tSKU-B"),redirect);

        verify(inventory,never()).queueSalePrice(any(),anyString(),any(),any(),any(Instant.class),any(Instant.class));
        assertThat(redirect.getFlashAttributes().get("inventorySuccess").toString())
            .contains("Sale plan saved for 2 Marketplace SKUs","only when production sale publishing is enabled for this account");
    }
}
