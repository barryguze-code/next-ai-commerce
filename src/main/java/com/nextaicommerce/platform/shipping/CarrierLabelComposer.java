package com.nextaicommerce.platform.shipping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

@Component
public class CarrierLabelComposer {
    public enum Layout { ORIGINAL, SEPARATE_SLIP, COMBINED_80_20, SANDBOX_80_20 }
    public record Line(String brand,String sku,String description,int quantity) {}
    private static final PDRectangle THERMAL=new PDRectangle(288,432);
    public byte[] letter(byte[] pdf) {
        try(var source=Loader.loadPDF(pdf);var output=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var utility=new LayerUtility(output);
            for(int i=0;i<source.getNumberOfPages();i++) {
                var form=utility.importPageAsForm(source,i);var box=form.getBBox();
                if(box.getWidth()>612||box.getHeight()>792)throw new IllegalArgumentException("Label does not fit Letter paper at actual size.");
                var page=new PDPage(PDRectangle.LETTER);output.addPage(page);
                try(var canvas=new PDPageContentStream(output,page)) {canvas.transform(Matrix.getTranslateInstance((612-box.getWidth())/2-box.getLowerLeftX(),(792-box.getHeight())/2-box.getLowerLeftY()));canvas.drawForm(form);}
            }
            output.save(bytes);return bytes.toByteArray();
        }catch(IOException ex){throw new IllegalStateException("Could not prepare Letter paper. Original label is retained.");}
    }
    public byte[] compose(byte[] original,String order,List<Line> lines,Layout layout,FedExClient.Environment environment) {
        if(layout==Layout.SANDBOX_80_20&&environment!=FedExClient.Environment.SANDBOX)
            throw new IllegalArgumentException("The combined layout is a sandbox preview only.");
        try(PDDocument source=Loader.loadPDF(original);PDDocument output=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            if(source.getNumberOfPages()<1||source.getNumberOfPages()>10)throw new IllegalArgumentException("Unexpected carrier PDF page count.");
            if(layout==Layout.ORIGINAL)return original.clone();
            if(layout==Layout.SEPARATE_SLIP) {
                // The default separate layout retains original media box and rotation.
                for(PDPage page:source.getPages())output.importPage(page);
            } else {
                var utility=new LayerUtility(output);
                for(int i=0;i<source.getNumberOfPages();i++) {
                    PDPage page=new PDPage(THERMAL);output.addPage(page);
                    var label=utility.importPageAsForm(source,i);var box=label.getBBox();
                    float scale=Math.min(288/box.getWidth(),345.6f/box.getHeight());
                    try(var canvas=new PDPageContentStream(output,page)) {
                        canvas.saveGraphicsState();canvas.transform(new Matrix(scale,0,0,scale,(288-box.getWidth()*scale)/2-box.getLowerLeftX()*scale,86.4f-box.getLowerLeftY()*scale));canvas.drawForm(label);canvas.restoreGraphicsState();
                        slip(canvas,order,lines.subList(0,Math.min(3,lines.size())),true,environment==FedExClient.Environment.SANDBOX);
                    }
                }
            }
            int start=layout==Layout.SEPARATE_SLIP?0:3;
            for(int offset=start;offset<lines.size();offset+=3) {
                PDPage page=new PDPage(THERMAL);output.addPage(page);
                try(var canvas=new PDPageContentStream(output,page)) {
                    text(canvas,bold(),12,8,410,"PACKING SLIP - "+ascii(order));
                    slip(canvas,order,lines.subList(offset,Math.min(offset+3,lines.size())),false,false);
                    text(canvas,regular(),8,8,15,"Carrier label is on the first page. Not a shipping label.");
                }
            }
            output.save(bytes);return bytes.toByteArray();
        }catch(IOException ex){throw new IllegalStateException("The carrier PDF could not be prepared. The original label is retained.");}
    }
    private static void slip(PDPageContentStream canvas,String order,List<Line> lines,boolean combined,boolean sandbox)throws IOException {
        float top=combined?82:385;
        canvas.setLineWidth(.5f);canvas.moveTo(6,top);canvas.lineTo(282,top);canvas.stroke();
        text(canvas,bold(),7,6,top-10,sandbox?"SANDBOX PREVIEW - NOT FOR SHIPPING":"Order "+ascii(order));
        for(int i=0;i<lines.size();i++) {
            Line item=lines.get(i);float y=top-26-i*19;
            String qty="Qty: "+item.quantity();float qw=bold().getStringWidth(qty)/1000*7+7;
            // Keep brand, SKU and quantity ahead of the optional description; do not shrink below 7pt.
            String identity=fit(bold(),7,ascii(item.brand())+" - "+ascii(item.sku()),135);
            text(canvas,bold(),7,6,y,identity);
            canvas.addRect(144,y-3,qw,12);canvas.stroke();text(canvas,bold(),7,147,y,qty);
            text(canvas,regular(),7,148+qw,y,fit(regular(),7,ascii(item.description()),132-qw));
        }
    }
    private static String fit(PDType1Font font,float size,String value,float width)throws IOException {
        if(font.getStringWidth(value)/1000*size<=width)return value;
        while(!value.isEmpty()&&font.getStringWidth(value+"...")/1000*size>width)value=value.substring(0,value.length()-1);
        return value+"...";
    }
    private static void text(PDPageContentStream canvas,PDType1Font font,float size,float x,float y,String value)throws IOException {canvas.beginText();canvas.setFont(font,size);canvas.newLineAtOffset(x,y);canvas.showText(value);canvas.endText();}
    private static String ascii(String value){return value==null?"":value.replaceAll("[^\\x20-\\x7E]","?");}
    private static PDType1Font bold(){return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);}
    private static PDType1Font regular(){return new PDType1Font(Standard14Fonts.FontName.HELVETICA);}
}
