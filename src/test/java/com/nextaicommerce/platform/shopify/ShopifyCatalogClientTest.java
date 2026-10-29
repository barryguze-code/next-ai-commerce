package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShopifyCatalogClientTest {
    static final String BODY="""
      {"data":{"shop":{"id":"gid://shopify/Shop/1","currencyCode":"USD"},"productVariants":{"pageInfo":{"hasNextPage":false,"endCursor":"end"},"nodes":[{
      "id":"gid://shopify/ProductVariant/9007199254740993001","sku":"0000123-EAx12","title":"Blue","price":"19.9900","updatedAt":"2026-10-26T12:00:00Z",
      "selectedOptions":[{"name":"Color","value":"Blue"}],"inventoryItem":{"id":"gid://shopify/InventoryItem/99"},"barcodes":{"nodes":[{"type":"EAN","value":"8683650091325"}],"pageInfo":{"hasNextPage":false}},"media":{"nodes":[]},
      "product":{"id":"gid://shopify/Product/9007199254740993000","title":"Test product","vendor":"Brand","status":"ACTIVE","updatedAt":"2026-10-26T12:00:00Z","onlineStoreUrl":null,"featuredMedia":{"image":{"url":"https://cdn.shopify.com/test.png"}}}
      }] }},"extensions":{"cost":{"requestedQueryCost":45,"throttleStatus":{"currentlyAvailable":950,"restoreRate":100}}}}
      """;
    final ShopifyCatalogClient client=new ShopifyCatalogClient(null);
    ShopifyCatalogClient.Page parse(String body,String cursor){return client.parse(new ShopifyConnectionService.CatalogResponse(body,"gid://shopify/Shop/1","USD",Instant.EPOCH),cursor);}
    @Test void preservesExactIdentifiersLeadingZerosPriceAndSeparateProductPhoto(){var p=parse(BODY,null);var v=p.variants().getFirst();assertThat(v.gid()).endsWith("9007199254740993001");assertThat(v.sku()).isEqualTo("0000123-EAx12");assertThat(v.price()).isEqualByComparingTo("19.99");assertThat(v.image()).isNull();assertThat(v.product().image()).isEqualTo("https://cdn.shopify.com/test.png");assertThat(v.barcodes()).contains("8683650091325");assertThat(p.more()).isFalse();}
    @Test void nullAndBlankSkusRemainSourceValues(){assertThat(parse(BODY.replace("\"0000123-EAx12\"","null"),null).variants().getFirst().sku()).isNull();assertThat(parse(BODY.replace("0000123-EAx12",""),null).variants().getFirst().sku()).isEmpty();}
    @Test void missingSkuAndIncompleteBarcodeEvidenceAreRejected(){assertThatThrownBy(()->parse(BODY.replace("\"sku\":\"0000123-EAx12\",",""),null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->parse(BODY.replace("\"pageInfo\":{\"hasNextPage\":false}","\"pageInfo\":{\"hasNextPage\":true}"),null)).isInstanceOf(IllegalArgumentException.class);}
    @Test void mappingBasisIgnoresPriceAndImageButTracksIdentityAndPackInputs(){var a=parse(BODY,null).variants().getFirst();var b=parse(BODY.replace("19.9900","20.00").replace("test.png","other.png"),null).variants().getFirst();assertThat(com.nextaicommerce.platform.marketplace.ShopifySkuMatcher.fingerprint(a)).isEqualTo(com.nextaicommerce.platform.marketplace.ShopifySkuMatcher.fingerprint(b));var changed=parse(BODY.replace("EAx12","EAx3"),null).variants().getFirst();assertThat(com.nextaicommerce.platform.marketplace.ShopifySkuMatcher.fingerprint(changed)).isNotEqualTo(com.nextaicommerce.platform.marketplace.ShopifySkuMatcher.fingerprint(a));}
    @Test void nullableBarcodeTypeIsValidSourceEvidence(){assertThat(parse(BODY.replace("\"type\":\"EAN\"","\"type\":null"),null).variants().getFirst().complete()).isTrue();}
    @Test void foreignShopAndCurrencyCannotPublish(){assertThatThrownBy(()->parse(BODY.replace("Shop/1","Shop/2"),null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->parse(BODY.replace("USD","CAD"),null)).isInstanceOf(IllegalArgumentException.class);}
    @Test void errorsAndInvalidMoneyOrIdentityFailClosed(){for(String body:new String[]{BODY.replace("\"data\":","\"errors\":[],\"data\":"),BODY.replace("19.9900","-1"),BODY.replace("ProductVariant/","Product/"),"{}","not json"})assertThatThrownBy(()->parse(body,null)).isInstanceOf(RuntimeException.class);}
    @Test void nonAdvancingCursorAndMissingCostAreRejected(){assertThatThrownBy(()->parse(BODY.replace("\"hasNextPage\":false,\"endCursor\":\"end\"","\"hasNextPage\":true,\"endCursor\":\"end\""),"end")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->parse(BODY.replace("requestedQueryCost","unknown"),null)).isInstanceOf(IllegalArgumentException.class);}
    @Test void onlyReadProductsFieldsAndPublicShopifyImageHostAreUsed(){assertThat(ShopifyCatalogClient.QUERY).doesNotContain("mutation","orders","inventoryQuantity","inventoryLevels","unitCost");assertThat(ShopifyCatalogClient.image("https://cdn.shopify.com/a.png")).isNotNull();for(String bad:new String[]{"http://cdn.shopify.com/a","https://cdn.shopify.com.evil.test/a","https://user@cdn.shopify.com/a","https://localhost/a"})assertThat(ShopifyCatalogClient.image(bad)).isNull();}
}
