package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MarketplaceSkuAutoMapperTest {
    @Test void readsPackQuantityFromSupportedSellerSkuEndings(){
        assertPack("IB-MKH-130789-APPLE-2xEA",2,"130789");
        assertPack("IB-MKH-130789-APPLE-2XEA",2,"130789");
        assertPack("IB-MKH-130789-APPLE-EAx2",2,"130789");
        assertPack("IB-MKH-130789-APPLE-EAX2",2,"130789");
    }

    @Test void defaultsToOneEachWhenSkuHasNoPackEnding(){
        var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-00130789-APPLE");
        assertThat(parsed.quantityPerMarketplaceUnit()).isEqualTo(1);
        assertThat(parsed.itemCodeTokens()).contains("130789");
    }

    @Test void readsAccountSkuAndPackFromTonysSellerSku(){
        var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-337404-DRKMLKPRTZLTF-TONYS-15XEA");
        assertThat(parsed.quantityPerMarketplaceUnit()).isEqualTo(15);
        assertThat(parsed.itemCodeTokens()).contains("337404");
    }

    @Test void readsValidatedMultiProductBundleComponents(){
        var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-2x354920-2x375283-EGGLIFE-4xEA");
        assertThat(parsed.quantityPerMarketplaceUnit()).isEqualTo(4);
        assertThat(parsed.validBundleTotal()).isTrue();
        assertThat(parsed.bundleComponents()).containsExactly(
            new MarketplaceSkuAutoMapper.ParsedComponent("354920",2),
            new MarketplaceSkuAutoMapper.ParsedComponent("375283",2));
    }

    @Test void rejectsBundleWhenComponentQuantitiesDoNotMatchPackTotal(){
        var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-2x354920-2x375283-EGGLIFE-5xEA");
        assertThat(parsed.validBundleTotal()).isFalse();
    }

    @Test void readsImplicitOneEachComponentsAndValidatesPackTotal(){
        var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-467936-467928-OZERY-2xEA");
        assertThat(parsed.quantityPerMarketplaceUnit()).isEqualTo(2);
        assertThat(parsed.explicitBundle()).isFalse();
        assertThat(parsed.validBundleTotal()).isTrue();
        assertThat(parsed.bundleComponents()).containsExactly(
            new MarketplaceSkuAutoMapper.ParsedComponent("467936",1),
            new MarketplaceSkuAutoMapper.ParsedComponent("467928",1));
    }

    @Test void rejectsImplicitBundleWhenCodesDoNotMatchPackTotal(){
        var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-467936-467928-OZERY-3xEA");
        assertThat(parsed.validBundleTotal()).isFalse();
    }

    private static void assertPack(String sku,int quantity,String itemCode){
        var parsed=MarketplaceSkuAutoMapper.parse(sku);
        assertThat(parsed.quantityPerMarketplaceUnit()).isEqualTo(quantity);
        assertThat(parsed.itemCodeTokens()).contains(itemCode);
    }
}
