package com.nextaicommerce.platform.shipping;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

@Component
public class ShippingBatchPdfComposer {
    public byte[] merge(List<byte[]> documents){
        if(documents==null||documents.isEmpty())throw new IllegalArgumentException("This batch has no purchased labels ready to print.");
        try(PDDocument output=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            for(byte[] document:documents)try(PDDocument source=Loader.loadPDF(document)){
                for(var page:source.getPages())output.importPage(page);
            }
            output.save(bytes);return bytes.toByteArray();
        }catch(Exception ex){throw new IllegalStateException("The batch print file could not be prepared.",ex);}
    }
}
