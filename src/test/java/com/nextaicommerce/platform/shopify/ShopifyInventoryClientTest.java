package com.nextaicommerce.platform.shopify;
import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ShopifyInventoryClientTest {
 final ShopifyConnectionService transport=mock(ShopifyConnectionService.class);
 final ShopifyInventoryClient client=new ShopifyInventoryClient(transport);
 final ShopifyInventoryClient.Target target=new ShopifyInventoryClient.Target(UUID.randomUUID(),UUID.randomUUID(),"gid://shopify/Shop/1","gid://shopify/InventoryItem/2","gid://shopify/Location/3");
 void reply(String body){when(transport.inventory(any(),any(),anyString(),anyString(),any(),anyBoolean())).thenReturn(new ShopifyConnectionService.CatalogResponse(body,target.shop(),"USD",java.time.Instant.EPOCH));}
 @Test void unknownOrForeignLocationNeverBecomesZero(){
  reply("{\"data\":{\"shop\":{\"id\":\"gid://shopify/Shop/1\"},\"inventoryItem\":null}}");
  assertThatThrownBy(()->client.available(target,()->{})).isInstanceOf(IllegalStateException.class);
 }
 @Test void mutationAlwaysCarriesImmutableIdempotencyAndCompareQuantity(){
  UUID operation=UUID.randomUUID();reply("{\"data\":{\"inventorySetQuantities\":{\"userErrors\":[],\"inventoryAdjustmentGroup\":{\"referenceDocumentUri\":\"nextaicommerce://inventory-publication/"+operation+"\"}}}}");
  client.setAvailable(target,7,4,operation,()->{});
  var body=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(transport).inventory(eq(target.tenant()),eq(target.connection()),eq(target.shop()),body.capture(),any(),eq(true));
  var variables=new ObjectMapper().readTree(body.getValue()).path("variables");
  assertThat(variables.path("idempotencyKey").asText()).isEqualTo(operation.toString());
  var quantity=variables.path("input").path("quantities").get(0);
  assertThat(quantity.path("changeFromQuantity").intValue()).isEqualTo(7);
  assertThat(quantity.path("quantity").intValue()).isEqualTo(4);
  assertThat(quantity.path("locationId").asText()).isEqualTo(target.location());
 }
 @Test void concurrentSaleRequiresReconciliationInsteadOfOverwriting(){
  reply("{\"data\":{\"inventorySetQuantities\":{\"userErrors\":[{\"code\":\"CHANGE_FROM_QUANTITY_STALE\"}]}}}");
  assertThatThrownBy(()->client.setAvailable(target,7,4,UUID.randomUUID(),()->{})).isInstanceOf(ShopifyInventoryClient.StaleQuantity.class);
  verify(transport,times(1)).inventory(any(),any(),anyString(),anyString(),any(),eq(true));
 }
 @Test void missingSubmissionReceiptIsNotSuccess(){
  reply("{\"data\":{\"inventorySetQuantities\":{\"userErrors\":[]}}}");
  assertThatThrownBy(()->client.setAvailable(target,7,4,UUID.randomUUID(),()->{})).isInstanceOf(IllegalStateException.class);
 }
}
