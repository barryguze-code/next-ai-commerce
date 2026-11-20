package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Bounded local reads only. All writes remain in the existing mapping workflow. */
@RestController
public class ProductMappingReviewController {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccessRepository workspace;
    public ProductMappingReviewController(JdbcTemplate jdbc,WorkspaceAccessRepository workspace){this.jdbc=jdbc;this.workspace=workspace;}
    record Product(UUID id,String name,String code,String image){}
    record Listing(String provider,String sellerSku,String productTitle,String image,UUID variantId,String variantRevision){}
    private UUID tenant(HttpSession session,Authentication actor){
        Object selected=session.getAttribute(AccountSelectionController.TENANT_ID);
        if(!(selected instanceof UUID id)||actor==null||!workspace.canOperateAccount(id,actor.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());return id;
    }
    @GetMapping("/app/catalog/mapping-review/products") @Transactional(readOnly=true)
    ResponseEntity<List<Product>> products(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID item,
            @RequestParam(required=false) UUID document,HttpSession session,Authentication actor){
        UUID tenant=tenant(session,actor);String text=q.trim();if(text.length()>160)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String pattern="%"+text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        var rows=jdbc.query("""
            SELECT a.id,coalesce(a.display_name,p.canonical_name),a.account_sku,
              CASE WHEN uploaded.account_catalog_item_id IS NOT NULL
                THEN '/app/catalog/products/'||a.id||'/image' ELSE image.image_url END
            FROM account_catalog_items a JOIN global_catalog_products p ON p.id=a.global_product_id
            LEFT JOIN account_catalog_product_images uploaded ON uploaded.tenant_id=a.tenant_id AND uploaded.account_catalog_item_id=a.id
            LEFT JOIN LATERAL (
              SELECT listing.image_url FROM marketplace_sku_mappings mapping
              JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id
              JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id AND listing.marketplace_connection_id=mapping.marketplace_connection_id AND upper(listing.seller_sku)=upper(mapping.marketplace_sku)
              WHERE mapping.tenant_id=a.tenant_id AND mapping.status='ACTIVE' AND component.account_catalog_item_id=a.id AND listing.image_url IS NOT NULL
                AND (SELECT count(*) FROM marketplace_sku_mapping_components c WHERE c.tenant_id=mapping.tenant_id AND c.marketplace_sku_mapping_id=mapping.id)=1
              ORDER BY listing.last_seen_at DESC LIMIT 1
            ) image ON true
            WHERE a.tenant_id=? AND a.status='ACTIVE'
              AND (?::uuid IS NULL OR a.id=?::uuid)
              AND (?::uuid IS NULL OR EXISTS(SELECT 1 FROM receiving_document_lines l
                WHERE l.tenant_id=a.tenant_id AND l.receiving_document_id=?::uuid AND l.account_catalog_item_id=a.id))
              AND (coalesce(a.display_name,p.canonical_name) ILIKE ? OR a.account_sku ILIKE ?
                OR EXISTS(SELECT 1 FROM vendor_catalog_offers v WHERE v.tenant_id=a.tenant_id AND v.account_catalog_item_id=a.id AND v.vendor_item_code ILIKE ?))
            ORDER BY coalesce(a.display_name,p.canonical_name),a.id LIMIT 25
            """,(rs,n)->new Product(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),tenant,item,item,document,document,pattern,pattern,pattern);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }
    @GetMapping("/app/catalog/mapping-review/listings") @Transactional(readOnly=true)
    ResponseEntity<List<Listing>> listings(@RequestParam String q,@RequestParam UUID item,HttpSession session,Authentication actor){
        UUID tenant=tenant(session,actor);Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(!(selected instanceof UUID store))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a marketplace store first.");
        boolean admin=actor.getAuthorities().stream().anyMatch(a->"ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        boolean allowed=workspace.listAccountOptions(actor.getName(),admin).stream().filter(a->a.id().equals(tenant)).flatMap(a->a.stores().stream()).anyMatch(s->s.id().equals(store));
        if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String channel=workspace.findConnection(tenant,store).channel();String text=q.trim();
        if((!text.isEmpty()&&text.length()<2)||text.length()>160)return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.of());
        String pattern="%"+text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        List<Listing> rows;
        if("AMAZON".equalsIgnoreCase(channel))rows=jdbc.query("""
            SELECT seller_sku,item_name,image_url FROM amazon_listings
            WHERE tenant_id=? AND marketplace_connection_id=? AND ((?<>'' AND (seller_sku ILIKE ? OR asin ILIKE ? OR item_name ILIKE ?)) OR (?='' AND EXISTS (
              SELECT 1 FROM marketplace_sku_mappings m WHERE m.tenant_id=amazon_listings.tenant_id AND m.marketplace_connection_id=amazon_listings.marketplace_connection_id
                AND m.marketplace_sku=amazon_listings.seller_sku AND m.status='ACTIVE' AND (m.account_catalog_item_id=? OR EXISTS (
                  SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id AND c.account_catalog_item_id=?)))))
            ORDER BY seller_sku LIMIT 25
            """,(rs,n)->new Listing("AMAZON",rs.getString(1),rs.getString(2),rs.getString(3),null,null),tenant,store,text,pattern,pattern,pattern,text,item,item);
        else if("SHOPIFY".equalsIgnoreCase(channel))rows=jdbc.query("""
            SELECT v.id,v.seller_sku,p.title,v.observation_hash,coalesce(v.image_url,p.image_url) FROM shopify_variants v
            JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
            WHERE v.tenant_id=? AND v.marketplace_connection_id=? AND ((?<>'' AND (v.seller_sku ILIKE ? OR p.title ILIKE ?)) OR (?='' AND EXISTS (
              SELECT 1 FROM marketplace_sku_mappings m WHERE m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id
                AND m.shopify_variant_id=v.id AND m.status='ACTIVE' AND (m.account_catalog_item_id=? OR EXISTS (
                  SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id AND c.account_catalog_item_id=?)))))
            ORDER BY v.seller_sku,v.id LIMIT 25
            """,(rs,n)->new Listing("SHOPIFY",rs.getString(2),rs.getString(3),rs.getString(5),rs.getObject(1,UUID.class),rs.getString(4)),tenant,store,text,pattern,pattern,text,item,item);
        else throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an Amazon or Shopify store to map products.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }
}
