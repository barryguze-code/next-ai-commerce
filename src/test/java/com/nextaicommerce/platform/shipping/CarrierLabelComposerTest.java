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
    @Test void productionKeepsOriginalPageAndPaginatesThreePackingLines()throws Exception{var bytes=new CarrierLabelComposer().compose(carrier(),"ORDER-1",lines(),CarrierLabelComposer.Layout.SEPARATE_SLIP,FedExClient.Environment.PRODUCTION);try(var result=Loader.loadPDF(bytes)){assertThat(result.getNumberOfPages()).isEqualTo(3);assertThat(result.getPage(0).getMediaBox().getHeight()).isEqualTo(432);var text=new PDFTextStripper().getText(result);assertThat(text).contains("SKU-4","Qty: 123");}}
    @Test void combinedIsSandboxOnlyAndCreatesContinuation()throws Exception{var original=carrier();assertThatThrownBy(()->new CarrierLabelComposer().compose(original,"ORDER",lines(),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.PRODUCTION)).isInstanceOf(IllegalArgumentException.class);var preview=new CarrierLabelComposer().compose(original,"ORDER",lines(),CarrierLabelComposer.Layout.SANDBOX_80_20,FedExClient.Environment.SANDBOX);try(var result=Loader.loadPDF(preview)){assertThat(result.getNumberOfPages()).isEqualTo(2);assertThat(new PDFTextStripper().getText(result)).contains("NOT FOR SHIPPING","SKU-4");if(Boolean.getBoolean("fedex.renderPreview")){var directory=java.nio.file.Path.of(".local/fedex-preview");java.nio.file.Files.createDirectories(directory);java.nio.file.Files.write(directory.resolve("sandbox-preview.pdf"),preview);var renderer=new org.apache.pdfbox.rendering.PDFRenderer(result);javax.imageio.ImageIO.write(renderer.renderImageWithDPI(0,203),"png",directory.resolve("sandbox-preview.png").toFile());}}}
    @Test void originalIsByteForByteUnchanged()throws Exception{byte[] original=carrier();assertThat(new CarrierLabelComposer().compose(original,"ORDER",lines(),CarrierLabelComposer.Layout.ORIGINAL,FedExClient.Environment.PRODUCTION)).isEqualTo(original);}
    @Test void optedInLiveLayoutHasThreeBottomLinesAndSeparateOverflow()throws Exception {
        byte[] combined=new CarrierLabelComposer().compose(carrier(),"ORDER-1",lines(),CarrierLabelComposer.Layout.COMBINED_80_20,FedExClient.Environment.PRODUCTION);
        try(var result=Loader.loadPDF(combined)) {
            assertThat(result.getNumberOfPages()).isEqualTo(2);
            var stripper=new PDFTextStripper();stripper.setStartPage(1);stripper.setEndPage(1);
            assertThat(stripper.getText(result)).contains("Order ORDER-1","SKU-HONEY","SKU-2","SKU-3","Qty: 123").doesNotContain("SKU-4","SANDBOX","NOT FOR SHIPPING");
            stripper.setStartPage(2);stripper.setEndPage(2);
            assertThat(stripper.getText(result)).contains("SKU-4").doesNotContain("SKU-3");
            if(Boolean.getBoolean("fedex.renderPreview")) {
                var directory=java.nio.file.Path.of(".local/fedex-preview");java.nio.file.Files.createDirectories(directory);
                javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(result).renderImageWithDPI(0,203),"png",directory.resolve("combined-preview.png").toFile());
            }
        }
    }
}
