package com.nextaicommerce.platform.shipping;

import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;

class FedExShopifySandboxTest {
 final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),carrier=UUID.randomUUID(),quote=UUID.randomUUID();final String order=UUID.randomUUID().toString();
 final ObjectMapper json=new ObjectMapper();
 final BuyShippingService amazon=mock(BuyShippingService.class);final CarrierOrderContext adapter=mock(CarrierOrderContext.class);
 final CarrierConnectionRepository connections=mock(CarrierConnectionRepository.class);final CarrierConnectionService credentials=mock(CarrierConnectionService.class);
 final FedExShipmentRepository repository=mock(FedExShipmentRepository.class);final FedExClient client=mock(FedExClient.class);final ShippingLabelCrypto crypto=mock(ShippingLabelCrypto.class);
 final CarrierLabelComposer composer=mock(CarrierLabelComposer.class);final CarrierShipmentConfirmation confirmation=mock(CarrierShipmentConfirmation.class);
 final ShippingPreferences preferences=mock(ShippingPreferences.class);final OrderRecipientService recipients=mock(OrderRecipientService.class);
 FedExShippingService service;
 @BeforeEach void setup(){
  service=new FedExShippingService(amazon,connections,credentials,repository,client,crypto,composer,json,confirmation,preferences,recipients);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"carrierOrders",adapter);
  var info=new BuyShippingRepository.OrderInfo(order,"Unshipped","MERCHANT",null,null,null,null,null);
  when(adapter.context(tenant,store,order)).thenReturn(new BuyShippingService.Context(null,info,List.of(new BuyShippingRepository.OrderItem("line","00001-EAx3",null,"Product",1,0)),List.of(),List.of(),null,List.of(),Map.of()));
  when(adapter.shopify(tenant,store)).thenReturn(true);when(connections.list(tenant)).thenReturn(List.of());when(repository.list(tenant,store,order)).thenReturn(List.of());
 }
 @Test void shopifyContextNeverImportsRecipientsOrCallsAmazon(){
  assertThat(service.context(tenant,store,order).recipient().status()).isEqualTo("TEST_RECIPIENT_REQUIRED");
  verifyNoInteractions(amazon,recipients,client,confirmation);
 }
 @Test void liveCarrierIsRejectedBeforeCredentialsOrNetwork(){
  when(crypto.configured()).thenReturn(true);
  when(connections.find(tenant,carrier)).thenReturn(new CarrierConnectionRepository.Connection(carrier,"Live","123","PRODUCTION","MERCHANT",1,null,null,null));
  assertThatThrownBy(()->service.quote(tenant,store,order,new FedExShippingService.QuoteInput(carrier,null,null,null,false,true),"actor")).isInstanceOf(IllegalStateException.class).hasMessageContaining("sandbox");
  verifyNoInteractions(credentials,client,recipients,amazon);
 }
 @Test void replayedSandboxPurchaseDoesNotCallCarrierOrStockWriter(){
  when(repository.list(tenant,store,order)).thenReturn(List.of(shipment("PURCHASED")));
  assertThat(service.purchase(tenant,store,order,quote,"FEDEX_GROUND").state()).isEqualTo("PURCHASED");
  verifyNoInteractions(client,credentials,confirmation,amazon,recipients);
 }
 @Test void existingProtectedLabelIsReusedAcrossQuotesAndUnknownOutcomeBlocks(){
  when(repository.protectedShipment(tenant,store,order)).thenReturn(shipment("PURCHASED"));
  assertThat(service.purchase(tenant,store,order,UUID.randomUUID(),"FEDEX_GROUND").id()).isEqualTo(quote);
  verifyNoInteractions(client,credentials,confirmation,amazon,recipients);
  when(repository.protectedShipment(tenant,store,order)).thenReturn(shipment("UNKNOWN"));
  assertThatThrownBy(()->service.purchase(tenant,store,order,UUID.randomUUID(),"FEDEX_GROUND")).isInstanceOf(IllegalStateException.class).hasMessageContaining("review");
  verify(repository,never()).claim(any(),any(),any(),any(),any(),anyLong());
 }
 @Test void explicitTestContextIsRejectedUnlessLabelTestFlagIsEnabled(){
  var info=new BuyShippingRepository.OrderInfo(order,"SandboxLabelTest","MERCHANT",null,null,null,null,null);
  when(adapter.context(tenant,store,order)).thenReturn(new BuyShippingService.Context(null,info,List.of(new BuyShippingRepository.OrderItem("line","SKU",null,"Product",3,0)),List.of(),List.of(),null,List.of(),Map.of()));
  assertThatThrownBy(()->service.context(tenant,store,order)).isInstanceOf(IllegalStateException.class);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"labelTest",true);
  assertThat(service.context(tenant,store,order).recipient().status()).isEqualTo("TEST_RECIPIENT_REQUIRED");verifyNoInteractions(amazon,client,recipients);
 }
 @Test void sandboxPurchaseArchivesOriginalWithoutMarketplaceConfirmation(){
  when(repository.list(tenant,store,order)).thenReturn(List.of(shipment("RATED")),List.of(shipment("PURCHASED")));
  var c=new CarrierConnectionRepository.Connection(carrier,"Sandbox","123","SANDBOX","MERCHANT",1,null,null,null);when(connections.find(tenant,carrier)).thenReturn(c);
  var keys=new FedExClient.Credentials("fixture","fixture");when(credentials.credentials(tenant,c)).thenReturn(keys);
  byte[] cipher={1},nonce={2};when(repository.claim(tenant,store,order,quote,"FEDEX_GROUND",1)).thenReturn(new ShippingLabelCrypto.Encrypted(cipher,nonce));
  when(crypto.decrypt(cipher,nonce)).thenReturn("{\"requestedShipment\":{\"recipient\":{}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var response=json.readTree("{\"output\":{\"transactionShipments\":[{\"pieceResponses\":[{\"trackingNumber\":\"123456789012\",\"packageDocuments\":[{\"contentType\":\"LABEL\",\"docType\":\"PDF\",\"encodedLabel\":\"JVBERi1maXh0dXJl\"}]}]}]}}");
  when(client.ship(eq(tenant),eq(carrier),eq(1L),eq(FedExClient.Environment.SANDBOX),eq(keys),any())).thenReturn(response);
  when(crypto.encrypt(any(byte[].class))).thenReturn(new ShippingLabelCrypto.Encrypted(cipher,nonce));
  assertThat(service.purchase(tenant,store,order,quote,"FEDEX_GROUND").state()).isEqualTo("PURCHASED");
  verify(repository).purchased(eq(tenant),eq(quote),eq("123456789012"),any(),isNull());verifyNoInteractions(confirmation,amazon,recipients);
 }
 FedExShipmentRepository.Shipment shipment(String state){return new FedExShipmentRepository.Shipment(quote,carrier,1,order,"SANDBOX",state,json.createArrayNode(),json.createArrayNode(),null,null);}
}
