package com.nextaicommerce.platform.marketplace;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BarcodePackRulesTest {
 @Test void onlyExplicitPacksAreQuantities(){
  assertThat(BarcodePackRules.quantity("Cheese 7.8 Ounces (Pack of 2)")).isEqualTo(2);
  assertThat(BarcodePackRules.quantity("Coffee 3-pack")).isEqualTo(3);
  assertThat(BarcodePackRules.quantity("Karaca 24-Piece Set for 12 People")).isEqualTo(1);
  assertThat(BarcodePackRules.quantity("Coffee 3-pack (pack of 2)")).isNull();
  assertThat(BarcodePackRules.quantity("Variety pack of 3")).isNull();
  assertThat(BarcodePackRules.quantity("Case of 12")).isNull();
  assertThat(BarcodePackRules.quantity("Pack of 999")).isNull();
 }
 @Test void validatesChecksumsAndEquivalentEncodings(){
  assertThat(BarcodePackRules.gtin("012345678905")).isEqualTo("00012345678905");
  assertThat(BarcodePackRules.gtin("0012345678905")).isEqualTo("00012345678905");
  assertThat(BarcodePackRules.gtin("012345678906")).isNull();
  assertThat(BarcodePackRules.gtin("B000012345")).isNull();
 }
}
