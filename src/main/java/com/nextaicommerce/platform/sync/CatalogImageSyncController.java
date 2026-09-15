package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Controller
public class CatalogImageSyncController {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AmazonSpApiClient amazon;
    private final CatalogRepository catalog;
    public CatalogImageSyncController(JdbcTemplate jdbc,TransactionTemplate tx,AmazonSpApiClient amazon,CatalogRepository catalog){
        this.jdbc=jdbc;this.tx=tx;this.amazon=amazon;this.catalog=catalog;
    }
    record Source(UUID globalId,UUID connection,String marketplace,String asin){}
    private void tenant(UUID id){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());}
    @PostMapping("/app/catalog/products/{itemId}/image/amazon") @ResponseBody
    public ResponseEntity<Map<String,String>> sync(@PathVariable UUID itemId,HttpSession session,Authentication auth){
        Object selected=session.getAttribute("selectedTenantId");
        UUID tenantId=selected instanceof UUID id?id:selected instanceof String value?UUID.fromString(value):null;
        if(tenantId==null)return ResponseEntity.badRequest().body(Map.of("error","Choose an account first."));
        try{
            Source source=tx.execute(s->{tenant(tenantId);return jdbc.query("""
                SELECT item.global_product_id,listing.marketplace_connection_id,listing.marketplace_id,listing.asin
                FROM account_catalog_items item
                JOIN marketplace_sku_mapping_components component ON component.tenant_id=item.tenant_id AND component.account_catalog_item_id=item.id
                JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=component.tenant_id AND mapping.id=component.marketplace_sku_mapping_id
                JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id AND listing.marketplace_connection_id=mapping.marketplace_connection_id AND listing.seller_sku=mapping.marketplace_sku
                JOIN marketplace_connections connection ON connection.tenant_id=listing.tenant_id AND connection.id=listing.marketplace_connection_id
                WHERE item.tenant_id=? AND item.id=? AND mapping.status='ACTIVE' AND connection.status='ACTIVE'
                  AND listing.asin IS NOT NULL AND listing.asin<>'' AND listing.platform_status='VISIBLE'
                  AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components other WHERE other.tenant_id=mapping.tenant_id AND other.marketplace_sku_mapping_id=mapping.id AND other.account_catalog_item_id<>item.id)
                ORDER BY listing.updated_at DESC,listing.asin LIMIT 1
                """,rs->rs.next()?new Source(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)):null,tenantId,itemId);});
            if(source==null)throw new IllegalArgumentException("No active Amazon SKU mapped only to this item was found. Check SKU mapping first; mixed-product bundles are not used for product pictures.");
            var response=amazon.get(tenantId,source.connection(),"/catalog/2022-04-01/items/"+encode(source.asin())+"?marketplaceIds="+encode(source.marketplace())+"&includedData=images").json();
            String url=null;
            for(var group:response.path("images"))if(source.marketplace().equals(group.path("marketplaceId").asText()))
                for(var image:group.path("images"))if("MAIN".equals(image.path("variant").asText())){url=image.path("link").asText();break;}
            if(url==null||url.isBlank())throw new IllegalArgumentException("Amazon did not return a main image. The existing pictures were kept.");
            URI uri=URI.create(url);if(!safeImageUri(uri))throw new IllegalArgumentException("Amazon returned an unsupported image address. The existing pictures were kept.");
            byte[] bytes;String type;
            try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()){
                var download=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var input=download.body()){
                    type=download.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
                    if(download.statusCode()!=200||!Set.of("image/jpeg","image/png","image/webp").contains(type))throw new IllegalArgumentException("The Amazon image could not be downloaded. Existing pictures were kept.");
                    bytes=input.readNBytes(4_000_001);if(bytes.length==0||bytes.length>4_000_000)throw new IllegalArgumentException("The Amazon image is empty or larger than 4 MB. Existing pictures were kept.");
                }
            }
            final String imageType=type;
            tx.executeWithoutResult(s->{tenant(tenantId);
                UUID current=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=? FOR UPDATE",UUID.class,tenantId,itemId);
                if(!source.globalId().equals(current))throw new IllegalArgumentException("The item changed during sync. Please try again.");
                catalog.saveProductImage(tenantId,auth.getName(),itemId,imageType,"amazon-"+source.asin(),bytes);
                jdbc.update("""
                    INSERT INTO global_catalog_product_images(global_product_id,image_bytes,image_content_type,source_asin,source_tenant_id)
                    VALUES (?,?,?,?,?) ON CONFLICT(global_product_id) DO UPDATE SET image_bytes=EXCLUDED.image_bytes,
                    image_content_type=EXCLUDED.image_content_type,source_asin=EXCLUDED.source_asin,source_tenant_id=EXCLUDED.source_tenant_id,updated_at=now()
                    """,current,bytes,imageType,source.asin(),tenantId);
            });
            return ResponseEntity.ok(Map.of("message","Account and Global Catalogue pictures updated from Amazon ("+source.asin()+").","imageUrl","/app/catalog/products/"+itemId+"/image"));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return ResponseEntity.status(502).body(Map.of("error","Amazon image sync is temporarily unavailable. No picture was changed. Please try again later."));}
    }
    static boolean safeImageUri(URI uri){
        String host=uri.getHost();return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&host!=null
            &&(host.equals("m.media-amazon.com")||host.endsWith(".ssl-images-amazon.com"));
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    @GetMapping("/app/platform/catalog/products/{id}/image")
    public ResponseEntity<byte[]> globalImage(@PathVariable UUID id){
        var image=jdbc.query("SELECT image_bytes,image_content_type FROM global_catalog_product_images WHERE global_product_id=?",
            rs->rs.next()?new CatalogRepository.ProductImage(rs.getBytes(1),rs.getString(2)):null,id);
        return image==null?ResponseEntity.notFound().build():ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.parseMediaType(image.contentType())).body(image.bytes());
    }
}
