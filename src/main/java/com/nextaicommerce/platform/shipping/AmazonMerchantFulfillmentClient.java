package com.nextaicommerce.platform.shipping;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AmazonMerchantFulfillmentClient {
    private final AmazonSpApiClient api;
    private final ObjectMapper json;
    private final AmazonStoreRateLimiter limiter;

    public AmazonMerchantFulfillmentClient(AmazonSpApiClient api,ObjectMapper json,AmazonStoreRateLimiter limiter){this.api=api;this.json=json;this.limiter=limiter;}

    public record Rate(String serviceId,String offerId,String carrierName,String serviceName,BigDecimal amount,
            String currency,Instant shipDate,Instant earliestDelivery,Instant latestDelivery,
            boolean requiresSellerInput,List<String> labelFormats,JsonNode payload,boolean cheapest,boolean fastest){}
    public record Quote(List<Rate> rates,JsonNode response,String requestId,List<String> notices){}
    public record Purchase(String shipmentId,String trackingId,String serviceId,String carrierName,String serviceName,
            BigDecimal price,String currency,Instant shipDate,Instant earliestDelivery,Instant latestDelivery,
            byte[] labelPdf,JsonNode response,String requestId){}
    public record Refund(JsonNode response,String requestId){}
    public record ShipmentStatus(String status,JsonNode response,String requestId){}
    public record PurchaseMetadata(String shipmentId,String trackingId,String serviceId,String carrierName,String serviceName,
            BigDecimal price,String currency,Instant shipDate,Instant earliestDelivery,Instant latestDelivery,JsonNode response,String requestId){}

    public Quote eligible(UUID tenantId,UUID connectionId,String marketplaceId,JsonNode requestDetails){
        limiter.rates(tenantId,connectionId);
        var body=json.createObjectNode();body.set("ShipmentRequestDetails",requestDetails);
        var filter=body.putObject("ShippingOfferingFilter");
        filter.put("IncludeComplexShippingOptions",true).put("IncludePackingSlipWithLabel",false)
            .put("DeliveryExperience","NoPreference").put("CarrierWillPickUp","NoPreference");
        AmazonSpApiClient.ApiResponse response=api.postAt(tenantId,connectionId,endpoint(marketplaceId),
            "/mfn/v0/eligibleShippingServices",body.toString());
        JsonNode payload=payload(response.json());JsonNode services=payload.path("ShippingServiceList");
        List<Rate> raw=new ArrayList<>();
        if(services.isArray())for(JsonNode service:services){
            JsonNode adjusted=service.path("RateWithAdjustments").isObject()?service.path("RateWithAdjustments"):service.path("Rate");
            JsonNode base=service.path("Rate");
            BigDecimal amount=decimal(adjusted.path("Amount"),decimal(base.path("Amount"),BigDecimal.ZERO));
            String currency=text(adjusted,"CurrencyCode",text(base,"CurrencyCode","USD"));
            raw.add(new Rate(text(service,"ShippingServiceId",null),text(service,"ShippingServiceOfferId",null),
                text(service,"CarrierName","Carrier"),text(service,"ShippingServiceName","Shipping service"),amount,currency,
                instant(service.path("ShipDate")),instant(service.path("EarliestEstimatedDeliveryDate")),
                instant(service.path("LatestEstimatedDeliveryDate")),service.path("RequiresAdditionalSellerInputs").asBoolean(false),
                labelFormats(service),service,false,false));
        }
        BigDecimal cheapest=raw.stream().map(Rate::amount).min(BigDecimal::compareTo).orElse(null);
        Instant fastest=raw.stream().map(rate->rate.latestDelivery()!=null?rate.latestDelivery():rate.earliestDelivery())
            .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        List<Rate> ranked=raw.stream().map(rate->new Rate(rate.serviceId(),rate.offerId(),rate.carrierName(),rate.serviceName(),
            rate.amount(),rate.currency(),rate.shipDate(),rate.earliestDelivery(),rate.latestDelivery(),rate.requiresSellerInput(),
            rate.labelFormats(),rate.payload(),cheapest!=null&&rate.amount().compareTo(cheapest)==0,fastest!=null&&fastest.equals(rate.latestDelivery()!=null?rate.latestDelivery():rate.earliestDelivery())))
            .sorted(rateOrder()).toList();
        List<String> notices=notices(payload);
        return new Quote(ranked,response.json(),response.requestId(),notices);
    }

    public Purchase purchase(UUID tenantId,UUID connectionId,String marketplaceId,JsonNode requestDetails,
            String serviceId,String offerId){
        limiter.purchase(tenantId,connectionId);
        var body=json.createObjectNode();body.set("ShipmentRequestDetails",requestDetails);
        body.put("ShippingServiceId",serviceId);if(offerId!=null&&!offerId.isBlank())body.put("ShippingServiceOfferId",offerId);
        body.put("HazmatType","None");body.putObject("LabelFormatOption").put("IncludePackingSlipWithLabel",false).put("LabelFormat","PDF");
        AmazonSpApiClient.ApiResponse response=api.postAt(tenantId,connectionId,endpoint(marketplaceId),"/mfn/v0/shipments",body.toString());
        return parsePurchase(response,serviceId);
    }

    public Purchase retrievePurchase(UUID tenantId,UUID connectionId,String marketplaceId,String shipmentId,String serviceId){
        limiter.refund(tenantId,connectionId);
        AmazonSpApiClient.ApiResponse response=api.getAt(tenantId,connectionId,endpoint(marketplaceId),"/mfn/v0/shipments/"+encodePath(shipmentId));
        return parsePurchase(response,serviceId);
    }

    private Purchase parsePurchase(AmazonSpApiClient.ApiResponse response,String fallbackServiceId){
        JsonNode shipment=payload(response.json()).path("Shipment");if(shipment.isMissingNode())shipment=payload(response.json());
        JsonNode service=shipment.path("ShippingService");
        JsonNode price=shipment.path("PurchasedPrice");
        if(!price.isObject())price=service.path("RateWithAdjustments").isObject()?service.path("RateWithAdjustments"):service.path("Rate");
        JsonNode file=shipment.path("Label").path("FileContents");
        var metadata=new PurchaseMetadata(text(shipment,"ShipmentId",null),text(shipment,"TrackingId",null),
            text(service,"ShippingServiceId",fallbackServiceId),text(service,"CarrierName",null),text(service,"ShippingServiceName",null),
            decimal(price.path("Amount"),null),text(price,"CurrencyCode",null),instant(service.path("ShipDate")),
            instant(service.path("EarliestEstimatedDeliveryDate")),instant(service.path("LatestEstimatedDeliveryDate")),
            response.json(),response.requestId());
        try{
            if(metadata.shipmentId()==null)throw new IllegalStateException("Amazon returned a label without its shipment ID.");
            if(metadata.trackingId()==null)throw new IllegalStateException("Amazon returned a shipment without its tracking number.");
            byte[] label=decodeLabel(text(file,"Contents",null),text(file,"Checksum",null),text(file,"FileType","application/pdf"));
            return new Purchase(metadata.shipmentId(),metadata.trackingId(),metadata.serviceId(),metadata.carrierName(),metadata.serviceName(),
                metadata.price(),metadata.currency(),metadata.shipDate(),metadata.earliestDelivery(),metadata.latestDelivery(),label,metadata.response(),metadata.requestId());
        }catch(RuntimeException artifact){throw new LabelArtifactException(metadata,artifact);}
    }

    public Refund refund(UUID tenantId,UUID connectionId,String marketplaceId,String shipmentId){
        limiter.refund(tenantId,connectionId);
        var response=api.deleteAt(tenantId,connectionId,endpoint(marketplaceId),"/mfn/v0/shipments/"+encodePath(shipmentId));
        return new Refund(response.json(),response.requestId());
    }

    public ShipmentStatus shipment(UUID tenantId,UUID connectionId,String marketplaceId,String shipmentId){
        limiter.refund(tenantId,connectionId);
        var response=api.getAt(tenantId,connectionId,endpoint(marketplaceId),"/mfn/v0/shipments/"+encodePath(shipmentId));
        JsonNode shipment=payload(response.json());return new ShipmentStatus(text(shipment,"Status","Unknown"),response.json(),response.requestId());
    }

    public static String shipmentState(JsonNode response){return text(payload(response),"Status","Unknown");}

    static Comparator<Rate> rateOrder(){
        return Comparator.<Rate>comparingInt(rate->rate.cheapest()?0:rate.fastest()?1:2)
            .thenComparing(Rate::amount).thenComparing(rate->rate.latestDelivery(),Comparator.nullsLast(Comparator.naturalOrder()));
    }
    static String endpoint(String marketplaceId){
        if(List.of("A1F83G8C2ARO7P","A13V1IB3VIYZZH","A1PA6795UKMFR9","APJ6JRA9NG5V4","A1RKKUPIHCS9HS","A1805IZSGTT6HS",
            "A2NODRKZP88ZB9","A1C3SOZRARQ6R3","ARBP9OOSHTCHU","A33AVAJ2PDY3EV","A17E79C6D8DWNP","A2VIGQ35RCS4UG").contains(marketplaceId))
            return "https://sellingpartnerapi-eu.amazon.com";
        if(List.of("A1VC38T7YXB528","A39IBJ37TRP1C6","A19VAU5U5O7RUS","A21TJRUUN4KGV").contains(marketplaceId))
            return "https://sellingpartnerapi-fe.amazon.com";
        return "https://sellingpartnerapi-na.amazon.com";
    }
    private static JsonNode payload(JsonNode root){return root.path("payload").isMissingNode()?root:root.path("payload");}
    private static String text(JsonNode node,String field,String fallback){String value=node.path(field).asText("");return value.isBlank()?fallback:value;}
    private static BigDecimal decimal(JsonNode node,BigDecimal fallback){try{return node.isNumber()?node.decimalValue():new BigDecimal(node.asText());}catch(Exception ignored){return fallback;}}
    private static Instant instant(JsonNode node){try{return node.isMissingNode()||node.asText().isBlank()?null:Instant.parse(node.asText());}catch(Exception ignored){return null;}}
    private static List<String> notices(JsonNode payload){
        List<String> result=new ArrayList<>();
        collect(payload.path("RejectedShippingServiceList"),"Unavailable service",result);
        collect(payload.path("UnavailableCarrierList"),"Unavailable carrier",result);
        collect(payload.path("TemporarilyUnavailableCarrierList"),"Temporarily unavailable carrier",result);
        collect(payload.path("TermsAndConditionsNotAcceptedCarrierList"),"Carrier terms need acceptance",result);
        return List.copyOf(result);
    }
    private static List<String> strings(JsonNode array){List<String> result=new ArrayList<>();if(array.isArray())for(JsonNode value:array)result.add(value.asText());return List.copyOf(result);}
    private static List<String> labelFormats(JsonNode service){
        List<String> direct=strings(service.path("AvailableLabelFormats"));if(!direct.isEmpty())return direct;
        List<String> result=new ArrayList<>();JsonNode options=service.path("AvailableFormatOptionsForLabel");
        if(options.isArray())for(JsonNode option:options){String value=option.path("LabelFormat").asText("");if(!value.isBlank())result.add(value);}
        return List.copyOf(result);
    }
    private static void collect(JsonNode array,String prefix,List<String> result){if(array.isArray())for(JsonNode item:array){String detail=item.isTextual()?item.asText():item.toString();result.add(prefix+": "+detail);}}
    private static byte[] decodeLabel(String contents,String checksum,String fileType){
        if(contents==null||contents.isBlank())throw new IllegalStateException("Amazon returned a shipment without a printable label.");
        try{
            byte[] compressed=Base64.getDecoder().decode(contents);
            byte[] decoded;
            try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(compressed));ByteArrayOutputStream out=new ByteArrayOutputStream()){
                gzip.transferTo(out);decoded=out.toByteArray();
            }
            if(checksum!=null&&!checksum.isBlank()){
                byte[] expected=Base64.getDecoder().decode(checksum);MessageDigest md5=MessageDigest.getInstance("MD5");
                boolean decodedMatches=MessageDigest.isEqual(expected,md5.digest(decoded));md5.reset();
                boolean compressedMatches=MessageDigest.isEqual(expected,md5.digest(compressed));
                if(!decodedMatches&&!compressedMatches)throw new IllegalStateException("Amazon label checksum did not match.");
            }
            if("application/pdf".equalsIgnoreCase(fileType))return decoded;
            if("image/png".equalsIgnoreCase(fileType))return pngToPdf(decoded);
            throw new IllegalStateException("Amazon returned "+fileType+" even though a PDF label was requested. Download it from Seller Central.");
        }catch(IllegalStateException ex){throw ex;}
        catch(Exception ex){throw new IllegalStateException("Amazon returned a label that could not be opened.",ex);}
    }
    private static byte[] pngToPdf(byte[] png)throws Exception{
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(png));if(image==null)throw new IllegalStateException("Amazon returned an invalid PNG label.");
        try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            PDPage page=new PDPage(new PDRectangle(288,432));document.addPage(page);var picture=LosslessFactory.createFromImage(document,image);
            float scale=Math.min(288f/picture.getWidth(),432f/picture.getHeight());float width=picture.getWidth()*scale,height=picture.getHeight()*scale;
            try(PDPageContentStream canvas=new PDPageContentStream(document,page)){canvas.drawImage(picture,(288-width)/2,(432-height)/2,width,height);}
            document.save(out);return out.toByteArray();
        }
    }
    private static String encodePath(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}
    public static class LabelArtifactException extends RuntimeException{
        private final PurchaseMetadata metadata;
        public LabelArtifactException(PurchaseMetadata metadata,Throwable cause){super(cause.getMessage(),cause);this.metadata=metadata;}
        public PurchaseMetadata metadata(){return metadata;}
    }
}
