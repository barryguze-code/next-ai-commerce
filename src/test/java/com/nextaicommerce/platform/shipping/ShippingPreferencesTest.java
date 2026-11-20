package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ShippingPreferencesTest {
    @Test void rejectsLetterWithThermalBottomSlip(){assertThatThrownBy(()->new ShippingPreferences.Settings("LETTER",CarrierLabelComposer.Layout.COMBINED_80_20)).isInstanceOf(IllegalArgumentException.class);}
    @Test void rejectsUnsupportedSettings(){assertThatThrownBy(()->new ShippingPreferences.Settings(null,null)).isInstanceOf(IllegalArgumentException.class);}
    @Test void packageMustCoverAllRemainingItemsBeforeQueueSave(){
        var repository=mock(BuyShippingRepository.class);var service=spy(new BuyShippingService(repository,mock(AmazonMerchantFulfillmentClient.class),new ObjectMapper(),mock(ShippingLabelCrypto.class),mock(ShippingPreferences.class),new CarrierLabelComposer()));
        UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
        var context=new BuyShippingService.Context(null,new BuyShippingRepository.OrderInfo("ORDER","Unshipped","MFN","US",null,null,null,null),List.of(new BuyShippingRepository.OrderItem("ITEM","SKU","ASIN","Product",2,0)),List.of(),List.of(),null,List.of(),Map.of());
        doReturn(context).when(service).context(tenant,store,"ORDER");
        var box=new BuyShippingService.PackageInput(null,"Box",null,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE,"inches",BigDecimal.ONE,"oz",null,"AMBIENT",false,List.of(new BuyShippingService.ItemQuantity("ITEM",1)));
        assertThatThrownBy(()->service.savePackage(tenant,store,"ORDER",box)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
    @Test void letterKeepsPageSizeWithoutScalingCarrier()throws Exception {
        var fixture=new CarrierLabelComposerTest();
        try(var result=org.apache.pdfbox.Loader.loadPDF(new CarrierLabelComposer().letter(fixture.carrier()))){assertThat(result.getPage(0).getMediaBox().getWidth()).isEqualTo(612);assertThat(result.getPage(0).getMediaBox().getHeight()).isEqualTo(792);}
    }
}
