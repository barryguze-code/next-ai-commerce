package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class CarrierLabelComposerTest {
    byte[] carrier()throws Exception{try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new PDPage(new PDRectangle(288,432)));doc.save(out);return out.toByteArray();}}
    List<CarrierLabelComposer.Line> lines(){return List.of(new CarrierLabelComposer.Line("Noosa","SKU-HONEY","Honey yoghurt catalogue description",2),new CarrierLabelComposer.Line("Brand","SKU-2","Second item",12),new CarrierLabelComposer.Line("Brand","SKU-3","Third item",123),new CarrierLabelComposer.Line("Brand","SKU-4","Fourth item",1));}
    @Test void productionKeepsOriginalPageAndPaginatesThreePackingLines()throws Exception{var bytes=new CarrierLabelComposer().compose(carrier(),"ORDER-1",lines(),CarrierLabelComposer.Layout.SEPARATE_SLIP,FedExClient.Environment.PRODUCTION);try(var result=Loader.loadPDF(bytes)){assertThat(result.getNumberOfPages()).isEqualTo(4);assertThat(result.getPage(0).getMediaBox().getHeight()).isEqualTo(432);var text=new PDFTextStripper().getText(result);assertThat(text).contains("SKU-4","Qty: 123");}}
    @Test void combinedIsSandboxOnlyAndKeepsAllRowsWithoutAllocationPage()throws Exception{
        var original=carrier();assertThatThrownBy(()->new CarrierLabelComposer().compose(original,"ORDER",lines(),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.PRODUCTION)).isInstanceOf(IllegalArgumentException.class);
        try(var result=Loader.loadPDF(new CarrierLabelComposer().compose(original,"ORDER",lines(),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            assertThat(result.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(result)).contains("SKU-HONEY","SKU-2","SKU-3","SKU-4").doesNotContain("PACKING","EXPIRY","LOCATION","Order ORDER");
        }
    }
    @Test void originalIsByteForByteUnchanged()throws Exception{byte[] original=carrier();assertThat(new CarrierLabelComposer().compose(original,"ORDER",lines(),CarrierLabelComposer.Layout.ORIGINAL,FedExClient.Environment.PRODUCTION)).isEqualTo(original);}
    @Test void fullSkuMappedQuantityAndPackageRemainWhenDescriptionIsCropped()throws Exception{
        String sku="IB-MKH-2302326-VAN-DRUMROLL-EA";
        var row=CarrierLabelComposer.Line.mapped("Drumroll",sku,"A very long product description that must be cropped before any mandatory identity",2,List.of(new java.math.BigDecimal("3")),"Pak",List.of(new CarrierLabelComposer.Allocation("PRIVATE-ITEM",java.math.BigDecimal.ONE,java.time.LocalDate.of(2027,1,15),"PRIVATE-LOCATION")));
        try(var result=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",List.of(row),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            assertThat(result.getNumberOfPages()).isEqualTo(1);var text=new PDFTextStripper().getText(result);assertThat(text).contains("Drumroll-","6","| "+sku," - Pak").doesNotContain("PRIVATE-ITEM","PRIVATE-LOCATION","2027","EXPIRY","PACKING","Account SKU");
            var content=new String(result.getPage(0).getContents().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);assertThat(content).contains(" re\nS");
        }
    }
    @Test void optedInLiveCombinedLayoutUsesSameCompactRowsWithoutHeading()throws Exception{
        try(var result=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",lines(),CarrierLabelComposer.Layout.COMBINED_80_20,FedExClient.Environment.PRODUCTION))){
            assertThat(result.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(result)).contains("SKU-HONEY","SKU-4").doesNotContain("Order ORDER","SANDBOX","PACKING ALLOCATIONS");
        }
    }
    @Test void longSkuWrapsWithoutChangingIdentityAndCapacityStopsBeforeDroppingItems()throws Exception{
        String sku="000000-LOCAL-VERY-LONG-SELLER-SKU-PEPPERONI-MULTIPACK-ORIGINAL-EXACT-IDENTITY-EAx12";
        var row=CarrierLabelComposer.Line.mapped("Cappellos",sku,"Only the description may be cropped",2,List.of(new java.math.BigDecimal("12")),"Custom packaging",List.of());
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",List.of(row),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){assertThat(doc.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(doc).replace("\n","")).contains(sku,"24","Custom packaging");}
        assertThatThrownBy(()->new CarrierLabelComposer().validateCompact(java.util.Collections.nCopies(10,row))).hasMessageContaining("No items were omitted");
    }
    @Test void mappedTotalUsesEveryFrozenComponentAndDoesNotInferFromSkuOrAllocations(){
        var row=CarrierLabelComposer.Line.mapped("Brand","000001-EAx99","Product",3,List.of(new java.math.BigDecimal("2"),new java.math.BigDecimal("0.5")),"Pak",List.of(new CarrierLabelComposer.Allocation("ITEM",new java.math.BigDecimal("900"),null,"MAIN")));
        assertThat(row.mappedQuantity()).isEqualByComparingTo("7.5");assertThat(row.quantity()).isEqualTo(3);assertThat(row.sku()).isEqualTo("000001-EAx99");
        assertThat(CarrierLabelComposer.Line.mapped("Brand","EAx12","Product",3,List.of(),"Pak",List.of()).mappedQuantity()).isNull();
        assertThatThrownBy(()->CarrierLabelComposer.Line.mapped("Brand","SKU","Product",2,List.of(java.math.BigDecimal.ZERO),"Pak",List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldPackingSnapshotsRemainReadableWithExplicitUnknownMappedQuantity()throws Exception{
        var json=new tools.jackson.databind.ObjectMapper();var row=json.readValue("{\"brand\":\"Old\",\"sku\":\"000001-EAx12\",\"description\":\"Original\",\"quantity\":2,\"allocations\":[]}",CarrierLabelComposer.Line.class);
        assertThat(row.mappedQuantity()).isNull();assertThat(row.packageName()).isNull();
        try(var pdf=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",List.of(row),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){assertThat(new PDFTextStripper().getText(pdf)).contains("?","Package not recorded","000001-EAx12");}
    }
    @Test void accountItemStripPreservesExactCodesAndQuantitiesWithLargerBoxedQuantity()throws Exception{
        List<CarrierLabelComposer.Line> rows=List.of(CarrierLabelComposer.Line.accountItem("Brand","000001","Long description ".repeat(30),new java.math.BigDecimal("12"),"MAIN"),CarrierLabelComposer.Line.accountItem("Brand","000001","Same item, another actual location",new java.math.BigDecimal("3"),"F7"));
        var json=new tools.jackson.databind.ObjectMapper();rows=json.readValue(json.writeValueAsString(rows),new tools.jackson.core.type.TypeReference<List<CarrierLabelComposer.Line>>(){});
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",rows,CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            assertThat(doc.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(doc)).contains("12","3","Brand","000001","MAIN","F7").doesNotContain("Package not recorded","PACKING ALLOCATIONS");
            String stream=new String(doc.getPage(0).getContents().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);assertThat(stream).contains("10.4 Tf");assertThat(stream.split(" re\\nS").length).isGreaterThanOrEqualTo(5);
        }
        var row=rows.getFirst();assertThatThrownBy(()->new CarrierLabelComposer().validateCompact(java.util.Collections.nCopies(5,row))).hasMessageContaining("No items were omitted");
    }
    @Test void accountItemStripRejectsMissingLocationOrIdentityInsteadOfGuessing(){
        assertThatThrownBy(()->CarrierLabelComposer.Line.accountItem("Brand",null,"Name",java.math.BigDecimal.ONE,"MAIN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CarrierLabelComposer.Line.accountItem("Brand","0001","Name",java.math.BigDecimal.ONE,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void accountBrandIsBoldWithoutBoxAndOnlyAccountIdentityIsPrinted()throws Exception{
        var row=CarrierLabelComposer.Line.accountItem("BRAND","000001","Product description",new java.math.BigDecimal("8"),"MAIN");
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",List.of(row),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            var positions=new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();var stripper=new PDFTextStripper(){@Override protected void writeString(String text,List<org.apache.pdfbox.text.TextPosition> chars)throws java.io.IOException{positions.addAll(chars);super.writeString(text,chars);}};
            assertThat(stripper.getText(doc).replaceAll("\\s+"," ")).contains("8 - BRAND - 000001 - Product description - MAIN").doesNotContain("IB-MKH","Pak","Package","PACKING");assertThat(positions.stream().filter(c->"B".equals(c.getUnicode())).toList()).hasSize(1).allSatisfy(c->assertThat(c.getFont().getName()).contains("Bold"));
        }
    }
    @Test void frozenPackageHeaderIsBoldOnceWithThreeCompleteAccountRows()throws Exception{
        var row=CarrierLabelComposer.Line.accountItem("BRAND","000001","Product description",new java.math.BigDecimal("8"),"MAIN").withPackage("FedEx Pak Padded");
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(carrier(),"ORDER",java.util.Collections.nCopies(3,row),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            assertThat(doc.getNumberOfPages()).isEqualTo(1);String text=new PDFTextStripper().getText(doc);assertThat(text.split("FedEx Pak Padded",-1)).hasSize(2);assertThat(text.split("000001",-1)).hasSize(4);assertThat(text).doesNotContain("PACKING","SELLER-SKU");
        }
        assertThatThrownBy(()->new CarrierLabelComposer().validateCompact(java.util.Collections.nCopies(4,row))).hasMessageContaining("No items were omitted");
        assertThatThrownBy(()->new CarrierLabelComposer().validateCompact(List.of(row.withPackage("Long package name ".repeat(30))))).hasMessageContaining("No items were omitted");
    }
    @Test void letterWrappedCarrierLabelFillsTheUpperBandWithoutCroppingPaint()throws Exception{
        byte[] original;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);try(var c=new PDPageContentStream(doc,page)){c.addRect(36,360,260,400);c.stroke();c.beginText();c.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),10);c.newLineAtOffset(45,745);c.showText("CARRIER IDENTITY");c.endText();for(int x=45;x<280;x+=4){c.addRect(x,375,1,40);c.fill();}}doc.save(out);original=out.toByteArray();}
        var row=CarrierLabelComposer.Line.accountItem("BRAND","000001","Product",java.math.BigDecimal.ONE,"MAIN").withPackage("FedEx Pak Padded");
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(original,"ORDER",List.of(row),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            var image=new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImage(0,1,org.apache.pdfbox.rendering.ImageType.RGB);int top=image.getHeight(),bottom=-1,left=image.getWidth(),right=-1;for(int y=0;y<345;y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff){top=Math.min(top,y);bottom=Math.max(bottom,y);left=Math.min(left,x);right=Math.max(right,x);}
            assertThat(bottom-top).as("visible carrier height inside the 80 percent band").isGreaterThan(310);assertThat(right-left).isGreaterThan(200);assertThat(new PDFTextStripper().getText(doc)).contains("CARRIER IDENTITY","FedEx Pak Padded","000001");assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(288);assertThat(doc.getPage(0).getMediaBox().getHeight()).isEqualTo(432);
        }
    }
    @Test void faintLetterFooterIsRetainedRatherThanMistakenForBlankMargin()throws Exception{
        byte[] original;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){var page=new PDPage(PDRectangle.LETTER);doc.addPage(page);try(var c=new PDPageContentStream(doc,page)){c.addRect(36,360,260,400);c.stroke();c.setNonStrokingColor(254f/255);c.addRect(550,50,6,6);c.fill();}doc.save(out);original=out.toByteArray();}
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(original,"ORDER",List.of(),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            var image=new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImage(0,1,org.apache.pdfbox.rendering.ImageType.RGB);boolean footer=false;for(int y=300;y<345;y++)for(int x=250;x<288;x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff)footer=true;assertThat(footer).as("faint distant carrier paint must not be discarded").isTrue();
        }
    }
    @Test void nonstandardCropCoordinatesRetainTheFullPageFit()throws Exception{
        byte[] original;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){var page=new PDPage(new PDRectangle(612,842));page.setCropBox(new PDRectangle(0,50,612,792));doc.addPage(page);try(var c=new PDPageContentStream(doc,page)){c.addRect(36,410,260,400);c.stroke();}doc.save(out);original=out.toByteArray();}
        try(var doc=Loader.loadPDF(new CarrierLabelComposer().compose(original,"ORDER",List.of(),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX))){
            var image=new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImage(0,1,org.apache.pdfbox.rendering.ImageType.RGB);int top=345,bottom=-1;for(int y=0;y<345;y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff){top=Math.min(top,y);bottom=Math.max(bottom,y);}assertThat(bottom-top).as("ambiguous crop coordinates must keep conservative full-page placement").isBetween(150,200);
        }
    }

}
