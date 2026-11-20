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
    public record Allocation(String itemCode,java.math.BigDecimal quantity,java.time.LocalDate expiration,String location) {}
    public record Line(String brand,String sku,String description,int quantity,List<Allocation> allocations,java.math.BigDecimal mappedQuantity,String packageName,String format,java.util.UUID accountItemId,java.util.UUID locationId) {
        public Line(String brand,String sku,String description,int quantity,List<Allocation> allocations,java.math.BigDecimal mappedQuantity,String packageName,String format){this(brand,sku,description,quantity,allocations,mappedQuantity,packageName,format,null,null);}
        public Line(String brand,String sku,String description,int quantity,List<Allocation> allocations,java.math.BigDecimal mappedQuantity,String packageName){this(brand,sku,description,quantity,allocations,mappedQuantity,packageName,null);}
        public Line(String brand,String sku,String description,int quantity){this(brand,sku,description,quantity,List.of(),null,null);}
        public Line(String brand,String sku,String description,int quantity,List<Allocation> allocations){this(brand,sku,description,quantity,allocations,null,null);}
        public Line withPackage(String name){return new Line(brand,sku,description,quantity,allocations,mappedQuantity,name,format,accountItemId,locationId);}
        public static Line mapped(String brand,String sku,String description,int units,List<java.math.BigDecimal> components,String packageName,List<Allocation> allocations){
            java.math.BigDecimal total=null;
            if(!components.isEmpty()){
                if(units<1||components.stream().anyMatch(q->q==null||q.signum()<=0))throw new IllegalArgumentException("Verified mapping quantities must be positive.");
                total=components.stream().reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).multiply(java.math.BigDecimal.valueOf(units));
            }
            return new Line(brand,sku,description,units,allocations,total,packageName);
        }
        public static Line accountItem(String brand,String itemCode,String description,java.math.BigDecimal quantity,String location){
            if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Packing quantity must be verified and positive.");
            if(itemCode==null||itemCode.isBlank()||location==null||location.isBlank())throw new IllegalArgumentException("Packing requires an account item code and actual allocation location.");
            return new Line(brand,itemCode,description,1,List.of(new Allocation(itemCode,quantity,null,location)),quantity,null,"ACCOUNT_ITEM_V2");
        }
        public static Line accountItem(String brand,String itemCode,String description,java.math.BigDecimal quantity,String location,java.util.UUID itemId,java.util.UUID locationId){
            if(itemId==null||locationId==null)throw new IllegalArgumentException("Packing requires actual mapped item and reservation location identities.");
            String code=itemCode==null||itemCode.isBlank()?"N/A":itemCode;
            String label=location==null||location.isBlank()?"N/A":location;
            var row=accountItem(brand,code,description,quantity,label);
            return new Line(row.brand(),row.sku(),row.description(),row.quantity(),row.allocations(),row.mappedQuantity(),row.packageName(),row.format(),itemId,locationId);
        }
        public Line {allocations=allocations==null?List.of():List.copyOf(allocations);}
    }
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
        if(layout==Layout.SANDBOX_80_20||layout==Layout.COMBINED_80_20)return compact(original,lines);
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
            // Full identity and actual lot allocations are never dropped to fit the thermal strip.
            boolean needsDetails=false;
            for(var item:lines)needsDetails|=needsDetails(item);
            if(needsDetails)details(output,order,lines);
            output.save(bytes);return bytes.toByteArray();
        }catch(IOException ex){throw new IllegalStateException("The carrier PDF could not be prepared. The original label is retained.");}
    }
    private record Piece(String text,boolean box,float scale,boolean strong) { Piece(String text,boolean box){this(text,box,1,box);} Piece(String text,boolean box,float scale){this(text,box,scale,box);} }
    private record PackingRow(float size,List<List<Piece>> lines) {}
    public void validateCompact(List<Line> lines){compactRows(lines);}
    private static List<PackingRow> compactRows(List<Line> items){
        if(items.stream().anyMatch(l->"ACCOUNT_ITEM_V2".equals(l.format())))return accountRows(items);
        try{
            var rows=new java.util.ArrayList<PackingRow>();float height=0;
            for(var item:items){
                String brand=item.brand()==null||item.brand().isBlank()?"Brand unknown":item.brand();
                String sku=item.sku();if(sku==null||sku.isBlank())throw new IllegalArgumentException("Packing requires the full seller SKU.");
                String pack=item.packageName()==null||item.packageName().isBlank()?"Package not recorded":item.packageName();
                String qty=item.mappedQuantity()==null?"?":item.mappedQuantity().stripTrailingZeros().toPlainString();
                float size=8;
                var mandatory=List.of(new Piece(brand+"-",false),new Piece(qty,true),new Piece(" | "+sku,false),new Piece(" - "+pack,false));
                if(width(mandatory,size)>276)size=7;
                List<List<Piece>> physical;
                if(width(mandatory,size)<=276){
                    float available=276-width(mandatory,size);String description=ascii(item.description());
                    if(!description.isBlank()&&available>width("-...",regular(),size))description=fit(regular(),size,"-"+description,available);else description="";
                    physical=List.of(List.of(mandatory.get(0),mandatory.get(1),new Piece(" | "+sku+description,false),mandatory.get(3)));
                }else physical=wrap(mandatory,size);
                height+=physical.size()*(size+5)+3;rows.add(new PackingRow(size,physical));
            }
            if(height>74)throw new IllegalArgumentException("Packing needs "+Math.round(height)+" points; the label strip holds 74. Shorten the package name or use a separate packing document. No items were omitted.");
            return rows;
        }catch(java.io.IOException|IllegalArgumentException error){
            if(error instanceof IllegalArgumentException argument&&argument.getMessage()!=null&&(argument.getMessage().startsWith("Packing ")))throw argument;
            throw new IllegalArgumentException("Packing contains characters unsupported by the label font. Original identities were not shortened or replaced.");
        }
    }
    private static List<PackingRow> accountRows(List<Line> items){
        try{
            var rows=new java.util.ArrayList<PackingRow>();
            var packages=items.stream().map(Line::packageName).distinct().toList();
            if(packages.size()>1)throw new IllegalArgumentException("Packing package snapshot is incomplete or conflicting.");
            String header=packages.isEmpty()?null:packages.getFirst();
            if(header!=null){
                if(header.isBlank())throw new IllegalArgumentException("Packing package name is missing.");
                float headerSize=8.5f;if(width(header,bold(),headerSize)>276)headerSize=7;
                if(width(header,bold(),headerSize)>276)throw new IllegalArgumentException("Packing package name cannot fit the strip. No items were omitted.");
                rows.add(new PackingRow(headerSize,List.of(List.of(new Piece(header,false,1,true)))));
            }
            for(var item:items){
                if(!"ACCOUNT_ITEM_V2".equals(item.format())||item.allocations().size()!=1||item.mappedQuantity()==null||item.mappedQuantity().signum()<=0)
                    throw new IllegalArgumentException("Packing snapshot is incomplete or has mixed formats.");
                String location=item.allocations().getFirst().location();
                if(location==null||location.isBlank()||item.sku()==null||item.sku().isBlank())throw new IllegalArgumentException("Packing requires exact account item and location codes.");
                // Preserve actual F7/F9/MAIN codes; never invent a three-character alias.
                String brand=item.brand()==null||item.brand().isBlank()?"Brand unknown":item.brand();
                var qty=new Piece(item.mappedQuantity().stripTrailingZeros().toPlainString(),true,1.3f);
                var separator=new Piece(" - ",false);var brandRun=new Piece(brand,false,1,true);var identity=new Piece(" - "+item.sku()+" - ",false);
                var end=new Piece(" - ",false);var loc=new Piece(location.length()<3?location+" ".repeat(3-location.length()):location,true);
                var mandatory=List.of(qty,separator,brandRun,identity,end,loc);float size=8;
                if(width(mandatory,size)>276)size=7;
                if(width(mandatory,size)>276)throw new IllegalArgumentException("Packing mandatory identities do not fit one row. No items were omitted.");
                float available=276-width(mandatory,size);String description=available>=width("...",regular(),size)?fit(regular(),size,ascii(item.description()),available):"";
                rows.add(new PackingRow(size,List.of(List.of(qty,separator,brandRun,identity,new Piece(description,false),end,loc))));
            }
            if(rows.size()*18>74)throw new IllegalArgumentException("Packing has too many mapped item/location rows for the label strip. No items were omitted.");
            return rows;
        }catch(IOException error){throw new IllegalArgumentException("Packing identity is unsupported by the label font. No identity was shortened.");}
    }
    private static float width(String text,PDType1Font font,float size)throws IOException{return font.getStringWidth(text)/1000*size;}
    private static float width(List<Piece> pieces,float size)throws IOException{float total=0;for(var p:pieces)total+=width(p.text(),p.strong()?bold():regular(),size*p.scale())+(p.box()?6:0);return total;}
    private static List<List<Piece>> wrap(List<Piece> pieces,float size)throws IOException{
        var lines=new java.util.ArrayList<List<Piece>>();var current=new java.util.ArrayList<Piece>();float used=0;
        for(var piece:pieces){
            if(piece.box()){
                float w=width(List.of(piece),size);if(w>276)throw new IllegalArgumentException("Packing quantity cannot fit the label strip.");
                if(used+w>276){lines.add(List.copyOf(current));current.clear();used=0;}current.add(piece);used+=w;continue;
            }
            String remaining=piece.text();
            while(!remaining.isEmpty()){
                int end=remaining.length();while(end>0&&width(remaining.substring(0,end),regular(),size)>276-used)end--;
                if(end==0){lines.add(List.copyOf(current));current.clear();used=0;continue;}
                String part=remaining.substring(0,end);current.add(new Piece(part,false));used+=width(part,regular(),size);remaining=remaining.substring(end);
                if(!remaining.isEmpty()){lines.add(List.copyOf(current));current.clear();used=0;}
            }
        }
        if(!current.isEmpty())lines.add(List.copyOf(current));return lines;
    }
    private byte[] compact(byte[] original,List<Line> items){
        var rows=compactRows(items);
        try(var source=Loader.loadPDF(original);var output=new PDDocument();var bytes=new ByteArrayOutputStream()){
            if(source.getNumberOfPages()<1||source.getNumberOfPages()>10)throw new IllegalArgumentException("Unexpected carrier PDF page count.");
            var utility=new LayerUtility(output);
            for(int i=0;i<source.getNumberOfPages();i++){
                var page=new PDPage(THERMAL);output.addPage(page);var label=utility.importPageAsForm(source,i);var box=carrierRegion(source,i,label.getBBox());float scale=Math.min(288/box.getWidth(),345.6f/box.getHeight());
                try(var canvas=new PDPageContentStream(output,page)){
                    canvas.saveGraphicsState();canvas.addRect(0,86.4f,288,345.6f);canvas.clip();canvas.transform(new Matrix(scale,0,0,scale,(288-box.getWidth()*scale)/2-box.getLowerLeftX()*scale,86.4f-box.getLowerLeftY()*scale));canvas.drawForm(label);canvas.restoreGraphicsState();
                    float y=71;
                    for(var row:rows){for(var line:row.lines()){
                        float x=6;
                        for(var piece:line){float fontSize=row.size()*piece.scale(),w=width(piece.text(),piece.strong()?bold():regular(),fontSize);
                            if(piece.box()){canvas.setLineWidth(.65f);canvas.addRect(x,y-2,w+6,fontSize+4);canvas.stroke();text(canvas,bold(),fontSize,x+3,y,piece.text());x+=w+6;}
                            else{text(canvas,piece.strong()?bold():regular(),fontSize,x,y,piece.text());x+=w;}
                        }y-=items.stream().anyMatch(l->"ACCOUNT_ITEM_V2".equals(l.format()))?15:row.size()+5;
                    }y-=3;}
                }
            }
            output.save(bytes);return bytes.toByteArray();
        }catch(IOException error){throw new IllegalStateException("The carrier PDF could not be prepared. The original label is retained.");}
    }
    /** FedEx's sandbox sometimes wraps PAPER_4X6 artwork in a mostly blank Letter page.
     * Measure the complete visible artwork, including every barcode/text mark, and
     * retain a quiet border. Only the vector placement changes; the archive stays raw.
     * Other paper sizes, rotations and annotated pages retain their original geometry.
     */
    private static PDRectangle carrierRegion(PDDocument source,int index,PDRectangle box)throws IOException{
        var page=source.getPage(index);
        var media=page.getMediaBox();var crop=page.getCropBox();
        // Rendering measures the CropBox; imported forms may use different bounds.
        // Ambiguous coordinate systems keep the original full-page fit.
        if(Math.floorMod(page.getRotation(),360)!=0||Math.abs(box.getWidth()-612)>1||Math.abs(box.getHeight()-792)>1||!page.getAnnotations().isEmpty()
                ||!sameBounds(box,media)||!sameBounds(box,crop))return box;
        var image=new org.apache.pdfbox.rendering.PDFRenderer(source).renderImageWithDPI(index,300,org.apache.pdfbox.rendering.ImageType.RGB);
        try{
            int left=image.getWidth(),right=-1,top=image.getHeight(),bottom=-1;
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
            if(right<left)return box;
            float sx=box.getWidth()/image.getWidth(),sy=box.getHeight()/image.getHeight(),quiet=12;
            float x0=Math.max(box.getLowerLeftX(),box.getLowerLeftX()+left*sx-quiet),x1=Math.min(box.getUpperRightX(),box.getLowerLeftX()+(right+1)*sx+quiet);
            float y0=Math.max(box.getLowerLeftY(),box.getLowerLeftY()+(image.getHeight()-1-bottom)*sy-quiet),y1=Math.min(box.getUpperRightY(),box.getLowerLeftY()+(image.getHeight()-top)*sy+quiet);
            float width=x1-x0,height=y1-y0;
            // Never enlarge an ambiguous tiny mark into a shipping label. Measuring at
            // 300dpi is at least as detailed as this non-enlarging 300dpi print path.
            if(width<=0||height<=0||Math.min(288/width,345.6f/height)>1)return box;
            return new PDRectangle(x0,y0,width,height);
        }finally{image.flush();}
    }
    private static boolean sameBounds(PDRectangle a,PDRectangle b){
        return a.getLowerLeftX()==b.getLowerLeftX()&&a.getLowerLeftY()==b.getLowerLeftY()&&a.getUpperRightX()==b.getUpperRightX()&&a.getUpperRightY()==b.getUpperRightY();
    }
    private static void slip(PDPageContentStream canvas,String order,List<Line> lines,boolean combined,boolean sandbox)throws IOException {
        float top=combined?82:385;
        canvas.setLineWidth(.5f);canvas.moveTo(6,top);canvas.lineTo(282,top);canvas.stroke();
        text(canvas,bold(),7,6,top-10,sandbox?"SANDBOX PREVIEW - NOT FOR SHIPPING":"Order "+ascii(order));
        text(canvas,regular(),7,6,top-21,"SKU / PRODUCT");text(canvas,regular(),7,114,top-21,"QTY");text(canvas,regular(),7,178,top-21,"EXPIRY");text(canvas,regular(),7,234,top-21,"LOCATION");
        for(int i=0;i<lines.size();i++) {
            Line item=lines.get(i);float y=top-34-i*17;
            String identity=ascii(item.sku())+" / "+ascii(item.description());
            text(canvas,bold(),7,6,y,fit(bold(),7,identity,100));
            text(canvas,bold(),7,114,y,"Qty: "+item.quantity());
            var expiries=item.allocations().stream().map(a->a.expiration()==null?"Not dated":java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy").format(a.expiration())).distinct().toList();
            var locations=item.allocations().stream().map(Allocation::location).distinct().toList();
            text(canvas,regular(),7,178,y,expiries.isEmpty()?"Unknown":expiries.size()==1?expiries.getFirst():"See page");
            String location=locations.isEmpty()?"Unknown":locations.size()==1?locations.getFirst():"See page";
            text(canvas,regular(),7,234,y,fit(regular(),7,ascii(location),48));
        }
    }
    private static boolean needsDetails(Line item)throws IOException {
        String identity=ascii(item.sku())+" / "+ascii(item.description());
        return bold().getStringWidth(identity)/1000*7>100||item.allocations().size()>1
            ||item.allocations().stream().anyMatch(a->ascii(a.location()).length()>8);
    }
    private static void details(PDDocument output,String order,List<Line> lines)throws IOException {
        PDPageContentStream canvas=null;float y=0;
        try {
            for(var item:lines){
                var rows=new java.util.ArrayList<String>();rows.add("Qty: "+item.quantity()+" | "+ascii(item.sku()));rows.add(ascii(item.description()));
                if(item.allocations().isEmpty())rows.add("Allocation, expiry and location: unknown (no picked lot evidence).");
                for(var a:item.allocations())rows.add(ascii(a.itemCode())+" | "+a.quantity().stripTrailingZeros().toPlainString()+" each | "+(a.expiration()==null?"Expiry not recorded":a.expiration())+" | "+ascii(a.location()));
                for(String row:rows){
                    while(!row.isEmpty()){
                        if(canvas==null||y<30){if(canvas!=null)canvas.close();var page=new PDPage(THERMAL);output.addPage(page);canvas=new PDPageContentStream(output,page);text(canvas,bold(),10,8,410,"PACKING ALLOCATIONS");text(canvas,bold(),8,8,396,fit(bold(),8,ascii(order),270));text(canvas,regular(),7,8,383,"Not a shipping label. Carrier original is retained.");y=363;}
                        String part=fit(regular(),8,row,270);int consumed=part.endsWith("...")?part.length()-3:part.length();
                        text(canvas,regular(),8,8,y,part.endsWith("...")?part.substring(0,part.length()-3):part);y-=12;row=row.substring(consumed);
                    }
                }y-=10;
            }
        } finally {if(canvas!=null)canvas.close();}
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
