package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AmazonMerchantFulfillmentClientTest {
    @Test void cheapestThenFastestArePromotedWithoutChangingThePrices(){
        var cheap=rate("cheap",new BigDecimal("7.25"),Instant.parse("2026-09-09T18:00:00Z"),true,false);
        var fast=rate("fast",new BigDecimal("12.40"),Instant.parse("2026-09-07T18:00:00Z"),false,true);
        var other=rate("other",new BigDecimal("9.10"),Instant.parse("2026-09-08T18:00:00Z"),false,false);
        var rates=new ArrayList<>(List.of(other,fast,cheap));rates.sort(AmazonMerchantFulfillmentClient.rateOrder());
        assertThat(rates).extracting(AmazonMerchantFulfillmentClient.Rate::serviceId).containsExactly("cheap","fast","other");
        assertThat(rates.getFirst().amount()).isEqualByComparingTo("7.25");
    }

    @Test void marketplacesResolveToTheirOwnSpApiRegion(){
        assertThat(AmazonMerchantFulfillmentClient.endpoint("ATVPDKIKX0DER")).contains("-na.");
        assertThat(AmazonMerchantFulfillmentClient.endpoint("A1F83G8C2ARO7P")).contains("-eu.");
        assertThat(AmazonMerchantFulfillmentClient.endpoint("A1VC38T7YXB528")).contains("-fe.");
    }

    @Test void eligibleRequestUsesAmazonOfferingFilterContract() throws Exception {
        AmazonSpApiClient api=mock(AmazonSpApiClient.class);ObjectMapper json=new ObjectMapper();
        var client=new AmazonMerchantFulfillmentClient(api,json,new AmazonStoreRateLimiter());
        UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
        String response="""
            {"payload":{"ShippingServiceList":[{"ShippingServiceId":"svc","ShippingServiceOfferId":"offer",
            "CarrierName":"UPS","ShippingServiceName":"Ground","RateWithAdjustments":{"CurrencyCode":"USD","Amount":8.25},
            "LatestEstimatedDeliveryDate":"2026-09-08T18:00:00Z","AvailableLabelFormats":["PDF"],
            "RequiresAdditionalSellerInputs":false}]}}
            """;
        when(api.postAt(eq(tenant),eq(connection),anyString(),eq("/mfn/v0/eligibleShippingServices"),anyString()))
            .thenReturn(new AmazonSpApiClient.ApiResponse(200,"request",json.readTree(response),response));

        var quote=client.eligible(tenant,connection,"ATVPDKIKX0DER",json.readTree("{\"AmazonOrderId\":\"112-1\"}"));

        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(api).postAt(eq(tenant),eq(connection),anyString(),eq("/mfn/v0/eligibleShippingServices"),body.capture());
        var sent=json.readTree(body.getValue());
        assertThat(sent.path("ShipmentRequestDetails").path("AmazonOrderId").asText()).isEqualTo("112-1");
        assertThat(sent.path("ShippingOfferingFilter").path("IncludeComplexShippingOptions").asBoolean()).isTrue();
        assertThat(sent.path("ShippingOfferingFilter").path("DeliveryExperience").asText()).isEqualTo("NoPreference");
        assertThat(quote.rates()).singleElement().satisfies(rate->{
            assertThat(rate.amount()).isEqualByComparingTo("8.25");
            assertThat(rate.labelFormats()).containsExactly("PDF");
        });
    }

    @Test void purchaseRequestsPdfAndDecodesAmazonGzipLabel() throws Exception {
        AmazonSpApiClient api=mock(AmazonSpApiClient.class);ObjectMapper json=new ObjectMapper();
        var client=new AmazonMerchantFulfillmentClient(api,json,new AmazonStoreRateLimiter());
        UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();byte[] pdf="%PDF-safe-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String contents=Base64.getEncoder().encodeToString(gzip(pdf));
        String response="""
            {"payload":{"ShipmentId":"abcddcba-00c3-4f6f-a63a-639f76ee9253","TrackingId":"1ZTEST",
            "ShippingService":{"ShippingServiceId":"svc","CarrierName":"UPS","ShippingServiceName":"Ground",
            "RateWithAdjustments":{"CurrencyCode":"USD","Amount":7.50}},
            "Label":{"FileContents":{"Contents":"%s","FileType":"application/pdf"}}}}
            """.formatted(contents);
        when(api.postAt(eq(tenant),eq(connection),anyString(),eq("/mfn/v0/shipments"),anyString()))
            .thenReturn(new AmazonSpApiClient.ApiResponse(200,"request",json.readTree(response),response));

        var purchase=client.purchase(tenant,connection,"ATVPDKIKX0DER",json.readTree("{\"AmazonOrderId\":\"112-1\"}"),"svc","offer");

        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(api).postAt(eq(tenant),eq(connection),anyString(),eq("/mfn/v0/shipments"),body.capture());
        var sent=json.readTree(body.getValue());
        assertThat(sent.path("ShippingServiceId").asText()).isEqualTo("svc");
        assertThat(sent.path("ShippingServiceOfferId").asText()).isEqualTo("offer");
        assertThat(sent.path("LabelFormatOption").path("LabelFormat").asText()).isEqualTo("PDF");
        assertThat(purchase.labelPdf()).isEqualTo(pdf);
        assertThat(purchase.price()).isEqualByComparingTo("7.50");
    }

    private static AmazonMerchantFulfillmentClient.Rate rate(String id,BigDecimal price,Instant delivery,boolean cheapest,boolean fastest){
        return new AmazonMerchantFulfillmentClient.Rate(id,null,"Carrier",id,price,"USD",null,null,delivery,false,List.of("PDF"),null,cheapest,fastest);
    }

    private static byte[] gzip(byte[] value) throws Exception {
        try(ByteArrayOutputStream out=new ByteArrayOutputStream();GZIPOutputStream gzip=new GZIPOutputStream(out)){
            gzip.write(value);gzip.finish();return out.toByteArray();
        }
    }
}
