package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FedExPayloadsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void embeddedPdfResponseReturnsBinaryLabel(){
        byte[] pdf="%PDF-1.4\nsynthetic test label".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var response=json.createObjectNode();
        var piece=response.putObject("output").putArray("transactionShipments").addObject().putArray("pieceResponses").addObject();
        piece.put("trackingNumber","123456789012").putArray("packageDocuments").addObject().put("contentType","LABEL").put("docType","PDF").put("encodedLabel",java.util.Base64.getEncoder().encodeToString(pdf));
        assertThat(FedExPayloads.label(response).pdf()).isEqualTo(pdf);
    }
    final FedExPayloads.Address address=new FedExPayloads.Address("Test Recipient","9015550100","10 Test Street","Memphis","TN","38116","US",true);
    @Test void payloadUsesMerchantAccountAndConservativeDimensionRounding(){
        var request=FedExPayloads.rateRequest(json,"123456789",address,address,new FedExPayloads.Parcel(new BigDecimal("9.4"),BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE));
        assertThat(request.path("accountNumber").path("value").asText()).isEqualTo("123456789");
        assertThat(request.path("requestedShipment").path("requestedPackageLineItems").get(0).path("dimensions").path("length").asInt()).isEqualTo(10);
        var ship=FedExPayloads.shipRequest(request,"FEDEX_GROUND");
        assertThat(ship.path("requestedShipment").has("recipient")).isFalse();
        assertThat(ship.path("requestedShipment").path("recipients").size()).isEqualTo(1);
        assertThat(ship.path("requestedShipment").path("labelSpecification").path("labelStockType").asText()).isEqualTo("PAPER_4X6");
        assertThat(request.path("requestedShipment").has("recipient")).isTrue();
    }
    @Test void listRatesNeverMasqueradeAsAccountPrices(){var rates=FedExPayloads.rates(json.readTree("""
      {"output":{"rateReplyDetails":[{"serviceType":"FEDEX_GROUND","serviceName":"FedEx Ground","ratedShipmentDetails":[{"rateType":"LIST","totalNetCharge":40,"currency":"USD"},{"rateType":"ACCOUNT","totalNetCharge":21.5,"currency":"USD"}]}]}}
      """));assertThat(rates).hasSize(1);assertThat(rates.getFirst().amount()).isEqualByComparingTo("21.5");}
    @Test void invalidWeightAndInternationalAddressFailBeforeNetwork(){assertThatThrownBy(()->FedExPayloads.validate(new FedExPayloads.Parcel(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO))).isInstanceOf(IllegalArgumentException.class);var foreign=new FedExPayloads.Address("Test","9015550100","Test","Test","TN","38116","CA",false);assertThatThrownBy(()->FedExPayloads.rateRequest(json,"123456789",address,foreign,new FedExPayloads.Parcel(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE))).isInstanceOf(IllegalArgumentException.class);}
    @Test void labelUrlIsNeverFetchedOrTrusted(){assertThatThrownBy(()->FedExPayloads.label(json.readTree("""
      {"output":{"transactionShipments":[{"pieceResponses":[{"trackingNumber":"123456789012","packageDocuments":[{"contentType":"LABEL","docType":"PDF","url":"http://169.254.169.254/"}]}]}]}}
      """))).isInstanceOf(FedExClient.CarrierException.class);}
}
