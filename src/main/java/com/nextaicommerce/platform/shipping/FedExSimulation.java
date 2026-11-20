package com.nextaicommerce.platform.shipping;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import tools.jackson.databind.*;

/** An offline fixture transport, never a fallback for a failed real carrier request. */
final class FedExSimulation implements FedExClient.Transport {
    private final ObjectMapper json;
    static final String WARNING="SIMULATED - NOT FOR SHIPPING";
    FedExSimulation(ObjectMapper json){this.json=json;}
    public FedExClient.Reply send(HttpRequest request){
        if(!"https".equals(request.uri().getScheme())||!"apis-sandbox.fedex.com".equals(request.uri().getHost())||!"POST".equals(request.method()))
            throw new IllegalStateException("Simulation cannot use production carrier operations.");
        String path=request.uri().getPath();var reply=json.createObjectNode();
        if(path.equals("/oauth/token"))reply.put("access_token","local-simulation-only").put("expires_in",3600);
        else if(Set.of("/rate/v1/rates/quotes","/rate/v1/comprehensiverates/quotes").contains(path)){
            var rates=reply.putObject("output").putArray("rateReplyDetails");
            for(var rate:List.of(new FedExPayloads.Rate("FEDEX_GROUND","FedEx Ground",new BigDecimal("12.80"),"USD"))){
                var item=rates.addObject();item.put("serviceType",rate.serviceType()).put("serviceName",rate.serviceName());
                item.putArray("ratedShipmentDetails").addObject().put("rateType","ACCOUNT").put("totalNetCharge",rate.amount()).put("currency",rate.currency());
            }
        }else if(path.equals("/ship/v1/shipments")){
            reply.put("localSimulation",true);reply.put("reference","SIMULATED-"+UUID.randomUUID());reply.put("pdf",Base64.getEncoder().encodeToString(label(requestPayload(request))));
        }else throw new IllegalStateException("Unsupported simulated operation.");
        return new FedExClient.Reply(200,reply.toString());
    }
    static FedExPayloads.Label parseLabel(JsonNode reply){
        if(!reply.path("localSimulation").asBoolean()||!reply.path("reference").asText("").matches("SIMULATED-[a-f0-9-]{36}"))
            throw new IllegalStateException("Expected a clearly marked local simulation label.");
        byte[] pdf=Base64.getDecoder().decode(reply.path("pdf").asText());
        if(pdf.length<5||pdf.length>5_000_000||!new String(pdf,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalStateException("Invalid simulated PDF.");
        return new FedExPayloads.Label(reply.path("reference").asText(),pdf);
    }
    private JsonNode requestPayload(HttpRequest request){
        var bytes=new ByteArrayOutputStream();var done=new java.util.concurrent.CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer data){if(bytes.size()+data.remaining()>1_000_000){done.completeExceptionally(new IllegalArgumentException("Simulated request too large."));return;}byte[] value=new byte[data.remaining()];data.get(value);bytes.writeBytes(value);}
            public void onError(Throwable error){done.completeExceptionally(error);}
            public void onComplete(){done.complete(null);}
        });
        done.orTimeout(5,java.util.concurrent.TimeUnit.SECONDS).join();return json.readTree(bytes.toByteArray());
    }
    static byte[] label(){return label(new ObjectMapper().createObjectNode());}
    static byte[] label(JsonNode request){
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()){
            var page=new PDPage(new PDRectangle(288,432));doc.addPage(page);
            try(var canvas=new PDPageContentStream(doc,page)){
                var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                text(canvas,bold,12,14,408,WARNING);text(canvas,bold,20,14,366,"LOCAL TEST LABEL");
                text(canvas,regular,11,14,328,"No carrier request, charge or shipment.");
                var shipment=request.path("requestedShipment");String service=shipment.path("serviceType").asText("LOCAL TEST SERVICE");
                text(canvas,bold,10,14,306,service);
                text(canvas,bold,12,14,274,"SYNTHETIC RECIPIENT");
                var recipient=shipment.path("recipients").path(0);var address=recipient.path("address");var addressLines=new ArrayList<String>();
                addressLines.add(recipient.path("contact").path("personName").asText("Local Test Recipient"));
                for(var line:address.path("streetLines"))addressLines.add(line.asText());
                if(addressLines.size()==1)addressLines.add("10 FedEx Parkway");
                addressLines.add(address.path("city").asText("Collierville")+", "+address.path("stateOrProvinceCode").asText("TN")+" "+address.path("postalCode").asText("38017")+" "+address.path("countryCode").asText("US"));
                int y=250;for(String line:addressLines){for(int start=0;start<line.length();start+=42){if(y<170)throw new IllegalArgumentException("Use a shorter synthetic address for this local label fixture.");text(canvas,regular,10,14,y,line.substring(start,Math.min(start+42,line.length())));y-=12;}}
                var parcel=shipment.path("requestedPackageLineItems").path(0);
                if(parcel.has("weight"))text(canvas,regular,9,14,160,"Package: "+parcel.path("weight").path("value").asText()+" lb / "+parcel.path("dimensions").path("length").asText()+" x "+parcel.path("dimensions").path("width").asText()+" x "+parcel.path("dimensions").path("height").asText()+" in");
                text(canvas,bold,11,14,148,"NO TRACKING / NO ROUTING BARCODE");
                text(canvas,regular,10,14,120,"Use this PDF to review packing and print layout.");
                text(canvas,bold,12,14,40,WARNING);
            }doc.save(bytes);return bytes.toByteArray();
        }catch(java.io.IOException error){throw new IllegalStateException("Could not create simulated PDF.");}
    }
    static byte[] watermark(byte[] pdf){
        try(var doc=Loader.loadPDF(pdf);var bytes=new ByteArrayOutputStream()){
            for(var page:doc.getPages())try(var canvas=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)){
                text(canvas,new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),9,8,90,WARNING);
            }doc.save(bytes);return bytes.toByteArray();
        }catch(java.io.IOException error){throw new IllegalStateException("Could not mark the simulated PDF.");}
    }
    private static void text(PDPageContentStream canvas,PDType1Font font,int size,int x,int y,String text)throws java.io.IOException{
        canvas.beginText();canvas.setFont(font,size);canvas.newLineAtOffset(x,y);canvas.showText(text);canvas.endText();
    }
}
