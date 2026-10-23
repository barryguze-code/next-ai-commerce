package com.nextaicommerce.platform.shipping;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** Physical variants are distinct from FedEx's packaging enum families. Never trust browser dimensions for stock boxes. */
public final class FedExPackaging {
    private FedExPackaging() {}
    public record Option(String id,String label,String apiType,BigDecimal length,BigDecimal width,BigDecimal height) {}
    private static Option box(String id,String label,String type,String l,String w,String h){return new Option(id,label,type,new BigDecimal(l),new BigDecimal(w),new BigDecimal(h));}
    public static final List<Option> OPTIONS=List.of(
        new Option("YOUR_PACKAGING","Your Packaging (custom dimensions)","YOUR_PACKAGING",null,null,null),
        box("ENVELOPE_STANDARD","FedEx Envelope Standard","FEDEX_ENVELOPE","12.5","9.5","0.5"),
        box("ENVELOPE_PADDED","FedEx Envelope Padded","FEDEX_ENVELOPE","11.5","9.75","0.5"),
        box("ENVELOPE_LARGE","FedEx Envelope Large","FEDEX_ENVELOPE","15.5","9.5","0.5"),
        box("PAK_PADDED","FedEx Pak Padded","FEDEX_PAK","14.75","11.75","0.5"),
        box("PAK_LARGE","FedEx Pak Large","FEDEX_PAK","15.5","12","0.5"),
        box("PAK_SMALL","FedEx Pak Small","FEDEX_PAK","12.75","10.25","0.5"),
        box("TUBE","FedEx Tube","FEDEX_TUBE","38","6","6"),
        box("SMALL_S1","FedEx Small Box S1","FEDEX_SMALL_BOX","12.375","10.875","1.5"),
        box("SMALL_S2","FedEx Small Box S2 (deep)","FEDEX_SMALL_BOX","11.25","8.75","2.625"),
        box("MEDIUM_M1","FedEx Medium Box M1","FEDEX_MEDIUM_BOX","13.25","11.5","2.375"),
        box("MEDIUM_M2","FedEx Medium Box M2 (deep)","FEDEX_MEDIUM_BOX","11.25","8.75","4.375"),
        box("LARGE_L1","FedEx Large Box L1","FEDEX_LARGE_BOX","17.5","12.375","3"),
        box("LARGE_L2","FedEx Large Box L2 (deep)","FEDEX_LARGE_BOX","11.25","8.75","7.75"),
        box("XLARGE_X1","FedEx Extra Large Box X1 (deep)","FEDEX_EXTRA_LARGE_BOX","11","11.875","10.75"),
        box("XLARGE_X2","FedEx Extra Large Box X2","FEDEX_EXTRA_LARGE_BOX","6","15.75","14.125"),
        box("XSMALL","FedEx Extra Small Box","FEDEX_EXTRA_SMALL_BOX","7.25","4.6875","2.5")
    );
    public static Option option(String id){return OPTIONS.stream().filter(o->o.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Choose a supported FedEx packaging option."));}
    public static FedExPayloads.Parcel normalize(FedExPayloads.Parcel p){
        if(p==null)throw new IllegalArgumentException("Choose packaging and enter weight.");
        String id=p.packagingId()==null?"YOUR_PACKAGING":p.packagingId();
        var o=option(id);
        return new FedExPayloads.Parcel(o.length()==null?p.length():o.length(),o.width()==null?p.width():o.width(),o.height()==null?p.height():o.height(),p.weight(),id);
    }
    public static FedExPayloads.Parcel profile(BuyShippingRepository.PackageProfile profile){
        if(profile==null)return null;
        BigDecimal divisor="centimeters".equals(profile.dimensionUnit())?new BigDecimal("2.54"):BigDecimal.ONE;
        if(!List.of("inches","centimeters").contains(profile.dimensionUnit())||!List.of("oz","g").contains(profile.weightUnit()))return null;
        if(profile.length()==null||profile.width()==null||profile.height()==null||profile.weight()==null)return null;
        var l=profile.length().divide(divisor,6,java.math.RoundingMode.HALF_UP);var w=profile.width().divide(divisor,6,java.math.RoundingMode.HALF_UP);var h=profile.height().divide(divisor,6,java.math.RoundingMode.HALF_UP);
        var weight=profile.weight().divide(new BigDecimal("oz".equals(profile.weightUnit())?"16":"453.59237"),6,java.math.RoundingMode.HALF_UP);
        String id=legacy(profile.name());
        if(id==null)id=OPTIONS.stream().filter(o->o.length()!=null&&close(o.length(),l)&&close(o.width(),w)&&close(o.height(),h)).map(Option::id).findFirst().orElse("YOUR_PACKAGING");
        var result=normalize(new FedExPayloads.Parcel(l,w,h,weight,id));
        try{FedExPayloads.validate(result);return result;}catch(IllegalArgumentException ex){return null;}
    }
    private static boolean close(BigDecimal a,BigDecimal b){return a.subtract(b).abs().compareTo(new BigDecimal("0.01"))<=0;}
    /** Operator-approved legacy defaults: deep boxes and padded Pak. Explicit variants stay unchanged. */
    public static String legacy(String name){
        if(name==null)return null;
        if(name.contains("+"))return null; // Mixed packages must not collapse into a single Pak.
        if(OPTIONS.stream().anyMatch(o->o.id().equals(name)))return name;
        return switch(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]","").replace("FEDEX","").replace("BOX","")){
            case "PAK","PADDEDPAK","PAKPADDED"->"PAK_PADDED";
            case "XSMALL","EXTRASMALL"->"XSMALL";case "SMALL"->"SMALL_S2";case "MEDIUM"->"MEDIUM_M2";
            case "LARGE"->"LARGE_L2";case "XLARGE","EXTRALARGE"->"XLARGE_X1";default->null;
        };
    }
}
