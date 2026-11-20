package com.nextaicommerce.platform.shipping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validated domestic parcel contract. International/customs and dangerous goods are not inferred. */
public final class FedExPayloads {
    private FedExPayloads() {}
    public record Address(String name,String phone,String street,String city,String state,String postalCode,String country,boolean residential,String street2,String street3) {
        public Address(String name,String phone,String street,String city,String state,String postalCode,String country,boolean residential){this(name,phone,street,city,state,postalCode,country,residential,null,null);}
        @Override public String toString(){return "Shipping address [protected]";}
    }
    public record Parcel(BigDecimal length,BigDecimal width,BigDecimal height,BigDecimal weight,String packagingId) {
        public Parcel(BigDecimal length,BigDecimal width,BigDecimal height,BigDecimal weight){this(length,width,height,weight,"YOUR_PACKAGING");}
    }
    public record Rate(String serviceType,String serviceName,BigDecimal amount,String currency) {}
    public record Label(String trackingNumber,byte[] pdf) {
        @Override public String toString(){return "FedEx label [protected]";}
    }
    public static ObjectNode rateRequest(ObjectMapper json,String account,Address from,Address to,Parcel parcel) {
        parcel=FedExPackaging.normalize(parcel);validate(from);validate(to);validate(parcel);
        if(!"US".equals(from.country())||!"US".equals(to.country()))throw new IllegalArgumentException("This first FedEx release supports US domestic parcels only.");
        ObjectNode root=json.createObjectNode();root.putObject("accountNumber").put("value",account);
        ObjectNode shipment=root.putObject("requestedShipment");shipment.set("shipper",party(json,from));shipment.set("recipient",party(json,to));
        shipment.put("pickupType","DROPOFF_AT_FEDEX_LOCATION").put("packagingType",FedExPackaging.option(parcel.packagingId()).apiType()).put("shipDateStamp",LocalDate.now(java.time.ZoneOffset.UTC).toString());
        shipment.putArray("rateRequestType").add("ACCOUNT");
        var box=shipment.putArray("requestedPackageLineItems").addObject();
        box.put("groupPackageCount",1);box.putObject("weight").put("units","LB").put("value",parcel.weight());
        box.putObject("dimensions").put("units","IN").put("length",parcel.length().setScale(0,RoundingMode.CEILING).intValueExact())
            .put("width",parcel.width().setScale(0,RoundingMode.CEILING).intValueExact()).put("height",parcel.height().setScale(0,RoundingMode.CEILING).intValueExact());
        return root;
    }
    public static ObjectNode shipRequest(ObjectNode rated,String service) {
        ObjectNode root=rated.deepCopy(),shipment=(ObjectNode)root.path("requestedShipment");
        JsonNode recipient=shipment.remove("recipient");shipment.putArray("recipients").add(recipient);
        shipment.put("serviceType",service);
        shipment.putObject("shippingChargesPayment").put("paymentType","SENDER");
        shipment.putObject("labelSpecification").put("imageType","PDF").put("labelStockType","PAPER_4X6");
        root.put("labelResponseOptions","LABEL");
        return root;
    }
    public static List<Rate> rates(JsonNode response) {
        List<Rate> rates=new ArrayList<>();
        for(JsonNode detail:response.path("output").path("rateReplyDetails")) {
            String service=detail.path("serviceType").asText("");
            if(!service.matches("[A-Z0-9_]{1,80}"))continue;
            for(JsonNode rated:detail.path("ratedShipmentDetails")) {
                String rateType=rated.path("rateType").asText("");
                if(!rateType.contains("ACCOUNT"))continue;
                JsonNode amount=rated.path("totalNetCharge");String currency=rated.path("currency").asText("");
                if(amount.isNumber()&&amount.decimalValue().signum()>=0&&currency.matches("[A-Z]{3}")) {
                    rates.add(new Rate(service,detail.path("serviceName").asText(service),amount.decimalValue(),currency));break;
                }
            }
        }
        rates.sort(Comparator.comparing(Rate::currency).thenComparing(Rate::amount));return List.copyOf(rates);
    }
    public static Label label(JsonNode response) {
        JsonNode shipments=response.path("output").path("transactionShipments");
        if(shipments.size()!=1)throw new FedExClient.CarrierException("FedEx shipment response needs manual review. Do not buy another label.",true);
        JsonNode pieces=shipments.get(0).path("pieceResponses");
        if(pieces.size()!=1)throw new FedExClient.CarrierException("FedEx package response needs manual review. Do not buy another label.",true);
        String tracking=pieces.get(0).path("trackingNumber").asText("");
        if(!tracking.matches("[0-9]{8,30}"))throw new FedExClient.CarrierException("FedEx tracking response needs manual review.",true);
        for(JsonNode doc:pieces.get(0).path("packageDocuments"))if("LABEL".equals(doc.path("contentType").asText())&&"PDF".equals(doc.path("docType").asText())) {
            try {
                byte[] bytes=java.util.Base64.getDecoder().decode(doc.path("encodedLabel").asText());
                if(bytes.length<5||bytes.length>5_000_000||!new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException();
                return new Label(tracking,bytes);
            }catch(IllegalArgumentException ex){throw new FedExClient.CarrierException("FedEx label could not be verified. Do not buy another label.",true);}
        }
        // Never fetch arbitrary URLs returned in a carrier response (SSRF / PII risk).
        throw new FedExClient.CarrierException("FedEx did not return an embedded PDF label. Review the shipment before retrying.",true);
    }
    private static ObjectNode party(ObjectMapper json,Address address){var node=json.createObjectNode();node.putObject("contact").put("personName",address.name()).put("phoneNumber",address.phone());var a=node.putObject("address");var streets=a.putArray("streetLines").add(address.street());for(String line:new String[]{address.street2(),address.street3()})if(line!=null&&!line.isBlank()){if(line.length()>100||line.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Check additional street address lines.");streets.add(line);}a.put("city",address.city()).put("stateOrProvinceCode",address.state()).put("postalCode",address.postalCode()).put("countryCode",address.country()).put("residential",address.residential());return node;}
    static void validate(Address a){if(a==null)throw new IllegalArgumentException("Enter sender and recipient details.");for(String value:List.of(safe(a.name()),safe(a.phone()),safe(a.street()),safe(a.city()),safe(a.state()),safe(a.postalCode()),safe(a.country())))if(value.isBlank()||value.length()>100||!value.matches("[\\x20-\\x7E]+"))throw new IllegalArgumentException("Complete all address fields using standard Latin characters (100 characters maximum).");if(!a.state().matches("[A-Z]{2}")||!a.postalCode().matches("[0-9]{5}(-[0-9]{4})?")||!a.phone().matches("[+0-9 ()-]{10,20}"))throw new IllegalArgumentException("Check the US state, ZIP code and phone number.");}
    public static void validate(Parcel p){if(p==null)throw new IllegalArgumentException("Enter package dimensions and weight.");for(BigDecimal value:new BigDecimal[]{p.length(),p.width(),p.height(),p.weight()})if(value==null||value.signum()<=0||value.compareTo(new BigDecimal("150"))>0)throw new IllegalArgumentException("Package dimensions and weight must be greater than zero and at most 150 inches/lb.");}
    private static String safe(String value){return value==null?"":value;}
}
