package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.catalog.CatalogImportService.ParsedSheet;
import java.util.*;

/** Explicit upload-column translation; never infers product identity or quantity units. */
final class ReceivingColumnMapping {
    record Selection(boolean headings,String quantityUnit,Map<String,String> fields) {}
    private static final Set<String> FIELDS=Set.of("AccountSKU","VendorItemCode","Description","Quantity","UPC","UnitsPerCase","UnitCost");
    static void requireHeadings(ParsedSheet sheet){
        if(sheet.headers().isEmpty()||sheet.headers().stream().anyMatch(h->h.matches("[\\d\\s.,+-]+")||h.matches("Column \\d+(?: \\(\\d+\\))?")))
            throw new IllegalArgumentException("Column headings are required. Add a heading for every uploaded column. Nothing was saved.");
    }
    static boolean hasRequiredHeaders(ParsedSheet sheet){
        Set<String> headers=new HashSet<>();
        sheet.headers().forEach(h->headers.add(h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","")));
        boolean recognized=!Collections.disjoint(headers,Set.of("description","productname","itemdescription","title","product"))
            && !Collections.disjoint(headers,Set.of("shipquantity","shippedquantity","quantity","qty","orderedquantity","orderqty","shippedqty"))
            && !Collections.disjoint(headers,Set.of("accountsku","vendoritemcode","vendorsku","itemnumber","itemno","sku","shipitem","upc","ean","gtin","barcode","upcean"));
        if(!recognized)return false;
        // A recognized heading is insufficient when product rows have no description under it.
        for(var row:sheet.rows()){
            Map<String,String> normalized=new HashMap<>();row.forEach((key,value)->normalized.put(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""),value));
            boolean quantity=List.of("shipquantity","shippedquantity","quantity","qty","orderedquantity","orderqty","shippedqty").stream().anyMatch(key->!Objects.toString(normalized.get(key),"").isBlank());
            boolean description=List.of("description","productname","itemdescription","title","product").stream().anyMatch(key->!Objects.toString(normalized.get(key),"").isBlank());
            if(quantity&&!description)return false;
        }
        return true;
    }
    static List<Map<String,String>> apply(ParsedSheet sheet,Selection choice){
        if(!Set.of("EACH","CASE").contains(Objects.toString(choice.quantityUnit(),"")))throw new IllegalArgumentException("Choose whether quantities are individual units or cases.");
        Map<String,String> fields=choice.fields()==null?Map.of():choice.fields();
        if(!fields.keySet().stream().allMatch(FIELDS::contains))throw new IllegalArgumentException("Unknown receiving field.");
        for(String required:List.of("Description","Quantity"))if(!mapped(fields,required))throw new IllegalArgumentException("Match the "+required+" column.");
        if(List.of("AccountSKU","VendorItemCode","UPC").stream().noneMatch(f->mapped(fields,f)))throw new IllegalArgumentException("Match Account SKU, vendor item code or UPC / EAN.");
        Set<Integer> used=new HashSet<>();Map<String,Integer> columns=new LinkedHashMap<>();
        fields.forEach((field,value)->{if(value==null||value.isBlank())return;int col;try{col=Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Choose an uploaded column for "+field+".");}if(col<0||col>=sheet.headers().size()||!used.add(col))throw new IllegalArgumentException("Each selected column must be valid and matched only once.");columns.put(field,col);});
        List<Map<String,String>> result=new ArrayList<>();
        for(Map<String,String> original:sheet.rows()){
            Map<String,String> row=new LinkedHashMap<>();
            columns.forEach((field,col)->row.put(field.equals("Quantity")&&choice.quantityUnit().equals("EACH")?"ShipQuantity":field,original.getOrDefault(sheet.headers().get(col),"")));
            // Keep the entire original source row alongside canonical fields for the receiving audit.
            row.put("_originalColumns",new tools.jackson.databind.ObjectMapper().writeValueAsString(original));result.add(row);
        }
        return result;
    }
    static String schema(ParsedSheet sheet,Selection choice){
        String headers=choice!=null?"columns:"+sheet.headers().size():
            sheet.headers().stream().map(h->h.trim().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining("|"));
        String mapping=choice==null?"automatic":new TreeMap<>(choice.fields()).toString()+":"+choice.quantityUnit();
        return headers+":"+mapping;
    }
    private static boolean mapped(Map<String,String> values,String key){return values.get(key)!=null&&!values.get(key).isBlank();}
}
