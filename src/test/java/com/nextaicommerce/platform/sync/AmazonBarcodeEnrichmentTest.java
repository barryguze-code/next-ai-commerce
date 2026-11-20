package com.nextaicommerce.platform.sync;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AmazonBarcodeEnrichmentTest {
 @Test void keepsOnlyValidBarcodesForRequestedMarketplace(){
  var response=new tools.jackson.databind.ObjectMapper().readTree("""
   {"identifiers":[{"marketplaceId":"US","identifiers":[
    {"identifierType":"UPC","identifier":"012345678905"},
    {"identifierType":"EAN","identifier":"0012345678905"},
    {"identifierType":"ASIN","identifier":"012345678905"},
    {"identifierType":"UPC","identifier":"012345678906"}]},
    {"marketplaceId":"CA","identifiers":[{"identifierType":"UPC","identifier":"036000291452"}]}]}
   """);
  assertThat(AmazonBarcodeEnrichment.identifiers(response,"US")).containsExactly("00012345678905");
 }
}
