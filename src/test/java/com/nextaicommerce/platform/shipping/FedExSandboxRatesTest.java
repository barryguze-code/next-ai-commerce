package com.nextaicommerce.platform.shipping;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
class FedExSandboxRatesTest {
 final ObjectMapper json=new ObjectMapper();
 MockEnvironment safe(){var e=new MockEnvironment();e.setActiveProfiles("fee-release-uat");return e.withProperty("app.fedex.sandbox-rate-only.enabled","true").withProperty("app.fedex.simulation-enabled","true").withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:55432/fixture").withProperty("app.fedex.sandbox-rate-only.account-number","740561073").withProperty("FEDEX_CLIENT_ID","unit-key").withProperty("FEDEX_CLIENT_SECRET","unit-secret");}
 FedExPayloads.Address address(String zip){return new FedExPayloads.Address("Test","9015550100","1 Test St","Test","TN",zip,"US",false);}
 FedExPayloads.Parcel parcel(){return new FedExPayloads.Parcel(BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE);}
 @Test void absentFlagIsOffWithoutCredentialsOrTransport(){var c=new FedExSandboxRates(json,new MockEnvironment(),r->{throw new AssertionError();});assertThat(c.account()).isNull();assertThatThrownBy(()->c.quote(UUID.randomUUID(),UUID.randomUUID(),address("65247"),address("72348"),parcel())).isInstanceOf(IllegalStateException.class);}
 @Test void rejectsRemoteProductionEveryWriteGuardAndMissingAccount(){
  for(String key:List.of("app.fedex.external-enabled","app.fedex.confirmation-enabled","app.amazon.write-enabled","app.amazon.inventory-publication-enabled","app.amazon.buy-shipping-enabled","app.shopify.inventory.write-enabled","app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled"))assertThatThrownBy(()->new FedExSandboxRates(json,safe().withProperty(key,"true"),r->{throw new AssertionError();})).isInstanceOf(IllegalStateException.class);
  for(String[] pair:List.of(new String[]{"spring.datasource.url","jdbc:postgresql://remote/live"},new String[]{"app.fedex.platform-environment","PRODUCTION"},new String[]{"app.fedex.simulation-enabled","false"},new String[]{"app.fedex.sandbox-rate-only.account-number",""}))assertThatThrownBy(()->new FedExSandboxRates(json,safe().withProperty(pair[0],pair[1]),r->{throw new AssertionError();})).isInstanceOf(IllegalStateException.class);
  var prod=safe();prod.setActiveProfiles("prod");assertThatThrownBy(()->new FedExSandboxRates(json,prod,r->{throw new AssertionError();})).isInstanceOf(IllegalStateException.class);
 }
 @Test void explicitRequestUsesExactTestAccountParentAuthFixedRouteAndNeverShip(){
  var calls=new ArrayList<HttpRequest>();var c=client(calls,200);var t=UUID.randomUUID();var s=UUID.randomUUID();
  var result=c.quote(t,s,address("65247"),address("72348"),parcel());assertThat(result.rateOnly()).isTrue();assertThat(result.rates()).hasSize(1);assertThat(result.rates().getFirst().amount()).isEqualByComparingTo("23.81");
  assertThat(calls).hasSize(2).allSatisfy(r->assertThat(r.uri().getHost()).isEqualTo("apis-sandbox.fedex.com"));assertThat(body(calls.getFirst())).contains("grant_type=client_credentials").doesNotContain("child");
  assertThat(calls.getLast().uri().getPath()).isEqualTo("/rate/v1/comprehensiverates/quotes");var payload=json.readTree(body(calls.getLast()));assertThat(payload.at("/accountNumber/value").asText()).isEqualTo("740561073");assertThat(payload.at("/requestedShipment/shipper/address/postalCode").asText()).isEqualTo("65247");assertThat(payload.at("/requestedShipment/recipient/address/postalCode").asText()).isEqualTo("72348");assertThat(payload.at("/requestedShipment/recipient/contact").isMissingNode()).isTrue();assertThat(payload.at("/requestedShipment/recipient/address/streetLines").isMissingNode()).isTrue();assertThat(payload.path("rateDisplayOption").asText()).isEqualTo("SELECTED_RATES_EXCLUDING_F1R");
  assertThatThrownBy(()->c.quote(t,s,address("65247"),address("72348"),parcel())).isInstanceOf(IllegalStateException.class).hasMessageContaining("five seconds");assertThat(calls).hasSize(2);
  c.quote(t,UUID.randomUUID(),address("65247"),address("72348"),parcel());c.quote(UUID.randomUUID(),s,address("65247"),address("72348"),parcel());assertThat(calls.stream().filter(r->r.uri().getPath().equals("/oauth/token")).count()).isEqualTo(3);
 }
 @Test void carrierFailureNeverRetriesOrCreatesFallback(){var calls=new ArrayList<HttpRequest>();var c=client(calls,503);assertThatThrownBy(()->c.quote(UUID.randomUUID(),UUID.randomUUID(),address("65247"),address("72348"),parcel())).isInstanceOf(FedExClient.CarrierException.class).hasMessageContaining("503");assertThat(calls).hasSize(2);}
 @Test void temporaryRateFailureDoesNotBlameCredentialsOrRetry(){
  for(int status:List.of(500,503)){var calls=new ArrayList<HttpRequest>();var c=client(calls,status);
   assertThatThrownBy(()->c.quote(UUID.randomUUID(),UUID.randomUUID(),address("65247"),address("72348"),parcel()))
    .isInstanceOfSatisfying(FedExClient.CarrierException.class,e->{assertThat(e.uncertain()).isFalse();assertThat(e.getMessage()).contains("temporary","HTTP "+status).doesNotContain("credentials","permissions");});
   assertThat(calls).hasSize(2);
  }
 }
 @Test void paddedPakUsesTheSameRateContractAtThreeAndFourPounds(){
  for(int weight:List.of(3,4)){var calls=new ArrayList<HttpRequest>();var c=client(calls,200);
   c.quote(UUID.randomUUID(),UUID.randomUUID(),address("65247"),address("72348"),new FedExPayloads.Parcel(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.valueOf(weight),"PAK_PADDED"));
   var payload=json.readTree(body(calls.getLast()));var box=payload.at("/requestedShipment/requestedPackageLineItems/0");
   assertThat(payload.at("/requestedShipment/packagingType").asText()).isEqualTo("FEDEX_PAK");assertThat(box.at("/weight/value").decimalValue()).isEqualByComparingTo(Integer.toString(weight));
   assertThat(box.path("dimensions")).isEqualTo(json.readTree("{\"units\":\"IN\",\"length\":15,\"width\":12,\"height\":1}"));assertThat(payload.path("rateDisplayOption").asText()).isEqualTo("SELECTED_RATES_EXCLUDING_F1R");
  }
 }
 @Test void invalidParcelStopsBeforeAnyNetwork(){var calls=new ArrayList<HttpRequest>();var c=client(calls,200);assertThatThrownBy(()->c.quote(UUID.randomUUID(),UUID.randomUUID(),address("65247"),address("72348"),new FedExPayloads.Parcel(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO))).isInstanceOf(IllegalArgumentException.class);assertThat(calls).isEmpty();}
 FedExSandboxRates client(List<HttpRequest> calls,int status){return new FedExSandboxRates(json,safe(),r->{calls.add(r);if(r.uri().getPath().equals("/oauth/token"))return new FedExClient.Reply(200,"{\"access_token\":\"unit-token\",\"expires_in\":3600}");return new FedExClient.Reply(status,"{\"output\":{\"rateReplyDetails\":[{\"serviceType\":\"FEDEX_GROUND\",\"ratedShipmentDetails\":[{\"rateType\":\"PAYOR_ACCOUNT_PACKAGE\",\"currency\":\"USD\",\"totalNetCharge\":23.81}]}]}}");});}
 static String body(HttpRequest request){var out=new ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] v=new byte[b.remaining()];b.get(v);out.writeBytes(v);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}});return out.toString(java.nio.charset.StandardCharsets.UTF_8);}
 @Test void genuineSandboxShipRequiresSeparateUatFlagAndExactAccountAndHasNoProductionRoute(){
  var calls=new ArrayList<HttpRequest>();var disabled=client(calls,200);var payload=json.createObjectNode();payload.putObject("accountNumber").put("value","740561073");assertThatThrownBy(()->disabled.ship(UUID.randomUUID(),UUID.randomUUID(),payload)).isInstanceOf(IllegalStateException.class);assertThat(calls).isEmpty();
  String pdf=java.util.Base64.getEncoder().encodeToString("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));var enabled=new FedExSandboxRates(json,safe().withProperty("app.fedex.local-shipping-uat.enabled","true"),r->{calls.add(r);return new FedExClient.Reply(200,r.uri().getPath().equals("/oauth/token")?"{\"access_token\":\"fake\",\"expires_in\":3600}":"{\"output\":{\"transactionShipments\":[{\"pieceResponses\":[{\"trackingNumber\":\"000000000000\",\"packageDocuments\":[{\"contentType\":\"LABEL\",\"docType\":\"PDF\",\"encodedLabel\":\""+pdf+"\"}]}]}]}}");});
  var wrong=payload.deepCopy();wrong.putObject("accountNumber").put("value","999999999");assertThatThrownBy(()->enabled.ship(UUID.randomUUID(),UUID.randomUUID(),wrong)).isInstanceOf(IllegalArgumentException.class);assertThat(calls).isEmpty();assertThat(enabled.ship(UUID.randomUUID(),UUID.randomUUID(),payload).pdf()).isNotEmpty();assertThat(calls).hasSize(2).allSatisfy(r->assertThat(r.uri().getHost()).isEqualTo("apis-sandbox.fedex.com"));assertThat(calls.getLast().uri().getPath()).isEqualTo("/ship/v1/shipments");
 }
}
