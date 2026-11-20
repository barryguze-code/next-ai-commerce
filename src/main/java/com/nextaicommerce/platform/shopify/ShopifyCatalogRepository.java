package com.nextaicommerce.platform.shopify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Called only inside a fenced job transaction. No inventory or ledger writer. */
@Repository
public class ShopifyCatalogRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json=new ObjectMapper();
    public ShopifyCatalogRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    static Timestamp time(Instant t){return Timestamp.from(t);}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public UUID archive(UUID tenant,UUID connection,UUID job,String cursor,String body){
        UUID id=UUID.randomUUID();String digest=hash(body);
        jdbc.update("""
            INSERT INTO shopify_source_documents(id,tenant_id,marketplace_connection_id,sync_job_id,api_version,query_revision,request_cursor,sha256,response_body)
            VALUES(?,?,?,?,'2026-10',?,?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id,sync_job_id,sha256) DO NOTHING
            """,id,tenant,connection,job,ShopifyCatalogClient.REVISION,cursor,digest,body);
        return jdbc.queryForObject("SELECT id FROM shopify_source_documents WHERE tenant_id=? AND marketplace_connection_id=? AND sync_job_id=? AND sha256=?",UUID.class,tenant,connection,job,digest);
    }
    public void publish(UUID tenant,UUID connection,UUID job,UUID run,String cursor,String body,ShopifyCatalogClient.Page page,String currency){
        var products=new LinkedHashMap<String,ShopifyCatalogClient.Product>();
        for(var variant:page.variants()){
            var old=products.putIfAbsent(variant.product().gid(),variant.product());
            if(old!=null&&old.updated().equals(variant.product().updated())&&!old.equals(variant.product()))throw new IllegalArgumentException("Conflicting product observations in one Shopify page.");
            if(old!=null&&old.updated().isBefore(variant.product().updated()))products.put(variant.product().gid(),variant.product());
        }
        for(var product:products.values())checkRevision("shopify_products","product_gid",tenant,connection,product.gid(),product.updated(),hash(json.writeValueAsString(product)));
        for(var variant:page.variants())checkRevision("shopify_variants","variant_gid",tenant,connection,variant.gid(),variant.updated(),variantHash(variant));
        UUID source=archive(tenant,connection,job,cursor,body);
        for(var product:products.values())jdbc.update("""
            INSERT INTO shopify_products(tenant_id,marketplace_connection_id,product_gid,title,vendor,product_status,storefront_url,image_url,source_updated_at,source_document_id,observation_hash)
            VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id,product_gid) DO UPDATE SET
            title=EXCLUDED.title,vendor=EXCLUDED.vendor,product_status=EXCLUDED.product_status,storefront_url=EXCLUDED.storefront_url,image_url=EXCLUDED.image_url,
            source_updated_at=EXCLUDED.source_updated_at,source_document_id=EXCLUDED.source_document_id,observation_hash=EXCLUDED.observation_hash,observed_at=clock_timestamp()
            WHERE shopify_products.source_updated_at<=EXCLUDED.source_updated_at
            """,tenant,connection,product.gid(),product.title(),product.vendor(),product.status(),product.storefront(),product.image(),time(product.updated()),source,hash(json.writeValueAsString(product)));
        for(var variant:page.variants()){
            UUID product=jdbc.queryForObject("SELECT id FROM shopify_products WHERE tenant_id=? AND marketplace_connection_id=? AND product_gid=?",UUID.class,tenant,connection,variant.product().gid());
            jdbc.update("""
                INSERT INTO shopify_variants(tenant_id,marketplace_connection_id,product_id,variant_gid,inventory_item_gid,seller_sku,title,selected_options,barcodes,barcodes_complete,price,currency,image_url,source_updated_at,source_document_id,last_seen_run,observation_hash,mapping_hash)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,marketplace_connection_id,variant_gid) DO UPDATE SET product_id=EXCLUDED.product_id,inventory_item_gid=EXCLUDED.inventory_item_gid,
                seller_sku=EXCLUDED.seller_sku,title=EXCLUDED.title,selected_options=EXCLUDED.selected_options,barcodes=EXCLUDED.barcodes,barcodes_complete=EXCLUDED.barcodes_complete,
                price=EXCLUDED.price,currency=EXCLUDED.currency,image_url=EXCLUDED.image_url,source_updated_at=EXCLUDED.source_updated_at,source_document_id=EXCLUDED.source_document_id,
                last_seen_run=EXCLUDED.last_seen_run,observation_hash=EXCLUDED.observation_hash,mapping_hash=EXCLUDED.mapping_hash,observed_at=clock_timestamp()
                WHERE shopify_variants.source_updated_at<=EXCLUDED.source_updated_at
                """,tenant,connection,product,variant.gid(),variant.inventoryId(),variant.sku(),variant.title(),variant.options(),variant.barcodes(),variant.complete(),variant.price(),currency,variant.image(),time(variant.updated()),source,run,variantHash(variant),com.nextaicommerce.platform.marketplace.ShopifySkuMatcher.fingerprint(variant));
        }
    }
    private String variantHash(ShopifyCatalogClient.Variant v){
        var fields=new LinkedHashMap<String,Object>();fields.put("gid",v.gid());fields.put("product",v.product().gid());fields.put("inventory",v.inventoryId());fields.put("sku",v.sku());fields.put("title",v.title());fields.put("options",v.options());fields.put("barcodes",v.barcodes());fields.put("price",v.price());fields.put("image",v.image());return hash(json.writeValueAsString(fields));
    }
    private void checkRevision(String table,String identity,UUID tenant,UUID connection,String gid,Instant updated,String hash){
        var old=jdbc.queryForList("SELECT source_updated_at,observation_hash FROM "+table+" WHERE tenant_id=? AND marketplace_connection_id=? AND "+identity+"=?",tenant,connection,gid);
        if(!old.isEmpty()&&((Timestamp)old.getFirst().get("source_updated_at")).toInstant().equals(updated)&&!hash.equals(old.getFirst().get("observation_hash")))throw new IllegalArgumentException("Conflicting Shopify evidence at the same source revision. Existing record and mapping retained.");
    }
}
