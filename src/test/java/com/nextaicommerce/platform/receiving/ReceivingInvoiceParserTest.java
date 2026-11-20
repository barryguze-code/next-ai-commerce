package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import com.nextaicommerce.platform.catalog.CatalogImportService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

class ReceivingInvoiceParserTest {
    @Test void repeatedKeheStatusRowsAreOneUnshippedInvoiceLine(){
        var rows=ReceivingRepository.unshippedRows(java.util.List.of(
            java.util.Map.of("Line","1","ShipItem","123","Description","Partial","OrderQuantity","30","ShipQuantity","0","QuantityNotShipped","24","Status","OutOfStock"),
            java.util.Map.of("Line","1","ShipItem","123","Description","Partial","OrderQuantity","30","ShipQuantity","6","QuantityNotShipped","24","Status","Shipped"),
            java.util.Map.of("Line","2","ShipItem","123","Description","Separate line","OrderQuantity","30","ShipQuantity","0","QuantityNotShipped","24","Status","OutOfStock")));
        assertThat(rows).hasSize(2);assertThat(rows.getFirst().shipped()).isEqualByComparingTo("6");
        assertThat(rows.getFirst().unshipped()).isEqualByComparingTo("24");assertThat(rows.getFirst().reason()).isEqualTo("OutOfStock");
    }
    @Test void retainsVendorUnshippedRowsAsInformationOnly(){
        var rows=ReceivingRepository.unshippedRows(java.util.List.of(
            java.util.Map.of("ShipItem","000123","Description","Missing item","OrderQuantity","12","ShipQuantity","0","QuantityNotShipped","12","InvalidReason","Out of stock"),
            java.util.Map.of("ShipItem","456","Description","Partial item","OrderQuantity","10","ShipQuantity","6"),
            java.util.Map.of("Description","Delivered","OrderQuantity","5","ShipQuantity","5"),
            java.util.Map.of("Description","No shipping breakdown","quantity","9")));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().code()).isEqualTo("123");
        assertThat(rows.getFirst().shipped()).isZero();assertThat(rows.getFirst().unshipped()).isEqualByComparingTo("12");
        assertThat(rows.get(1).unshipped()).isEqualByComparingTo("4");
    }
    @Test
    void readsKeheInvoiceColumnsWithoutChangingCodesOrPrices() throws Exception {
        String csv="Line,Status,OrderQuantity,ShipQuantity,QuantityNotShipped,InvalidReason,ShipItem,PackSize,Brand,Description,Upc,Retail,SuggestedRetail,WholeSale,AdjustedWholeSale,DiscountPercentage,Discount,NetEach,NetBillable,Upcharges,BottleTax,InvoiceNumber,InvoiceDate,StoreNumber\n"
            + "1,\"Shipped    \",60,60,0,\"                              \",\"=\"\"00354920\"\"\",\"12/6.000 OZ  \",\"Egglife                       \",\"Egg White Wrap Original       \",\"=\"\"810023540007\"\"\",7.79,0,5.20,5.20,0.00,0.93,4.27,256.20,0,0,4784240,08/11/2026,000000\n";
        var parsed=new CatalogImportService(null,null,null).readFile("kehe-invoice.csv",csv.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst()).containsEntry("ShipQuantity","60")
            .containsEntry("PackSize","12/6.000 OZ").containsEntry("NetEach","4.27")
            .containsEntry("Description","Egg White Wrap Original")
            .containsEntry("InvoiceNumber","4784240").containsEntry("InvoiceDate","08/11/2026");
        assertThat(ReceivingRepository.cleanCode(parsed.rows().getFirst().get("ShipItem"))).isEqualTo("354920");
        assertThat(ReceivingRepository.cleanCode(parsed.rows().getFirst().get("Upc"))).isEqualTo("810023540007");
        assertThat(ReceivingRepository.packUnits(parsed.rows().getFirst().get("PackSize"))).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(ReceivingRepository.parseDate(parsed.rows().getFirst().get("InvoiceDate"))).isEqualTo(LocalDate.of(2026,8,11));
    }

    @Test
    void preFillsWholeCasesAndLeavesOnlyLooseEaches() {
        var line=new ReceivingRepository.ReceiveLineView(UUID.randomUUID(),UUID.randomUUID(),"Product","Brand",null,"354920",
            "810023540007",new BigDecimal("14"),new BigDecimal("12"),new BigDecimal("14"),BigDecimal.ZERO,
            BigDecimal.ZERO,new BigDecimal("14"),new BigDecimal("4.27"),"USD",false,"OPEN","NONE","NEW_PRODUCT",0);
        assertThat(line.suggestedCases()).isEqualByComparingTo("1");
        assertThat(line.suggestedEaches()).isEqualByComparingTo("2");
        assertThat(line.expectedUnits()).isEqualTo("14");
        assertThat(line.unitsPerCaseInput()).isEqualTo("12");
        assertThat(line.nextExpirationBatch()).isEqualTo(1);
    }

    @Test
    void undetectedCasePackDefaultsToOneAndStatesRemainOperationallyDistinct() {
        assertThat(ReceivingRepository.packUnits(null)).isEqualByComparingTo("1");
        assertThat(ReceivingRepository.packUnits("")).isEqualByComparingTo("1");
        var pending=new ReceivingRepository.ReceiveLineView(UUID.randomUUID(),UUID.randomUUID(),"Product","Brand",null,
            "354920","810023540007",BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.TEN,BigDecimal.ONE,"USD",false,"OPEN","NONE","NEW_PRODUCT",0);
        var over=new ReceivingRepository.ReceiveLineView(UUID.randomUUID(),UUID.randomUUID(),"Product","Brand",null,
            "354920","810023540007",BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN,new BigDecimal("12"),
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,"USD",false,"RECEIVED","OVER_SHIPPED","MATCHED",1);
        assertThat(pending.receivingState()).isEqualTo("PENDING");
        assertThat(over.receivingState()).isEqualTo("OVER");
    }

    @Test
    void readsARealVendorPdfWhenDiagnosticPathIsProvided() throws Exception {
        String diagnosticPath=System.getProperty("kehe.invoice.pdf");
        Assumptions.assumeTrue(diagnosticPath!=null&&Files.isRegularFile(Path.of(diagnosticPath)));
        var parsed=new CatalogImportService(null,null,null).readFile(diagnosticPath,Files.readAllBytes(Path.of(diagnosticPath)));
        assertThat(parsed.rows()).isNotEmpty();
        if(diagnosticPath.endsWith("SO54160.pdf")){
            assertThat(parsed.rows()).hasSize(2);
            assertThat(parsed.rows().getFirst()).containsEntry("VendorItemCode","50021")
                .containsEntry("Quantity","60").containsEntry("CasePack","12")
                .containsEntry("UnitPrice","57.68").containsEntry("InvoiceNumber","SO54160");
            return;
        }
        assertThat(parsed.rows().getFirst()).containsKeys("ShipItem","Upc","ShipQuantity","NetEach","InvoiceNumber","InvoiceDate");
        assertThat(parsed.rows()).allSatisfy(row->{
            assertThat(row.get("InvoiceNumber")).isNotBlank();
            assertThat(row.get("InvoiceDate")).isNotBlank();
            assertThat(new BigDecimal(row.get("ShipQuantity"))).isPositive();
            assertThat(new BigDecimal(row.get("NetEach"))).isPositive();
        });
        if(diagnosticPath.endsWith("KEHEInvoice1.pdf")){
            var known=parsed.rows().stream().filter(row->row.get("ShipItem").equals("0386234")).findFirst().orElseThrow();
            assertThat(known.get("ShipQuantity")).isEqualTo("1440");
            assertThat(new BigDecimal(known.get("NetEach"))).isEqualByComparingTo("0.8");
            assertThat(known.get("InvoiceNumber")).isEqualTo("101247110");
        }
        if(diagnosticPath.endsWith("KEHEInvoice2.pdf")){
            var known=parsed.rows().stream().filter(row->row.get("ShipItem").equals("0403420")).findFirst().orElseThrow();
            assertThat(known.get("ShipQuantity")).isEqualTo("72");
            assertThat(new BigDecimal(known.get("NetEach"))).isEqualByComparingTo("10.92");
        }
    }
}
