package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FedExPackagingTest {
    @Test void dimensionalLegacyProfileMapsPakAndConvertsOunces(){
        var p=new BuyShippingRepository.PackageProfile(null,"Saved package",null,new BigDecimal("12.75"),new BigDecimal("10.25"),new BigDecimal("0.5"),"inches",new BigDecimal("32"),"oz","FedEx","AMBIENT");
        assertThat(FedExPackaging.profile(p).packagingId()).isEqualTo("PAK_SMALL");
        assertThat(FedExPackaging.profile(p).weight()).isEqualByComparingTo("2");
    }
    @Test void approvedMappingsUseDeepBoxesWithoutGuessingUnknownPackages(){
        assertThat(FedExPackaging.legacy("Medium")).isEqualTo("MEDIUM_M2");
        assertThat(FedExPackaging.legacy("FEDEX_LARGE")).isEqualTo("LARGE_L2");
        assertThat(FedExPackaging.legacy("XLarge")).isEqualTo("XLARGE_X1");
        assertThat(FedExPackaging.legacy("XSmall")).isEqualTo("XSMALL");
        assertThat(FedExPackaging.legacy("Small")).isEqualTo("SMALL_S2");
        for(String name:List.of("Pak","FEDEX_PAK","FedEx Padded Pak"))assertThat(FedExPackaging.legacy(name)).isEqualTo("PAK_PADDED");
        for(String name:List.of("UPS OMBox","Box + Pak"))assertThat(FedExPackaging.legacy(name)).isNull();
    }
    @Test void namedPakUsesPaddedDimensionsEvenWhenLegacyDimensionsDiffer(){
        var p=new BuyShippingRepository.PackageProfile(null,"Pak",null,new BigDecimal("12.75"),new BigDecimal("10.25"),new BigDecimal("0.5"),"inches",new BigDecimal("32"),"oz","FedEx","AMBIENT");
        var parcel=FedExPackaging.profile(p);
        assertThat(parcel.packagingId()).isEqualTo("PAK_PADDED");
        assertThat(parcel.length()).isEqualByComparingTo("14.75");
        assertThat(parcel.width()).isEqualByComparingTo("11.75");
        assertThat(parcel.weight()).isEqualByComparingTo("2");
        assertThat(FedExPackaging.legacy("PAK_SMALL")).isEqualTo("PAK_SMALL");
    }
    @Test void allVariantsReachRateAndShipWithServerControlledDimensions(){
        var json=new ObjectMapper();var a=new FedExPayloads.Address("Test","9015550100","10 Test Street","Memphis","TN","38116","US",true);
        assertThat(FedExPackaging.OPTIONS).hasSize(17);
        for(var o:FedExPackaging.OPTIONS){
            var p=new FedExPayloads.Parcel(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,o.id());
            var normalized=FedExPackaging.normalize(p);
            assertThat(normalized.length()).isEqualTo(o.length()==null?BigDecimal.ONE:o.length());
            var rate=FedExPayloads.rateRequest(json,"123456789",a,a,p);
            assertThat(rate.path("requestedShipment").path("packagingType").asText()).isEqualTo(o.apiType());
            assertThat(FedExPayloads.shipRequest(rate,"FEDEX_2_DAY").path("requestedShipment").path("packagingType").asText()).isEqualTo(o.apiType());
        }
    }
    @Test void unknownIdsRejectedAndOldJsonRemainsReadable(){
        assertThatThrownBy(()->FedExPackaging.normalize(new FedExPayloads.Parcel(null,null,null,BigDecimal.ONE,"ARBITRARY"))).isInstanceOf(IllegalArgumentException.class);
        var old=new ObjectMapper().readValue("{\"length\":10,\"width\":8,\"height\":4,\"weight\":2}",FedExPayloads.Parcel.class);
        assertThat(FedExPackaging.normalize(old).packagingId()).isEqualTo("YOUR_PACKAGING");
    }
    @Test void exactCompositionAggregatesDuplicateSkuLinesAndIgnoresOrder(){
        var a=new BuyShippingRepository.OrderItem("1","SKU-A","ASIN","A",1,0);
        var b=new BuyShippingRepository.OrderItem("2","SKU-B","ASIN","B",3,0);
        var a2=new BuyShippingRepository.OrderItem("3","SKU-A","ASIN","A",2,0);
        var combined=new BuyShippingRepository.OrderItem("4","SKU-A","ASIN","A",3,0);
        assertThat(FedExShippingService.key(context(List.of(a,b,a2)))).isEqualTo(FedExShippingService.key(context(List.of(b,combined))));
        assertThat(FedExShippingService.key(context(List.of(a,b)))).isNotEqualTo(FedExShippingService.key(context(List.of(b,combined))));
        assertThat(FedExShippingService.legacySignature(context(List.of(a,b,a2)))).isEqualTo("SKU-A-3,SKU-B-3");
    }
    private BuyShippingService.Context context(List<BuyShippingRepository.OrderItem> items){return new BuyShippingService.Context(null,null,items,List.of(),List.of(),null,List.of(),java.util.Map.of());}
}
