package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ShippingLabelComposerTest {
    @Test void carrierAndFirstTwoPackingLinesShareOneFourBySixSheet() throws Exception {
        byte[] carrier;
        try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            document.addPage(new PDPage(new PDRectangle(288,432)));document.save(out);carrier=out.toByteArray();
        }
        var lines=List.of(line("SKU-1"),line("SKU-2"),line("SKU-3"));
        var composed=new ShippingLabelComposer().compose(carrier,"112-1234567-1234567",lines,true);
        assertThat(composed.pageCount()).isEqualTo(2);
        try(PDDocument result=Loader.loadPDF(composed.pdf())){
            assertThat(result.getNumberOfPages()).isEqualTo(2);
            assertThat(result.getPage(0).getMediaBox().getWidth()).isEqualTo(288);
            assertThat(result.getPage(0).getMediaBox().getHeight()).isEqualTo(432);
            assertThat(new PDFTextStripper().getText(result)).contains("SKU-1","SKU-2","SKU-3");
        }
    }

    @Test void twoSkusFitBelowTheCarrierLabelWithoutAnotherPage() throws Exception {
        byte[] carrier;
        try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            document.addPage(new PDPage(PDRectangle.LETTER));document.save(out);carrier=out.toByteArray();
        }
        var composed=new ShippingLabelComposer().compose(carrier,"order",List.of(line("SKU-1"),line("SKU-2")),true);
        assertThat(composed.pageCount()).isEqualTo(1);
        try(PDDocument result=Loader.loadPDF(composed.pdf())){
            assertThat(result.getPage(0).getMediaBox().getWidth()).isEqualTo(288);
            assertThat(result.getPage(0).getMediaBox().getHeight()).isEqualTo(432);
        }
    }

    @Test void disablingPackingDetailsReturnsOnlyCarrierPages() throws Exception {
        byte[] carrier;
        try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            document.addPage(new PDPage(new PDRectangle(288,432)));document.save(out);carrier=out.toByteArray();
        }
        var composed=new ShippingLabelComposer().compose(carrier,"order",List.of(line("SKU")),false);
        assertThat(composed.pageCount()).isEqualTo(1);
        try(PDDocument result=Loader.loadPDF(composed.pdf())){
            assertThat(result.getPage(0).getMediaBox().getWidth()).isEqualTo(288);
            assertThat(result.getPage(0).getMediaBox().getHeight()).isEqualTo(432);
        }
    }
    private static BuyShippingRepository.PackingLine line(String sku){return new BuyShippingRepository.PackingLine("Brand",sku,"Product",1,"MAIN",LocalDate.of(2027,1,15));}
}
