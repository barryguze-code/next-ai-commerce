package com.nextaicommerce.platform.marketplace;

import java.math.BigDecimal;
import java.util.*;

/** Pure existing-policy matching. Raw provider identity is never normalized or used as a database key. */
public final class ShopifySkuMatcher {
    private ShopifySkuMatcher(){}
    public record Item(UUID id,String title,Set<String> codes,Set<String> gtins){}
    public record Match(Map<UUID,BigDecimal> components,String state){}
    public static Match match(String sku,String title,List<String> barcodes,boolean complete,boolean duplicate,List<Item> catalogue){
        if(sku==null||sku.isBlank()||duplicate||!complete)return unresolved("Identity or barcode evidence needs review");
        if(sku.matches("(?is).*[-_]EA[xX]0+(?:$|[-_].*)"))return unresolved("Invalid zero pack quantity");
        MarketplaceSkuAutoMapper.ParsedSku parsed;
        try{parsed=MarketplaceSkuAutoMapper.parse(sku);}catch(IllegalArgumentException e){return unresolved("Unrecognized pack quantity");}
        if(parsed.quantityPerMarketplaceUnit()>10000||!parsed.validBundleTotal())return unresolved("Pack quantity needs review");
        var parts=new LinkedHashMap<UUID,BigDecimal>();
        if(!parsed.bundleComponents().isEmpty()){
            for(var component:parsed.bundleComponents()){
                var matches=codeMatches(Set.of(component.itemCode()),catalogue);if(matches.size()!=1)return unresolved("Bundle component needs review");
                parts.merge(matches.iterator().next(),BigDecimal.valueOf(component.quantity()),BigDecimal::add);
            }
            if(parts.size()>1)return new Match(Map.copyOf(parts),"Unique account bundle codes");
        }
        Set<UUID> codes=codeMatches(new HashSet<>(parsed.itemCodeTokens()),catalogue);Set<UUID> barcode=new HashSet<>();
        for(String raw:barcodes){String gtin=BarcodePackRules.gtin(raw);if(gtin!=null)for(var item:catalogue)if(item.gtins().contains(gtin))barcode.add(item.id());}
        if(codes.size()>1||barcode.size()>1||!codes.isEmpty()&&!barcode.isEmpty()&&!codes.equals(barcode))return unresolved("Conflicting account identifiers");
        if(codes.size()==1)return new Match(Map.of(codes.iterator().next(),BigDecimal.valueOf(parsed.quantityPerMarketplaceUnit())),"Unique account item code");
        if(barcode.size()!=1||parsed.explicitBundle())return unresolved("No unique account match");
        UUID id=barcode.iterator().next();var item=catalogue.stream().filter(i->i.id().equals(id)).findFirst().orElseThrow();
        Integer sourcePack=BarcodePackRules.quantity(title),itemPack=BarcodePackRules.quantity(item.title());
        if(sourcePack==null||!Integer.valueOf(1).equals(itemPack)||(parsed.quantityPerMarketplaceUnit()>1&&sourcePack>1&&parsed.quantityPerMarketplaceUnit()!=sourcePack))return unresolved("Barcode pack ratio needs review");
        return new Match(Map.of(id,BigDecimal.valueOf(Math.max(sourcePack,parsed.quantityPerMarketplaceUnit()))),"Unique validated GTIN");
    }
    /** Only identity and matcher inputs: price/image refreshes never invalidate a saved mapping basis. */
    public static String fingerprint(com.nextaicommerce.platform.shopify.ShopifyCatalogClient.Variant v){
        var json=new tools.jackson.databind.ObjectMapper();var fields=new LinkedHashMap<String,Object>();
        fields.put("variant",v.gid());fields.put("product",v.product().gid());fields.put("inventory",v.inventoryId());fields.put("sku",v.sku());fields.put("productTitle",v.product().title());fields.put("variantTitle",v.title());
        fields.put("options",canonicalArray(v.options()));fields.put("barcodes",canonicalArray(v.barcodes()));fields.put("complete",v.complete());
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static List<String> canonicalArray(String text){var values=new ArrayList<String>();for(var n:new tools.jackson.databind.ObjectMapper().readTree(text)){var fields=new TreeMap<String,String>();for(var f:n.properties())fields.put(f.getKey(),f.getValue().isNull()?null:f.getValue().asText());values.add(new tools.jackson.databind.ObjectMapper().writeValueAsString(fields));}return values.stream().sorted().toList();}
    private static Set<UUID> codeMatches(Set<String> tokens,List<Item> items){var result=new HashSet<UUID>();for(var i:items)for(String code:i.codes())if(tokens.contains(MarketplaceSkuAutoMapper.normalizeCode(code)))result.add(i.id());return result;}
    private static Match unresolved(String state){return new Match(Map.of(),state);}
}
