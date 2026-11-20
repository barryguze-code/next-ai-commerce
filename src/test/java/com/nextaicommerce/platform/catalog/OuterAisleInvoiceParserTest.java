package com.nextaicommerce.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OuterAisleInvoiceParserTest {
    @Test void extractsCaseQuantitiesPackAndCasePrice(){
        String text="""
            Outer Aisle Gourmet, LLC
            2879 Seaborg Ave Sales Order SO54160
            Ventura CA 93003
            IBCore LLC IBCore LLC
            Ventura CA Ventura CA 7/15/2026 $4,614.40
            ITEM QUANTITY UNIT PRICE AMOUNT
            50021-US - OSR - Retail - Original Sandwich Rounds - 12 packs of 6 60 $57.68 $3,460.80
            slices / case
            50321-US - ESR - Retail - Everything Sandwich Rounds - 12 packs of 6 20 $57.68 $1,153.60
            """;
        var sheet=CatalogImportService.parseOuterAislePdf(text);
        assertThat(sheet).isNotNull();
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst()).containsEntry("VendorItemCode","50021")
            .containsEntry("Quantity","60").containsEntry("CasePack","12")
            .containsEntry("UnitPrice","57.68").containsEntry("PriceBasis","CASE")
            .containsEntry("InvoiceNumber","SO54160").containsEntry("InvoiceDate","7/15/2026");
        assertThat(new BigDecimal(sheet.rows().getFirst().get("UnitPrice"))
            .divide(new BigDecimal(sheet.rows().getFirst().get("CasePack")),4,java.math.RoundingMode.HALF_UP))
            .isEqualByComparingTo("4.8067");
    }
}
