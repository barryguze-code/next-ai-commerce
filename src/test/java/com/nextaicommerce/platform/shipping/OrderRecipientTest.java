package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import com.nextaicommerce.platform.sync.OrderPiiRedactor;

class OrderRecipientTest {
    @Test void shippingResponsesCannotBeCached()throws Exception {
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/app/orders/114-1229726-6987419/buy-shipping/fedex");var response=new org.springframework.mock.web.MockHttpServletResponse();
        new ShippingPrivacyFilter().doFilter(request,response,new org.springframework.mock.web.MockFilterChain());
        assertThat(response.getHeader("Cache-Control")).contains("no-store");assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
    @Test void invalidRequestDoesNotEchoParserDetails(){assertThat(new ShippingPrivacyAdvice().invalidInput(new IllegalArgumentException("Customer confidential detail")).getBody().toString()).doesNotContain("confidential");}
    final ObjectMapper json=new ObjectMapper();
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
    final String order="114-1229726-6987419";
    ShippingLabelCrypto crypto(){return new ShippingLabelCrypto(Base64.getEncoder().encodeToString(new byte[32]));}
    OrderRecipient sample(){return new OrderRecipient("Test Customer","5551234567","123 Example St","Unit 7","Building B","Seattle","WA","98101","US","RESIDENTIAL");}
    @Test void suppressedFieldsRemainEmptyAndNeverUseBuyerName(){
        var parsed=OrderRecipient.parse(json.readTree("{\"order\":{\"orderId\":\""+order+"\",\"buyer\":{\"buyerName\":\"Do not use\"},\"recipient\":{\"deliveryAddress\":{\"city\":\"Seattle\"}}}}"),order);
        assertThat(parsed.name()).isEmpty();assertThat(parsed.phone()).isEmpty();assertThat(parsed.complete()).isFalse();
        assertThat(parsed.toString()).doesNotContain("Seattle");
    }
    @Test void mismatchedResponseIsRejected(){assertThatThrownBy(()->OrderRecipient.parse(json.readTree("{\"order\":{\"orderId\":\"OTHER\"}}"),order)).isInstanceOf(IllegalArgumentException.class);}
    @Test void ciphertextUsesUniqueNoncesAndIsBoundToTenantStoreAndOrder(){
        var crypto=crypto();byte[] plain="Test Customer".getBytes(StandardCharsets.UTF_8),aad=OrderRecipientService.context(tenant,store,order);
        var a=crypto.encrypt(plain,aad);var b=crypto.encrypt(plain,aad);
        assertThat(a.nonce()).isNotEqualTo(b.nonce());assertThat(a.payload()).isNotEqualTo(plain);
        assertThat(crypto.decrypt(a.payload(),a.nonce(),aad)).isEqualTo(plain);
        assertThatThrownBy(()->crypto.decrypt(a.payload(),a.nonce(),OrderRecipientService.context(UUID.randomUUID(),store,order))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->crypto.decrypt(a.payload(),a.nonce(),OrderRecipientService.context(tenant,UUID.randomUUID(),order))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->crypto.decrypt(a.payload(),a.nonce(),OrderRecipientService.context(tenant,store,"other"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void deniedFetchSetsSafeStatusWithoutStoringResponse(){
        var repository=mock(OrderRecipientRepository.class);var amazon=mock(AmazonSpApiClient.class);
        var command=new OrderRecipientRepository.Command(tenant,store,order,"ATVPDKIKX0DER",UUID.randomUUID());when(repository.claim(tenant)).thenReturn(command);
        when(amazon.recipient(any(),any(),anyString(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(403,null,"SECRET CUSTOMER DETAIL"));
        new OrderRecipientService(repository,crypto(),json,amazon,true).process(tenant);
        verify(repository).failed(command,true);verify(repository,never()).complete(any(),any(),any());
    }
    @Test void responseIsEncryptedBeforePersistence(){
        var repository=mock(OrderRecipientRepository.class);var amazon=mock(AmazonSpApiClient.class);var command=new OrderRecipientRepository.Command(tenant,store,order,"ATVPDKIKX0DER",UUID.randomUUID());when(repository.claim(tenant)).thenReturn(command);when(amazon.recipient(any(),any(),anyString(),anyString())).thenReturn(sample());
        new OrderRecipientService(repository,crypto(),json,amazon,true).process(tenant);
        var captured=org.mockito.ArgumentCaptor.forClass(ShippingLabelCrypto.Encrypted.class);verify(repository).complete(eq(command),eq("AVAILABLE"),captured.capture());
        assertThat(new String(captured.getValue().payload(),StandardCharsets.UTF_8)).doesNotContain("Test Customer","123 Example");
        var plain=crypto().decrypt(captured.getValue().payload(),captured.getValue().nonce(),OrderRecipientService.context(tenant,store,order));assertThat(json.readValue(plain,OrderRecipient.class)).isEqualTo(sample());
    }
    @Test void disabledPipelineNeverFetchesOrDecrypts(){var repository=mock(OrderRecipientRepository.class);var amazon=mock(AmazonSpApiClient.class);var service=new OrderRecipientService(repository,crypto(),json,amazon,false);service.process(tenant);service.enqueue(tenant,store,List.of(order));assertThat(service.get(tenant,store,order).status()).isEqualTo("DISABLED");verifyNoInteractions(repository,amazon);}
    @Test void archivedOrdersHaveNoBuyerOrRecipientData(){var input=json.readTree("{\"payload\":{\"Orders\":[{\"AmazonOrderId\":\"123\",\"ShippingAddress\":{\"Name\":\"SECRET\"},\"BuyerInfo\":{\"BuyerEmail\":\"SECRET\"}}]}}");assertThat(OrderPiiRedactor.redact(input).toString()).contains("AmazonOrderId").doesNotContain("SECRET","ShippingAddress","BuyerInfo");assertThat(input.toString()).contains("SECRET");}
    @Test void carrierMappingPreservesAdditionalAddressLines(){
        var repository=mock(OrderRecipientRepository.class);var crypto=crypto();var encrypted=crypto.encrypt(json.writeValueAsBytes(sample()),OrderRecipientService.context(tenant,store,order));when(repository.read(tenant,store,order)).thenReturn(new OrderRecipientRepository.Stored("AVAILABLE",encrypted.payload(),encrypted.nonce()));
        var address=new OrderRecipientService(repository,crypto,json,mock(AmazonSpApiClient.class),true).forShipping(tenant,store,order);
        assertThat(address.street2()).isEqualTo("Unit 7");assertThat(address.street3()).isEqualTo("Building B");assertThat(address.phone()).isEqualTo("5551234567");
    }
}
