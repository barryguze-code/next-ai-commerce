package com.nextaicommerce.platform.catalog;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared identity rules for bulk import, manual catalogue and receiving. */
final class CatalogIdentity {
    private CatalogIdentity() {}
    static String distributionCenter(String value){
        String dc=value==null?"":value.trim().toUpperCase(Locale.ROOT).replaceFirst("^DC[\\s:#-]*","").trim();
        if(dc.isBlank())return "";
        if(!dc.matches("[A-Z0-9][A-Z0-9 -]{0,39}"))throw new IllegalArgumentException("Enter a DC / branch code of up to 40 letters or numbers.");
        return dc.matches("\\d+")?dc.replaceFirst("^0+(?!$)",""):dc;
    }
    static String scope(UUID tenant,String dc){return dc==null||dc.isBlank()?"ACCOUNT:"+tenant:"DC:"+dc;}
    static String vendorCode(String value){
        String code=value==null?"":value.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
        return code.matches("\\d+")?code.replaceFirst("^0+(?!$)",""):code;
    }
    record VendorBinding(java.util.Set<UUID> items,java.util.Set<String> identifiers){}
    /** Account-owned supplier bindings, including legacy ambiguity; never global supplier-code identity. */
    static java.util.Map<String,VendorBinding> vendorBindings(JdbcTemplate jdbc,UUID tenant,UUID vendor,String onlyCode){
        var result=new java.util.HashMap<String,VendorBinding>();
        jdbc.query("""
            SELECT o.vendor_item_code,i.id,b.identity_key FROM vendor_catalog_offers o
            JOIN account_catalog_items i ON i.tenant_id=o.tenant_id AND i.id=o.account_catalog_item_id
            LEFT JOIN global_product_identifiers b ON b.global_product_id=i.global_product_id
            WHERE o.tenant_id=? AND o.vendor_id=? AND o.effective_to IS NULL
              AND nullif(trim(o.vendor_item_code),'') IS NOT NULL
              AND (?::text IS NULL OR CASE WHEN regexp_replace(upper(o.vendor_item_code),'[^A-Z0-9]','','g') ~ '^[0-9]+$'
                THEN regexp_replace(regexp_replace(upper(o.vendor_item_code),'[^A-Z0-9]','','g'),'^0+(?!$)','')
                ELSE regexp_replace(upper(o.vendor_item_code),'[^A-Z0-9]','','g') END=?)
            """,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                var binding=result.computeIfAbsent(vendorCode(rs.getString(1)),k->new VendorBinding(new java.util.HashSet<>(),new java.util.HashSet<>()));
                binding.items().add(rs.getObject(2,UUID.class));String key=rs.getString(3);if(key!=null)binding.identifiers().add(key);
            },tenant,vendor,onlyCode,onlyCode);
        return result;
    }
    static String bindingProblem(java.util.Map<String,VendorBinding> bindings,String code,String type,String identifier){
        var existing=bindings.get(vendorCode(code));if(existing==null)return null;
        if(existing.items().size()>1)return "Vendor item code "+code+" is linked to multiple products in this account/vendor. Review the existing conflict; no products or stock will be reassigned.";
        String value=identifier==null?"":identifier.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
        String kind=type==null?"UPC":type.toUpperCase(Locale.ROOT);
        String key=java.util.Set.of("UPC","EAN","GTIN").contains(kind)&&value.matches("(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})")
            ?"GTIN:"+"0".repeat(14-value.length())+value:kind+":"+value;
        if(!existing.identifiers().contains(key))return "Vendor item code "+code+" is already linked to a different product or an unverified barcode in this account/vendor. Review the source barcode; no products or stock will be reassigned.";
        return null;
    }
    static void requireVendorItem(JdbcTemplate jdbc,UUID tenant,UUID vendor,String code,UUID item){
        if(vendorCode(code).isBlank())return;
        var binding=vendorBindings(jdbc,tenant,vendor,vendorCode(code)).get(vendorCode(code));
        if(binding!=null&&binding.items().stream().anyMatch(id->!id.equals(item)))
            throw new IllegalArgumentException("Vendor item code "+code+" is already linked to a different product in this account/vendor. Review the existing conflict; no products or stock were reassigned.");
    }
    static void lock(JdbcTemplate jdbc){
        // Catalogue writes only. Orders, stock reservations and publishing do not acquire this lock.
        jdbc.execute("SELECT pg_advisory_xact_lock(193641,1)");
    }
}
