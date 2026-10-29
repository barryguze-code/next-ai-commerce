package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** Versioned read-only catalogue contract. No inventory quantities, Orders, costs or mutations. */
@Component
public class ShopifyCatalogClient {
    public static final String REVISION="variants-2026-10-v1";
    static final String QUERY="""
        query CatalogueVariants($after:String) {
          shop { id currencyCode }
          productVariants(first:10,after:$after,sortKey:ID) {
            pageInfo { hasNextPage endCursor }
            nodes { id title sku price updatedAt selectedOptions { name value } inventoryItem { id }
              barcodes(first:20) { nodes { type value } pageInfo { hasNextPage } }
              media(first:1) { nodes { ... on MediaImage { image { url } } } }
              product { id title vendor status updatedAt onlineStoreUrl featuredMedia { ... on MediaImage { image { url } } } }
            }
          }
        }
        """;
    public record Product(String gid,String title,String vendor,String status,Instant updated,String storefront,String image){}
    public record Variant(String gid,String inventoryId,String sku,String title,BigDecimal price,Instant updated,String options,String barcodes,boolean complete,String image,Product product){}
    public record Page(List<Variant> variants,String next,boolean more,double cost,double available,double restoreRate){}
    private final ShopifyConnectionService connections;
    private final ObjectMapper json=new ObjectMapper();
    public ShopifyCatalogClient(ShopifyConnectionService connections){this.connections=connections;}
    public ShopifyConnectionService.CatalogResponse fetch(UUID tenant,UUID connection,String cursor,Runnable reserve){
        var variables=new LinkedHashMap<String,Object>();variables.put("after",cursor);
        return connections.readCatalog(tenant,connection,json.writeValueAsString(Map.of("query",QUERY,"variables",variables)),reserve);
    }
    public Page parse(ShopifyConnectionService.CatalogResponse response,String cursor){
        var root=json.readTree(response.body());
        if(root.has("errors"))throw new IllegalArgumentException("Shopify returned incomplete catalogue data. Previous observations were kept.");
        var data=root.path("data");
        if(!response.shopId().equals(data.path("shop").path("id").asText())||!response.currency().equals(data.path("shop").path("currencyCode").asText()))
            throw new IllegalArgumentException("Shopify shop identity or currency changed. Verify the connection before retrying.");
        var connection=data.path("productVariants");var nodes=connection.path("nodes");var page=connection.path("pageInfo");
        if(!nodes.isArray()||nodes.size()>10||!page.path("hasNextPage").isBoolean())throw new IllegalArgumentException("Shopify catalogue page is incomplete.");
        boolean more=page.path("hasNextPage").asBoolean();String next=page.path("endCursor").asText("");
        if(more&&(next.isBlank()||next.equals(cursor)||nodes.isEmpty()))throw new IllegalArgumentException("Shopify returned a non-advancing catalogue cursor.");
        List<Variant> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(var n:nodes){
            String gid=gid(n,"ProductVariant");if(!ids.add(gid))throw new IllegalArgumentException("Shopify repeated a variant identity within one page.");
            var p=n.path("product");String productId=gid(p,"Product");String inventoryId=gid(n.path("inventoryItem"),"InventoryItem");
            var barcodes=n.path("barcodes");if(!barcodes.path("nodes").isArray()||barcodes.path("nodes").size()>20||!barcodes.path("pageInfo").path("hasNextPage").isBoolean()||barcodes.path("pageInfo").path("hasNextPage").asBoolean())throw new IllegalArgumentException("Shopify barcode evidence is incomplete.");
            for(var b:barcodes.path("nodes"))if(!b.has("type")||(!b.path("type").isNull()&&(!b.path("type").isString()||b.path("type").asText().isBlank()))||!b.path("value").isString()||b.path("value").asText().isBlank()||b.path("value").asText().length()>255)throw new IllegalArgumentException("Shopify barcode evidence is invalid.");
            if(!n.has("sku")||(!n.path("sku").isNull()&&!n.path("sku").isString()))throw new IllegalArgumentException("Shopify SKU evidence is incomplete.");
            if(!n.path("selectedOptions").isArray())throw new IllegalArgumentException("Shopify variant options are incomplete.");
            for(var option:n.path("selectedOptions")){required(option,"name");required(option,"value");}
            var product=new Product(productId,required(p,"title"),p.path("vendor").asText(""),required(p,"status"),Instant.parse(required(p,"updatedAt")),safePublicUrl(p.path("onlineStoreUrl").asText(null)),image(p.path("featuredMedia").path("image").path("url").asText(null)));
            BigDecimal price=new BigDecimal(required(n,"price"));if(price.signum()<0||price.precision()>24||price.scale()>8)throw new IllegalArgumentException("Shopify returned an unsupported price.");
            String ownImage=null;for(var m:n.path("media").path("nodes")){ownImage=image(m.path("image").path("url").asText(null));if(ownImage!=null)break;}
            result.add(new Variant(gid,inventoryId,n.path("sku").isNull()?null:n.path("sku").asText(null),required(n,"title"),price,Instant.parse(required(n,"updatedAt")),n.path("selectedOptions").toString(),barcodes.path("nodes").toString(),true,ownImage,product));
        }
        var cost=root.path("extensions").path("cost");double requested=cost.path("requestedQueryCost").asDouble(-1);
        if(requested<0||requested>1000)throw new IllegalArgumentException("Shopify query cost is missing or exceeds the request budget.");
        return new Page(List.copyOf(result),next,more,requested,cost.path("throttleStatus").path("currentlyAvailable").asDouble(0),cost.path("throttleStatus").path("restoreRate").asDouble(0));
    }
    static String required(JsonNode node,String field){String value=node.path(field).asText("");if(value.isBlank())throw new IllegalArgumentException("Shopify returned incomplete "+field+" metadata.");return value;}
    static String gid(JsonNode n,String kind){String id=required(n,"id");if(!id.matches("gid://shopify/"+kind+"/[0-9]+"))throw new IllegalArgumentException("Shopify returned an invalid "+kind+" identity.");return id;}
    public static String image(String value){String safe=safePublicUrl(value);return safe!=null&&URI.create(safe).getHost().equals("cdn.shopify.com")?safe:null;}
    public static String safePublicUrl(String value){try{URI u=URI.create(value);return "https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)?value:null;}catch(RuntimeException e){return null;}}
}
