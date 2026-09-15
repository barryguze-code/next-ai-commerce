package com.nextaicommerce.platform.shipping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

@Component
public class ShippingLabelComposer {
    private static final PDRectangle FOUR_BY_SIX=new PDRectangle(288,432);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final float PAGE_MARGIN=5;
    private static final float PACKING_TOP=106;
    public record Composed(byte[] pdf,int pageCount){}

    public Composed compose(byte[] carrierPdf,String orderId,List<BuyShippingRepository.PackingLine> lines,boolean includeSlip){
        return compose(carrierPdf,orderId,lines,includeSlip,new BuyShippingRepository.PackingContext("Package","","AMBIENT",java.math.BigDecimal.ZERO,"USD",false,null,""));
    }

    public Composed compose(byte[] carrierPdf,String orderId,List<BuyShippingRepository.PackingLine> lines,boolean includeSlip,
            BuyShippingRepository.PackingContext context){
        try(PDDocument source=Loader.loadPDF(carrierPdf);PDDocument output=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            if(source.getNumberOfPages()==0)throw new IllegalStateException("Amazon returned an empty PDF label.");
            List<BuyShippingRepository.PackingLine> safe=lines==null?List.of():List.copyOf(lines);
            int packingPages=includeSlip?Math.max(1,(int)Math.ceil(safe.size()/2d)):0;
            LayerUtility layers=new LayerUtility(output);
            for(int index=0;index<source.getNumberOfPages();index++){
                List<BuyShippingRepository.PackingLine> first=index==0&&includeSlip?safe.subList(0,Math.min(2,safe.size())):null;
                addCarrierSheet(output,source,layers,index,orderId,first,packingPages,context);
            }
            if(includeSlip&&safe.size()>2){
                for(int from=2,page=2;from<safe.size();from+=2,page++)
                    addContinuationSlip(output,orderId,safe.subList(from,Math.min(safe.size(),from+2)),page,packingPages,context);
            }
            output.save(bytes);return new Composed(bytes.toByteArray(),output.getNumberOfPages());
        }catch(IOException ex){throw new IllegalStateException("The Amazon PDF label could not be prepared for printing.",ex);}
    }

    private static void addCarrierSheet(PDDocument output,PDDocument source,LayerUtility layers,int sourceIndex,String orderId,
            List<BuyShippingRepository.PackingLine> packingLines,int packingPages,BuyShippingRepository.PackingContext context)throws IOException{
        PDPage sheet=new PDPage(FOUR_BY_SIX);output.addPage(sheet);
        PDFormXObject carrier=layers.importPageAsForm(source,sourceIndex);
        float bottom=packingLines==null?PAGE_MARGIN:PACKING_TOP;
        float availableWidth=FOUR_BY_SIX.getWidth()-PAGE_MARGIN*2;
        float availableHeight=FOUR_BY_SIX.getHeight()-bottom-PAGE_MARGIN;
        PDRectangle box=carrier.getBBox();
        float scale=Math.min(availableWidth/box.getWidth(),availableHeight/box.getHeight());
        float x=PAGE_MARGIN+(availableWidth-box.getWidth()*scale)/2-box.getLowerLeftX()*scale;
        float y=bottom+(availableHeight-box.getHeight()*scale)/2-box.getLowerLeftY()*scale;
        try(PDPageContentStream canvas=new PDPageContentStream(output,sheet)){
            canvas.saveGraphicsState();canvas.transform(new Matrix(scale,0,0,scale,x,y));canvas.drawForm(carrier);canvas.restoreGraphicsState();
            if(packingLines!=null)drawCompactSlip(canvas,orderId,packingLines,1,packingPages,context);
        }
    }

    private static void drawCompactSlip(PDPageContentStream canvas,String orderId,List<BuyShippingRepository.PackingLine> lines,
            int page,int pages,BuyShippingRepository.PackingContext context)throws IOException{
        var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        line(canvas,5,PACKING_TOP-2,283,PACKING_TOP-2);
        String packageName=blank(context.packageName())?"Package":context.packageName();
        if(!blank(context.containerCode()))packageName+=" · Box "+context.containerCode();
        text(canvas,bold,7.5f,9,93,safe(packageName,30));
        String payment=safe(context.currency(),3)+" "+context.customerShipping().setScale(2,RoundingMode.HALF_UP);
        text(canvas,regular,6.5f,119,93,safe("Amazon "+orderId+" · Shipping "+payment,49));
        text(canvas,bold,6.5f,262,93,page+"/"+pages);
        if(!blank(context.operationNote()))text(canvas,context.extraIce()?bold:regular,6.3f,9,82,safe((context.extraIce()?"EXTRA ICE · ":"")+context.operationNote(),66));
        if(lines.isEmpty()){
            text(canvas,regular,8,9,53,"No item details were available for this package.");return;
        }
        int y=lines.size()==1?49:63;
        for(BuyShippingRepository.PackingLine item:lines){
            circle(canvas,19,y,10);text(canvas,bold,8.5f,item.quantity()>9?13:16,y-3,Integer.toString(item.quantity()));
            String title=(blank(item.brand())?"Item":item.brand())+" · "+item.title();
            text(canvas,bold,7.2f,35,y+5,safe(title,57));
            String detail=item.sellerSku()+" · "+item.location();
            if(item.expiration()!=null)detail+=" · Expires "+DATE.format(item.expiration());
            text(canvas,regular,6.4f,35,y-7,safe(detail,70));
            y-=29;
        }
    }

    private static void addContinuationSlip(PDDocument document,String orderId,List<BuyShippingRepository.PackingLine> lines,int page,int pages,
            BuyShippingRepository.PackingContext context)throws IOException{
        PDPage sheet=new PDPage(FOUR_BY_SIX);document.addPage(sheet);
        var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try(PDPageContentStream canvas=new PDPageContentStream(document,sheet)){
            text(canvas,bold,15,18,402,"PACKING DETAILS");
            String packageName=(blank(context.packageName())?"Package":context.packageName())+(blank(context.containerCode())?"":" · Box "+context.containerCode());
            text(canvas,bold,8,140,402,safe(packageName,25));
            String payment="Customer shipping "+safe(context.currency(),3)+" "+context.customerShipping().setScale(2,RoundingMode.HALF_UP);
            text(canvas,regular,8,18,386,"Amazon "+safe(orderId,25)+" · "+safe(payment,25));
            text(canvas,regular,8,255,402,page+"/"+pages);line(canvas,18,375,270,375);
            if(!blank(context.operationNote()))text(canvas,context.extraIce()?bold:regular,8,18,363,safe((context.extraIce()?"EXTRA ICE · ":"")+context.operationNote(),58));
            int y=340;
            for(BuyShippingRepository.PackingLine item:lines){
                canvas.addRect(18,y-118,252,108);canvas.stroke();circle(canvas,42,y-42,20);text(canvas,bold,15,34,y-47,Integer.toString(item.quantity()));
                text(canvas,bold,11,70,y-25,safe(blank(item.brand())?"ITEM":item.brand().toUpperCase(),32));text(canvas,bold,9,70,y-43,safe(item.sellerSku(),38));
                text(canvas,regular,9,70,y-61,safe(item.title(),42));String route="Location "+safe(item.location(),18);
                if(item.expiration()!=null)route+=" · Expires "+DATE.format(item.expiration());text(canvas,regular,8,70,y-83,safe(route,52));y-=126;
            }
            text(canvas,regular,7,18,18,"Packing continuation · carrier label is on page 1.");
        }
    }

    private static void text(PDPageContentStream canvas,PDType1Font font,float size,float x,float y,String value)throws IOException{
        canvas.beginText();canvas.setFont(font,size);canvas.newLineAtOffset(x,y);canvas.showText(ascii(value));canvas.endText();
    }
    private static void line(PDPageContentStream canvas,float x1,float y1,float x2,float y2)throws IOException{canvas.moveTo(x1,y1);canvas.lineTo(x2,y2);canvas.stroke();}
    private static void circle(PDPageContentStream canvas,float x,float y,float r)throws IOException{
        float k=.55228475f*r;canvas.moveTo(x+r,y);canvas.curveTo(x+r,y+k,x+k,y+r,x,y+r);canvas.curveTo(x-k,y+r,x-r,y+k,x-r,y);
        canvas.curveTo(x-r,y-k,x-k,y-r,x,y-r);canvas.curveTo(x+k,y-r,x+r,y-k,x+r,y);canvas.stroke();
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String safe(String value,int max){String result=value==null?"":value.trim();return result.length()<=max?result:result.substring(0,Math.max(0,max-1))+"…";}
    private static String ascii(String value){return value.replace('•','-').replace('·','-').replace('…','.').replaceAll("[^\\x20-\\x7E]","?");}
}
