package com.nextaicommerce.platform.marketplace;

/** Amazon listing-condition codes, not inferred from SKU text or ASIN. */
public final class SkuCondition {
    private SkuCondition(){}
    public static String key(String value){
        return switch(label(value)){
            case "New"->"NEW";case "Used · Like new"->"USED_LIKE_NEW";case "Used · Very good"->"USED_VERY_GOOD";
            case "Used · Good"->"USED_GOOD";case "Used · Acceptable"->"USED_ACCEPTABLE";
            case "Collectible · Like new"->"COLLECTIBLE_LIKE_NEW";case "Collectible · Very good"->"COLLECTIBLE_VERY_GOOD";
            case "Collectible · Good"->"COLLECTIBLE_GOOD";case "Collectible · Acceptable"->"COLLECTIBLE_ACCEPTABLE";
            case "Refurbished"->"REFURBISHED";default->"UNKNOWN";
        };
    }
    public static final java.util.Map<String,String> OPTIONS=java.util.Map.ofEntries(
        java.util.Map.entry("NEW","New"),java.util.Map.entry("USED_LIKE_NEW","Used · Like new"),java.util.Map.entry("USED_VERY_GOOD","Used · Very good"),
        java.util.Map.entry("USED_GOOD","Used · Good"),java.util.Map.entry("USED_ACCEPTABLE","Used · Acceptable"),
        java.util.Map.entry("COLLECTIBLE_LIKE_NEW","Collectible · Like new"),java.util.Map.entry("COLLECTIBLE_VERY_GOOD","Collectible · Very good"),
        java.util.Map.entry("COLLECTIBLE_GOOD","Collectible · Good"),java.util.Map.entry("COLLECTIBLE_ACCEPTABLE","Collectible · Acceptable"),
        java.util.Map.entry("REFURBISHED","Refurbished"),java.util.Map.entry("UNKNOWN","Unknown / other"));
    public static String label(String value){
        if(value==null||value.isBlank())return "Condition pending";
        return switch(value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","")){
            case "11","new","newnew" -> "New";
            case "1","usedlikenew" -> "Used · Like new";
            case "2","usedverygood" -> "Used · Very good";
            case "3","usedgood" -> "Used · Good";
            case "4","usedacceptable" -> "Used · Acceptable";
            case "5","collectiblelikenew" -> "Collectible · Like new";
            case "6","collectibleverygood" -> "Collectible · Very good";
            case "7","collectiblegood" -> "Collectible · Good";
            case "8","collectibleacceptable" -> "Collectible · Acceptable";
            case "10","refurbished","refurbishedrefurbished" -> "Refurbished";
            default -> value;
        };
    }
}
