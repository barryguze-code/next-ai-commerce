package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class ShippingBatchPdfComposerTest {
    @Test void mergesPurchasedPrintFilesInTheProvidedOperationalOrder() throws Exception {
        byte[] merged=new ShippingBatchPdfComposer().merge(List.of(pdf(1),pdf(2)));
        try(PDDocument document=Loader.loadPDF(merged)){assertThat(document.getNumberOfPages()).isEqualTo(3);}
    }

    @Test void refusesToPretendAnEmptyBatchIsPrintable(){
        assertThatThrownBy(()->new ShippingBatchPdfComposer().merge(List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no purchased labels");
    }

    private static byte[] pdf(int pages) throws Exception {
        try(PDDocument document=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            for(int i=0;i<pages;i++)document.addPage(new PDPage());document.save(bytes);return bytes.toByteArray();
        }
    }
}
