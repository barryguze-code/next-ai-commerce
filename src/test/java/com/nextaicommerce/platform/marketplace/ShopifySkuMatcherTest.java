package com.nextaicommerce.platform.marketplace;
import java.util.*;import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
class ShopifySkuMatcherTest {
 final UUID a=new UUID(0,1),b=new UUID(0,2);
 ShopifySkuMatcher.Item item(UUID id,String code,String gtin){return new ShopifySkuMatcher.Item(id,"Pizza",Set.of(code),gtin==null?Set.of():Set.of(Objects.requireNonNull(BarcodePackRules.gtin(gtin))));}
 @Test void existingPackPolicySupportsEachQuantityAndLeadingZeroCode(){for(int n:List.of(1,3,12)){var match=ShopifySkuMatcher.match("IB-MKH-0000123-PIZZA-EAx"+n,"Pizza",List.of(),true,false,List.of(item(a,"123",null)));assertThat(match.components().get(a)).isEqualByComparingTo(Integer.toString(n));}}
 @Test void uniqueValidatedGtinCanMatchOwnEachProduct(){var m=ShopifySkuMatcher.match("different-EAx3","Pizza",List.of("8683650091325"),true,false,List.of(item(a,"123","8683650091325")));assertThat(m.components().get(a)).isEqualByComparingTo("3");}
 @Test void contradictionsDuplicatesMissingCodesAndIncompleteEvidenceCannotGuess(){var items=List.of(item(a,"123","8683650091325"),item(b,"456",null));for(var m:List.of(ShopifySkuMatcher.match("456-EA","Pizza",List.of("8683650091325"),true,false,items),ShopifySkuMatcher.match("123-EA","Pizza",List.of(),true,true,items),ShopifySkuMatcher.match("123-EA","Pizza",List.of(),false,false,items),ShopifySkuMatcher.match(null,"Pizza",List.of("8683650091325"),true,false,items),ShopifySkuMatcher.match("no-match","Pizza",List.of(),true,false,items)))assertThat(m.components()).isEmpty();}
 @Test void ambiguousCodesUnsafePacksAndInvalidBarcodesStayUnresolved(){assertThat(ShopifySkuMatcher.match("123-EA","Pizza",List.of(),true,false,List.of(item(a,"123",null),item(b,"123",null))).components()).isEmpty();for(String bad:List.of("123-EAx0","123-EAx10001"))assertThat(ShopifySkuMatcher.match(bad,"Pizza",List.of(),true,false,List.of(item(a,"123",null))).components()).isEmpty();assertThat(ShopifySkuMatcher.match("other","Pizza pack of 3",List.of("8683650091324"),true,false,List.of(item(a,"123","8683650091325"))).components()).isEmpty();}
 @Test void manualQuantityValidationPreservesWholeComponentsAndBounds(){assertThat(ShopifySkuRepository.validate(List.of(a.toString(),a.toString()),List.of("3","12")).get(a)).isEqualByComparingTo("15");for(String q:List.of("0","-1","1.5","10001","NaN"))assertThatThrownBy(()->ShopifySkuRepository.validate(List.of(a.toString()),List.of(q))).isInstanceOf(IllegalArgumentException.class);}
 @Test void nativeLinksUseStringIdentifiersAndExactShopHost(){assertThat(ShopifySkuRepository.admin("unit.myshopify.com","gid://shopify/Product/9007199254740993000","gid://shopify/ProductVariant/9007199254740993001")).isEqualTo("https://unit.myshopify.com/admin/products/9007199254740993000/variants/9007199254740993001");assertThat(ShopifySkuRepository.admin("evil.com","gid://shopify/Product/1","gid://shopify/ProductVariant/2")).isNull();}
 @Test void ozeryUniqueAccountCodeUsesSixEachesAndSharedAmazonParser(){
  var parsed=MarketplaceSkuAutoMapper.parse("IB-MKH-342934-SNACKING-OZERY-6xEA");
  assertThat(parsed.itemCodeTokens()).contains("342934");assertThat(parsed.quantityPerMarketplaceUnit()).isEqualTo(6);
  var match=ShopifySkuMatcher.match("IB-MKH-342934-SNACKING-OZERY-6xEA","Snacking Rounds",List.of(),true,false,List.of(item(a,"342934",null)));
  assertThat(match.components()).containsOnly(entry(a,new java.math.BigDecimal("6")));
 }
 @Test void ozeryDuplicateAccountCodesRemainUnresolvedRatherThanTreatingOuncesAsEaches(){
  var ounce=new ShopifySkuMatcher.Item(a,"Muesli Snacking Rounds",Set.of("342934"),Set.of());
  var each=new ShopifySkuMatcher.Item(b,"Muesli Snacking Rounds",Set.of("342934"),Set.of());
  var m=ShopifySkuMatcher.match("IB-MKH-342934-SNACKING-OZERY-6xEA","Ozery Bakery Snacking Rounds (Pack of 6)",List.of("664164101982"),true,false,List.of(ounce,each));
  assertThat(m.components()).isEmpty();assertThat(m.state()).isEqualTo("Conflicting account identifiers");
 }
}
