package com.nextaicommerce.platform.marketplace;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Typed local snapshot export; uses shared CSV escaping and formula protection, never a live provider read. */
@Service
public class ShopifySkuCsvExport {
    private final ShopifySkuRepository listings;private final ObjectMapper json=new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyOrderJobs orderJobs;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyOrderReadRepository orderReads;
    public ShopifySkuCsvExport(ShopifySkuRepository listings){this.listings=listings;}
    static final Set<String> GROUPS=Set.of("product-details","status","price-shipping","available","4w-sales","profit");
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=60)
    public MarketplaceSkuCsvExport.Artifact generate(UUID tenant,UUID connection,Map<String,String> params){
        if(params.size()>75||params.values().stream().anyMatch(s->s.length()>16000)||params.entrySet().stream().anyMatch(e->e.getKey().startsWith("f_")&&!e.getValue().isBlank()))throw new IllegalArgumentException("Unsupported Shopify export filters.");
        List<String> columns=Arrays.asList(params.getOrDefault("columns","product-details,status,price-shipping").split(","));
        if(!columns.contains("product-details")||columns.size()>6||!GROUPS.containsAll(columns)||new HashSet<>(columns).size()!=columns.size())throw new IllegalArgumentException("Invalid Shopify CSV columns.");
        Map<String,Object> filters=new LinkedHashMap<>();var filterJson=json.readTree(params.getOrDefault("columnFilters","{}"));
        if(!filterJson.isObject())throw new IllegalArgumentException("Invalid column filters.");
        for(var field:filterJson.properties()){
            if(!GROUPS.contains(field.getKey()))throw new IllegalArgumentException("Unsupported column filter.");
            var value=field.getValue();if(value.isString())filters.put(field.getKey(),value.asText());else if(value.isObject()){
                var bounds=new LinkedHashMap<String,String>();for(var b:value.properties()){if(!Set.of("type","min","max").contains(b.getKey())||!b.getValue().isString())throw new IllegalArgumentException("Invalid numeric filter.");bounds.put(b.getKey(),b.getValue().asText());}
                if(!Set.of("number","date").contains(bounds.getOrDefault("type","")))throw new IllegalArgumentException("Invalid filter type.");filters.put(field.getKey(),bounds);
            }else throw new IllegalArgumentException("Invalid column filter.");
        }
        var output=new ArrayList<LinkedHashMap<String,Object>>();Set<UUID> identities=new HashSet<>();Long expected=null;long deadline=System.nanoTime()+60_000_000_000L;
        for(int page=0;;page++){
            MarketplaceSkuCsvExport.check(deadline);var batch=listings.list(tenant,connection,params.getOrDefault("q",""),params.getOrDefault("status","ALL"),params.getOrDefault("sort","listing"),params.getOrDefault("direction","asc"),page,100);
            if(batch.total()>10000||expected!=null&&batch.total()!=expected)throw new IllegalArgumentException("Shopify export is too large or inconsistent.");expected=batch.total();
            var components=listings.componentBatch(tenant,connection,batch.rows().stream().map(ShopifySkuRepository.Row::id).toList());
            var weekly=orderJobs!=null&&orderJobs.enabled()&&orderReads!=null&&(columns.contains("4w-sales")||filters.containsKey("4w-sales"))?orderReads.weekly(tenant,connection,batch.rows().stream().map(ShopifySkuRepository.Row::id).toList()):Map.<UUID,String>of();
            for(var row:batch.rows()){
                if(!identities.add(row.id()))throw new IllegalArgumentException("Repeated Shopify variant. No partial download was produced.");
                var parts=components.getOrDefault(row.id(),List.of());var text=new HashMap<>(filterText(row,parts));if(weekly.containsKey(row.id()))text.put("4w-sales",weekly.get(row.id()));if(!filters.entrySet().stream().allMatch(e->MarketplaceSkuCsvExport.matches(text.getOrDefault(e.getKey(),""),e.getValue())))continue;
                var projected=project(row,parts,columns);if(columns.contains("4w-sales")&&weekly.containsKey(row.id()))projected.put("Four-week sales",Arrays.stream(weekly.get(row.id()).split("\\s*\\|\\s*")).mapToLong(Long::parseLong).sum());
                output.add(projected);
            }
            if(!batch.hasNext()){if(identities.size()!=expected)throw new IllegalArgumentException("Incomplete Shopify export.");break;}
        }
        var header=new LinkedHashSet<String>();for(String group:columns){header.addAll(project(null,List.of(),List.of(group)).keySet());if(group.equals("product-details"))for(var row:output)row.keySet().stream().filter(key->key.startsWith("Mapped item ")).forEach(header::add);}var bytes=new ByteArrayOutputStream();bytes.writeBytes(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});MarketplaceSkuCsvExport.writeRecord(bytes,new ArrayList<>(header));for(var row:output)MarketplaceSkuCsvExport.writeRecord(bytes,header.stream().map(row::get).toList());
        return new MarketplaceSkuCsvExport.Artifact(bytes.toByteArray(),output.size(),Instant.now());
    }
    static Map<String,String> filterText(ShopifySkuRepository.Row r,List<MarketplaceSkuRepository.MappingComponentView> parts){return Map.of("product-details",r.title()+" "+r.skuLabel()+" "+r.optionsLabel()+" "+parts.stream().map(p->Objects.toString(p.accountSku(),"")+" "+Objects.toString(p.vendorItemCode(),"")).reduce("",(a,b)->a+" "+b),"status",r.productStatus(),"price-shipping",r.price().toPlainString(),"available","Not imported","4w-sales","Not imported","profit","Not imported");}
    static LinkedHashMap<String,Object> project(ShopifySkuRepository.Row r,List<MarketplaceSkuRepository.MappingComponentView> parts,List<String> columns){
        var result=new LinkedHashMap<String,Object>();var json=new ObjectMapper();
        for(String group:columns)switch(group){
            case "product-details"->{
                result.put("Seller SKU",r==null?null:r.sku());result.put("Product title",r==null?null:r.title());result.put("Variant options",r==null?null:r.optionsLabel());result.put("Mapping status",r==null?null:r.mappingLabel());
                for(int i=0;i<parts.size();i++){var p=parts.get(i);String prefix="Mapped item "+(i+1);result.put(prefix+" code",p.vendorItemCode()!=null?p.vendorItemCode():p.accountSku()!=null?p.accountSku():p.productName());result.put(prefix+" quantity per listing unit",p.quantity());}
            }
            case "status"->result.put("Product status",r==null?null:r.productStatus());
            case "price-shipping"->{result.put("Listing price",r==null?null:r.price());result.put("Currency",r==null?null:r.currency());}
            case "available"->result.put("Available",null);
            case "4w-sales"->result.put("Four-week sales",null);
            case "profit"->result.put("Estimated profit",null);
            default->throw new IllegalArgumentException("Unsupported export column");
        }
        return result;
    }
}
