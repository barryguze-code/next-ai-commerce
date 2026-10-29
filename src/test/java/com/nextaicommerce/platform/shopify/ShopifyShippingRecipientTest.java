package com.nextaicommerce.platform.shopify;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.time.Instant;
class ShopifyShippingRecipientTest {
 final String gid="gid://shopify/Order/1";
 String body(String shop,String order,String address){return "{\"data\":{\"shop\":{\"id\":\""+shop+"\",\"currencyCode\":\"USD\"},\"order\":{\"id\":\""+order+"\",\"cancelledAt\":null,\"displayFulfillmentStatus\":\"FULFILLED\",\"shippingAddress\":"+address+"}}}";}
 ShopifyConnectionService.CatalogResponse response(String body){return new ShopifyConnectionService.CatalogResponse(body,"gid://shopify/Shop/2","USD",Instant.EPOCH);}
 @Test void exactOrderAndShopReturnTransientAddressIncludingUpstreamFulfilledForLocalTestOverride(){var r=ShopifyOrderClient.parseRecipient(response(body("gid://shopify/Shop/2",gid,"{\"name\":\"Test Recipient\",\"phone\":null,\"address1\":\"Test St\",\"address2\":\"Unit 1\",\"city\":\"Test\",\"provinceCode\":\"TN\",\"zip\":\"38103\",\"countryCodeV2\":\"US\"}")),gid);assertThat(r.name()).isEqualTo("Test Recipient");assertThat(r.street2()).isEqualTo("Unit 1");assertThat(r.phone()).isEmpty();assertThat(r.toString()).doesNotContain("Test Recipient","Test St");}
 @Test void otherShopAndOtherOrderCannotSupplyRecipient(){for(String b:java.util.List.of(body("gid://shopify/Shop/3",gid,"{}"),body("gid://shopify/Shop/2","gid://shopify/Order/9","{}")))assertThatThrownBy(()->ShopifyOrderClient.parseRecipient(response(b),gid)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");}
 @Test void deniedOrMissingAddressNeverInventsRecipient(){for(String b:java.util.List.of("{\"errors\":[{\"message\":\"PRIVATE DETAIL\"}]}",body("gid://shopify/Shop/2",gid,"null")))assertThatThrownBy(()->ShopifyOrderClient.parseRecipient(response(b),gid)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("PRIVATE DETAIL");}
 @Test void usesOnlyExplicitOrderPhoneAndAddressNameFieldsWhenAddressPhoneIsMissing(){var b=body("gid://shopify/Shop/2",gid,"{\"firstName\":\"Test\",\"lastName\":\"Recipient\",\"address1\":\"Test St\"}").replace("\"cancelledAt\":null","\"cancelledAt\":null,\"phone\":\"9015550100\"");var r=ShopifyOrderClient.parseRecipient(response(b),gid);assertThat(r.name()).isEqualTo("Test Recipient");assertThat(r.phone()).isEqualTo("9015550100");}
}
