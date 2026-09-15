package com.nextaicommerce.platform.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record GlobalProductView(UUID id, String name, String brand, String vendorName,
            String vendorItemCode, String identifier, String unitOfMeasure,
            boolean expirationRequired, String status) {}
    public record AccountItemView(UUID id, UUID globalProductId, String name, String brand,
            String vendorName, String vendorItemCode, String identifier, String accountSku,
            int vendorCount, BigDecimal currentBuyingCost, String currency,
            boolean expirationRequired, String status,String completionStatus,
            UUID defaultLocationId,String defaultLocationCode,String defaultLocationName,String imageUrl) {
        public AccountItemView(UUID id, UUID globalProductId, String name, String brand,
                String vendorName, String vendorItemCode, String identifier, String accountSku,
                int vendorCount, BigDecimal currentBuyingCost, String currency,
                boolean expirationRequired, String status,String completionStatus) {
            this(id,globalProductId,name,brand,vendorName,vendorItemCode,identifier,accountSku,vendorCount,
                currentBuyingCost,currency,expirationRequired,status,completionStatus,null,"MAIN","Main storage",null);
        }
    }
    public record AccountItemPage(List<AccountItemView> rows,long total,int page,int size){
        public int totalPages(){return Math.max(1,(int)Math.ceil(total/(double)size));}
        public boolean hasPrevious(){return page>0;} public boolean hasNext(){return page+1<totalPages();}
        public long firstItem(){return total==0?0:(long)page*size+1;} public long lastItem(){return Math.min(total,(long)(page+1)*size);}
        public int displayPage(){return page+1;}
    }
    public record VendorView(UUID id, String name, String code, String currency,
            BigDecimal discountRate, BigDecimal defaultFreightAmount, int productCount, String status) {}
    public record InlineProductResult(UUID productId, UUID vendorId, String vendorName) {}
    public record VendorOfferView(UUID itemId, UUID vendorId, String vendorName, String vendorItemCode,
            BigDecimal listCost, BigDecimal discountRate, BigDecimal buyingCost, String currency,
            boolean preferred) {}
    public record LocationView(UUID id,String code,String name,String status) {}
    public record MarketplaceSkuRef(UUID itemId,String sku,BigDecimal quantity,String status,
            String channel,Integer marketplaceQuantity,String fulfillment,String asin,
            BigDecimal price,String currency,String amazonDomain) {
        public MarketplaceSkuRef(UUID itemId,String sku,BigDecimal quantity,String status,String channel,Integer marketplaceQuantity,String fulfillment,String asin,BigDecimal price,String currency){
            this(itemId,sku,quantity,status,channel,marketplaceQuantity,fulfillment,asin,price,currency,"amazon.com");
        }
    }
    public record ProductImage(byte[] bytes,String contentType) {}

    @Transactional(readOnly=true)
    public List<MarketplaceSkuRef> listMarketplaceSkus(UUID tenantId,List<UUID> itemIds){
        setTenant(tenantId);if(itemIds==null||itemIds.isEmpty())return List.of();
        String placeholders=String.join(",",java.util.Collections.nCopies(itemIds.size(),"?"));
        List<Object> parameters=new java.util.ArrayList<>();parameters.add(tenantId);parameters.addAll(itemIds);
        return jdbc.query("""
            WITH effective_component AS (
              SELECT component.tenant_id,component.marketplace_sku_mapping_id,
                     component.account_catalog_item_id,component.quantity
              FROM marketplace_sku_mapping_components component
              WHERE component.tenant_id=?
              UNION ALL
              SELECT mapping.tenant_id,mapping.id,mapping.account_catalog_item_id,
                     greatest(mapping.quantity_per_marketplace_unit,1)
              FROM marketplace_sku_mappings mapping
              WHERE mapping.tenant_id=? AND mapping.status='ACTIVE'
                AND mapping.account_catalog_item_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM marketplace_sku_mapping_components existing
                  WHERE existing.tenant_id=mapping.tenant_id
                    AND existing.marketplace_sku_mapping_id=mapping.id)
            )
            SELECT component.account_catalog_item_id,mapping.marketplace_sku,component.quantity,
              coalesce(listing.platform_status,listing.listing_status,mapping.status),connection.channel,
              listing.quantity,listing.fulfillment_channel,coalesce(listing.asin,mapping.asin),
              listing.price,listing.currency,connection.marketplace_identifier
            FROM effective_component component
            JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=component.tenant_id
              AND mapping.id=component.marketplace_sku_mapping_id
            JOIN marketplace_connections connection ON connection.tenant_id=mapping.tenant_id
              AND connection.id=mapping.marketplace_connection_id
            LEFT JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id
              AND listing.marketplace_connection_id=mapping.marketplace_connection_id
              AND upper(listing.seller_sku)=upper(mapping.marketplace_sku)
            WHERE component.tenant_id=? AND mapping.status='ACTIVE'
              AND component.account_catalog_item_id IN ("""+placeholders+") ORDER BY upper(mapping.marketplace_sku)",
            (rs,row)->new MarketplaceSkuRef(rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),
                rs.getString(4),rs.getString(5),(Integer)rs.getObject(6),rs.getString(7),rs.getString(8),
                rs.getBigDecimal(9),rs.getString(10),com.nextaicommerce.platform.marketplace.MarketplaceLinks.amazonDomain(rs.getString(11))),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(tenantId,tenantId),parameters.stream()).toArray());
    }

    @Transactional
    public void saveProductImage(UUID tenantId,String actorEmail,UUID itemId,String contentType,String filename,byte[] bytes){
        setTenant(tenantId);
        Integer valid=jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=? AND id=?",Integer.class,tenantId,itemId);
        if(valid==null||valid!=1)throw new IllegalArgumentException("This catalogue product is unavailable.");
        jdbc.update("""
            INSERT INTO account_catalog_product_images(tenant_id,account_catalog_item_id,image_bytes,image_content_type,original_filename,uploaded_by)
            VALUES (?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
            ON CONFLICT (tenant_id,account_catalog_item_id) DO UPDATE SET image_bytes=EXCLUDED.image_bytes,
              image_content_type=EXCLUDED.image_content_type,original_filename=EXCLUDED.original_filename,
              uploaded_by=EXCLUDED.uploaded_by,updated_at=now()
            """,tenantId,itemId,bytes,contentType,filename,actorEmail);
    }

    @Transactional(readOnly=true)
    public ProductImage productImage(UUID tenantId,UUID itemId){
        setTenant(tenantId);
        return jdbc.query("SELECT image_bytes,image_content_type FROM account_catalog_product_images WHERE tenant_id=? AND account_catalog_item_id=?",
            rs->rs.next()?new ProductImage(rs.getBytes(1),rs.getString(2)):null,tenantId,itemId);
    }

    @Transactional(readOnly = true)
    public List<UUID> globalProductImageIds(){
        return jdbc.query("SELECT global_product_id FROM global_catalog_product_images",(rs,row)->rs.getObject(1,UUID.class));
    }

    @Transactional(readOnly = true)
    public List<GlobalProductView> listGlobalProducts() {
        return jdbc.query("""
            SELECT product.id, product.canonical_name, product.brand,
                   vendor_code.vendor_name,vendor_code.vendor_item_code,identifier.identifier_value,
                   product.unit_of_measure, product.requires_expiration_date, product.status
            FROM global_catalog_products product
            LEFT JOIN LATERAL (
                SELECT vendor_name,vendor_item_code FROM global_product_vendor_codes
                WHERE global_product_id=product.id ORDER BY last_seen_at DESC LIMIT 1
            ) vendor_code ON true
            LEFT JOIN LATERAL (
                SELECT identifier_value FROM global_product_identifiers
                WHERE global_product_id=product.id ORDER BY is_primary DESC, created_at LIMIT 1
            ) identifier ON true
            ORDER BY lower(product.canonical_name)
            """, (rs, row) -> new GlobalProductView(rs.getObject("id", UUID.class),
                rs.getString("canonical_name"), rs.getString("brand"), rs.getString("vendor_name"),
                rs.getString("vendor_item_code"),rs.getString("identifier_value"),
                rs.getString("unit_of_measure"), rs.getBoolean("requires_expiration_date"),
                rs.getString("status")));
    }

    @Transactional(readOnly = true)
    public List<AccountItemView> listAccountItems(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT item.id, item.global_product_id,
                   coalesce(item.display_name, product.canonical_name) display_name, product.brand,
                   offer.vendor_name,offer.vendor_item_code,identifier.identifier_value,
                   CASE WHEN nullif(regexp_replace(upper(coalesce(item.account_sku,'')),'[^A-Z0-9]','','g'),'')
                     =nullif(regexp_replace(upper(coalesce(offer.vendor_item_code,'')),'[^A-Z0-9]','','g'),'')
                     THEN NULL ELSE item.account_sku END account_sku,
                   item.status, product.requires_expiration_date,item.completion_status,
                   (SELECT count(DISTINCT offer.vendor_id) FROM vendor_catalog_offers offer
                    WHERE offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id
                      AND offer.effective_to IS NULL) vendor_count,
                   offer.buying_cost, offer.currency,location.id location_id,location.code location_code,location.name location_name,
                   CASE WHEN uploaded.account_catalog_item_id IS NOT NULL
                     THEN '/app/catalog/products/'||item.id||'/image' ELSE image.image_url END image_url
            FROM account_catalog_items item
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (
                SELECT identifier_value FROM global_product_identifiers
                WHERE global_product_id=product.id ORDER BY is_primary DESC, created_at LIMIT 1
            ) identifier ON true
            LEFT JOIN LATERAL (
                SELECT round(offer.list_cost * (1-offer.discount_rate/100),4) buying_cost,
                       offer.currency,offer.vendor_item_code,vendor.name vendor_name
                FROM vendor_catalog_offers offer JOIN vendors vendor
                  ON vendor.tenant_id=offer.tenant_id AND vendor.id=offer.vendor_id
                WHERE offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id AND offer.effective_to IS NULL
                ORDER BY offer.is_default DESC, offer.updated_at DESC LIMIT 1
            ) offer ON true
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=item.tenant_id
                AND assignment.account_catalog_item_id=item.id AND assignment.is_default
            LEFT JOIN warehouse_locations location ON location.tenant_id=assignment.tenant_id AND location.id=assignment.location_id
            LEFT JOIN account_catalog_product_images uploaded ON uploaded.tenant_id=item.tenant_id AND uploaded.account_catalog_item_id=item.id
            LEFT JOIN LATERAL (
                SELECT listing.image_url FROM marketplace_sku_mappings mapping
                JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
                  AND component.marketplace_sku_mapping_id=mapping.id
                JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id
                  AND listing.marketplace_connection_id=mapping.marketplace_connection_id
                  AND upper(listing.seller_sku)=upper(mapping.marketplace_sku)
                WHERE mapping.tenant_id=item.tenant_id AND mapping.status='ACTIVE'
                  AND component.account_catalog_item_id=item.id AND listing.image_url IS NOT NULL
                  AND (SELECT count(*) FROM marketplace_sku_mapping_components all_components
                       WHERE all_components.tenant_id=mapping.tenant_id AND all_components.marketplace_sku_mapping_id=mapping.id)=1
                ORDER BY listing.last_seen_at DESC LIMIT 1
            ) image ON true
            WHERE item.tenant_id=?
            ORDER BY lower(coalesce(item.display_name, product.canonical_name))
            """, (rs, row) -> new AccountItemView(rs.getObject("id", UUID.class),
                rs.getObject("global_product_id", UUID.class), rs.getString("display_name"),
                rs.getString("brand"),rs.getString("vendor_name"),rs.getString("vendor_item_code"),
                rs.getString("identifier_value"), rs.getString("account_sku"),
                rs.getInt("vendor_count"), rs.getBigDecimal("buying_cost"), rs.getString("currency"),
                rs.getBoolean("requires_expiration_date"), rs.getString("status"),rs.getString("completion_status"),
                rs.getObject("location_id",UUID.class),rs.getString("location_code"),rs.getString("location_name"),rs.getString("image_url")), tenantId);
    }


    /** Match related identifiers once, not once per catalogue row. Count/page share this query. */
    private record AccountQuery(String prefix,String from,List<Object> parameters) {}
    private static AccountQuery accountQuery(UUID tenantId,String query){
        String from=" FROM account_catalog_items item JOIN global_catalog_products product ON product.id=item.global_product_id";
        if(query.isEmpty())return new AccountQuery("",from+" WHERE item.tenant_id=?",List.of(tenantId));
        String pattern="%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String prefix="""
            WITH search_input AS MATERIALIZED (SELECT ?::uuid tenant_id,?::text pattern),
            matched_items AS MATERIALIZED (
              SELECT item.id FROM account_catalog_items item
              JOIN global_catalog_products product ON product.id=item.global_product_id
              CROSS JOIN search_input q WHERE item.tenant_id=q.tenant_id
                AND (coalesce(item.display_name,product.canonical_name) ILIKE q.pattern
                  OR product.brand ILIKE q.pattern OR item.account_sku ILIKE q.pattern)
              UNION
              SELECT offer.account_catalog_item_id FROM vendor_catalog_offers offer
              CROSS JOIN search_input q WHERE offer.tenant_id=q.tenant_id AND offer.effective_to IS NULL
                AND offer.vendor_item_code ILIKE q.pattern
              UNION
              SELECT item.id FROM global_product_identifiers identifier
              JOIN account_catalog_items item ON item.global_product_id=identifier.global_product_id
              CROSS JOIN search_input q WHERE item.tenant_id=q.tenant_id AND identifier.identifier_value ILIKE q.pattern
              UNION
              SELECT mapping.account_catalog_item_id FROM marketplace_sku_mappings mapping
              CROSS JOIN search_input q WHERE mapping.tenant_id=q.tenant_id AND mapping.status='ACTIVE'
                AND (mapping.asin ILIKE q.pattern OR mapping.marketplace_sku ILIKE q.pattern)
              UNION
              SELECT component.account_catalog_item_id FROM marketplace_sku_mappings mapping
              JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
                AND component.marketplace_sku_mapping_id=mapping.id
              CROSS JOIN search_input q WHERE mapping.tenant_id=q.tenant_id AND mapping.status='ACTIVE'
                AND (mapping.asin ILIKE q.pattern OR mapping.marketplace_sku ILIKE q.pattern)
            )
            """;
        return new AccountQuery(prefix,from+" WHERE item.tenant_id=(SELECT tenant_id FROM search_input)"
            +" AND item.id IN(SELECT id FROM matched_items)",List.of(tenantId,pattern));
    }
    @Transactional(readOnly=true)
    public AccountItemPage pageAccountItems(UUID tenantId,String search,int page,int size){
        setTenant(tenantId);String query=search==null?"":search.trim();size=Math.max(10,Math.min(100,size));page=Math.max(0,page);
        var searchQuery=accountQuery(tenantId,query);
        String matches=searchQuery.from();
        var parameters=new java.util.ArrayList<Object>(searchQuery.parameters());
        long total=jdbc.queryForObject(searchQuery.prefix()+" SELECT count(*)"+matches,Long.class,parameters.toArray());
        int pages=Math.max(1,(int)Math.ceil(total/(double)size));page=Math.min(page,pages-1);
        parameters.add(size);parameters.add((long)page*size);
        // Materialize ONLY the page IDs before image, price, vendor and location enrichment.
        String selected=searchQuery.prefix()+(searchQuery.prefix().isEmpty()?"WITH ":", ")+"selected AS MATERIALIZED (SELECT item.id,lower(coalesce(item.display_name,product.canonical_name)) sort_name"
            +matches+" ORDER BY sort_name,item.id LIMIT ? OFFSET ?) ";
        List<AccountItemView> rows=jdbc.query(selected+"""
            SELECT item.id,item.global_product_id,coalesce(item.display_name,product.canonical_name) display_name,product.brand,
              offer.vendor_name,offer.vendor_item_code,identifier.identifier_value,
              CASE WHEN nullif(regexp_replace(upper(coalesce(item.account_sku,'')),'[^A-Z0-9]','','g'),'')
                =nullif(regexp_replace(upper(coalesce(offer.vendor_item_code,'')),'[^A-Z0-9]','','g'),'')
                THEN NULL ELSE item.account_sku END account_sku,item.status,
              product.requires_expiration_date,item.completion_status,
              (SELECT count(DISTINCT x.vendor_id) FROM vendor_catalog_offers x WHERE x.tenant_id=item.tenant_id
                AND x.account_catalog_item_id=item.id AND x.effective_to IS NULL) vendor_count,
              offer.buying_cost,offer.currency,location.id location_id,location.code location_code,location.name location_name,
              CASE WHEN uploaded.account_catalog_item_id IS NOT NULL
                THEN '/app/catalog/products/'||item.id||'/image' ELSE image.image_url END image_url
            FROM selected JOIN account_catalog_items item ON item.id=selected.id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL(SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=product.id
              ORDER BY is_primary DESC,created_at LIMIT 1) identifier ON true
            LEFT JOIN LATERAL(SELECT round(x.list_cost*(1-x.discount_rate/100),4) buying_cost,x.currency,
              x.vendor_item_code,vendor.name vendor_name FROM vendor_catalog_offers x JOIN vendors vendor
              ON vendor.tenant_id=x.tenant_id AND vendor.id=x.vendor_id WHERE x.tenant_id=item.tenant_id
              AND x.account_catalog_item_id=item.id AND x.effective_to IS NULL ORDER BY x.is_default DESC,x.updated_at DESC LIMIT 1) offer ON true
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=item.tenant_id
              AND assignment.account_catalog_item_id=item.id AND assignment.is_default
            LEFT JOIN warehouse_locations location ON location.tenant_id=assignment.tenant_id AND location.id=assignment.location_id
            LEFT JOIN account_catalog_product_images uploaded ON uploaded.tenant_id=item.tenant_id AND uploaded.account_catalog_item_id=item.id
            LEFT JOIN LATERAL (
              SELECT listing.image_url FROM marketplace_sku_mappings mapping
              JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id
              JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id AND listing.marketplace_connection_id=mapping.marketplace_connection_id
                AND upper(listing.seller_sku)=upper(mapping.marketplace_sku)
              WHERE mapping.tenant_id=item.tenant_id AND mapping.status='ACTIVE' AND component.account_catalog_item_id=item.id
                AND listing.image_url IS NOT NULL AND (SELECT count(*) FROM marketplace_sku_mapping_components all_components
                  WHERE all_components.tenant_id=mapping.tenant_id AND all_components.marketplace_sku_mapping_id=mapping.id)=1
              ORDER BY listing.last_seen_at DESC LIMIT 1
            ) image ON true
            ORDER BY selected.sort_name,item.id
            """,(rs,row)->new AccountItemView(rs.getObject("id",UUID.class),rs.getObject("global_product_id",UUID.class),
                rs.getString("display_name"),rs.getString("brand"),rs.getString("vendor_name"),rs.getString("vendor_item_code"),
                rs.getString("identifier_value"),rs.getString("account_sku"),rs.getInt("vendor_count"),rs.getBigDecimal("buying_cost"),
                rs.getString("currency"),rs.getBoolean("requires_expiration_date"),rs.getString("status"),rs.getString("completion_status"),
                rs.getObject("location_id",UUID.class),rs.getString("location_code"),rs.getString("location_name"),rs.getString("image_url")),
            parameters.toArray());
        return new AccountItemPage(List.copyOf(rows),total,page,size);
    }


    /** Lightweight type-ahead lookup; intentionally avoids the catalogue page count query. */
    @Transactional(readOnly=true)
    public List<AccountItemView> searchAccountItems(UUID tenantId,String search,int limit){
        setTenant(tenantId);
        String query=search==null?"":search.trim();
        if(query.length()<2)return List.of();
        int size=Math.max(1,Math.min(limit,50));
        String pattern="%"+query+"%";
        return jdbc.query("""
            SELECT item.id,item.global_product_id,coalesce(item.display_name,product.canonical_name) display_name,
                   product.brand,offer.vendor_name,offer.vendor_item_code,identifier.identifier_value,
                   CASE WHEN nullif(regexp_replace(upper(coalesce(item.account_sku,'')),'[^A-Z0-9]','','g'),'')
                     =nullif(regexp_replace(upper(coalesce(offer.vendor_item_code,'')),'[^A-Z0-9]','','g'),'')
                     THEN NULL ELSE item.account_sku END account_sku,
                   item.status,product.requires_expiration_date,item.completion_status,
                   0 vendor_count,offer.buying_cost,offer.currency,
                   location.id location_id,location.code location_code,location.name location_name,
                   CASE WHEN EXISTS (SELECT 1 FROM account_catalog_product_images image
                     WHERE image.tenant_id=item.tenant_id AND image.account_catalog_item_id=item.id)
                     THEN '/app/catalog/products/'||item.id||'/image' END image_url
            FROM account_catalog_items item
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (
                SELECT identifier_value FROM global_product_identifiers
                WHERE global_product_id=product.id ORDER BY is_primary DESC,created_at LIMIT 1
            ) identifier ON true
            LEFT JOIN LATERAL (
                SELECT round(x.list_cost*(1-x.discount_rate/100),4) buying_cost,x.currency,
                       x.vendor_item_code,vendor.name vendor_name
                FROM vendor_catalog_offers x JOIN vendors vendor
                  ON vendor.tenant_id=x.tenant_id AND vendor.id=x.vendor_id
                WHERE x.tenant_id=item.tenant_id AND x.account_catalog_item_id=item.id
                  AND x.effective_to IS NULL
                ORDER BY x.is_default DESC,x.updated_at DESC LIMIT 1
            ) offer ON true
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=item.tenant_id
                AND assignment.account_catalog_item_id=item.id AND assignment.is_default
            LEFT JOIN warehouse_locations location ON location.tenant_id=assignment.tenant_id AND location.id=assignment.location_id
            WHERE item.tenant_id=? AND item.status='ACTIVE'
              AND (coalesce(item.display_name,product.canonical_name) ILIKE ?
                OR coalesce(product.brand,'') ILIKE ? OR coalesce(item.account_sku,'') ILIKE ?
                OR EXISTS (SELECT 1 FROM vendor_catalog_offers search_offer
                    WHERE search_offer.tenant_id=item.tenant_id
                      AND search_offer.account_catalog_item_id=item.id
                      AND search_offer.effective_to IS NULL
                      AND coalesce(search_offer.vendor_item_code,'') ILIKE ?)
                OR EXISTS (SELECT 1 FROM global_product_identifiers search_identifier
                    WHERE search_identifier.global_product_id=product.id
                      AND search_identifier.identifier_value ILIKE ?)
                OR EXISTS (SELECT 1 FROM marketplace_sku_mappings mapping
                    WHERE mapping.tenant_id=item.tenant_id AND mapping.status='ACTIVE'
                      AND (mapping.account_catalog_item_id=item.id OR EXISTS (SELECT 1 FROM marketplace_sku_mapping_components component
                        WHERE component.tenant_id=item.tenant_id AND component.marketplace_sku_mapping_id=mapping.id AND component.account_catalog_item_id=item.id))
                      AND coalesce(mapping.asin,'') ILIKE ?))
            ORDER BY CASE
                WHEN lower(coalesce(item.account_sku,''))=lower(?) THEN 0
                WHEN lower(coalesce(offer.vendor_item_code,''))=lower(?) THEN 1
                WHEN lower(coalesce(identifier.identifier_value,''))=lower(?) THEN 2
                WHEN lower(coalesce(item.display_name,product.canonical_name)) LIKE lower(?)||'%' THEN 3
                ELSE 4 END,
                lower(coalesce(item.display_name,product.canonical_name))
            LIMIT ?
            """,(rs,row)->new AccountItemView(rs.getObject("id",UUID.class),rs.getObject("global_product_id",UUID.class),
                rs.getString("display_name"),rs.getString("brand"),rs.getString("vendor_name"),
                rs.getString("vendor_item_code"),rs.getString("identifier_value"),rs.getString("account_sku"),
                rs.getInt("vendor_count"),rs.getBigDecimal("buying_cost"),rs.getString("currency"),
                rs.getBoolean("requires_expiration_date"),rs.getString("status"),rs.getString("completion_status"),
                rs.getObject("location_id",UUID.class),rs.getString("location_code"),rs.getString("location_name"),rs.getString("image_url")),
            tenantId,pattern,pattern,pattern,pattern,pattern,pattern,query,query,query,query,size);
    }

    @Transactional(readOnly = true)
    public List<VendorView> listVendors(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT vendor.id,vendor.name,vendor.vendor_code,vendor.currency,
                   vendor.default_discount_rate,vendor.default_freight_amount,vendor.status,
                   count(DISTINCT offer.account_catalog_item_id) product_count
            FROM vendors vendor LEFT JOIN vendor_catalog_offers offer
              ON offer.tenant_id=vendor.tenant_id AND offer.vendor_id=vendor.id AND offer.effective_to IS NULL
            WHERE vendor.tenant_id=?
            GROUP BY vendor.id ORDER BY lower(vendor.name)
            """, (rs, row) -> new VendorView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("vendor_code"), rs.getString("currency"), rs.getBigDecimal("default_discount_rate"),
                rs.getBigDecimal("default_freight_amount"), rs.getInt("product_count"), rs.getString("status")), tenantId);
    }

    /** Vendor selector data without scanning and counting the complete offer catalogue. */
    @Transactional(readOnly=true)
    public List<VendorView> listVendorChoices(UUID tenantId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT id,name,vendor_code,currency,default_discount_rate,default_freight_amount,status
            FROM vendors WHERE tenant_id=? AND status='ACTIVE' ORDER BY lower(name)
            """,(rs,row)->new VendorView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getBigDecimal(5),rs.getBigDecimal(6),0,rs.getString(7)),tenantId);
    }

    @Transactional(readOnly = true)
    public List<VendorOfferView> listVendorOffers(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT offer.account_catalog_item_id,offer.vendor_id,vendor.name,offer.vendor_item_code,
                   offer.list_cost,offer.discount_rate,
                   round(offer.list_cost*(1-offer.discount_rate/100),4) buying_cost,offer.currency,
                   item.preferred_vendor_id=offer.vendor_id preferred
            FROM vendor_catalog_offers offer
            JOIN vendors vendor ON vendor.tenant_id=offer.tenant_id AND vendor.id=offer.vendor_id
            JOIN account_catalog_items item ON item.tenant_id=offer.tenant_id AND item.id=offer.account_catalog_item_id
            WHERE offer.tenant_id=? AND offer.effective_to IS NULL
            ORDER BY lower(vendor.name)
            """,(rs,row)->new VendorOfferView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),
                rs.getString(8),rs.getBoolean(9)),tenantId);
    }

    @Transactional(readOnly=true)
    public List<VendorOfferView> listVendorOffers(UUID tenantId,List<UUID> itemIds){
        setTenant(tenantId);if(itemIds==null||itemIds.isEmpty())return List.of();
        String placeholders=String.join(",",java.util.Collections.nCopies(itemIds.size(),"?"));
        List<Object> parameters=new java.util.ArrayList<>();parameters.add(tenantId);parameters.addAll(itemIds);
        return jdbc.query("""
            SELECT offer.account_catalog_item_id,offer.vendor_id,vendor.name,offer.vendor_item_code,
              offer.list_cost,offer.discount_rate,round(offer.list_cost*(1-offer.discount_rate/100),4),offer.currency,
              item.preferred_vendor_id=offer.vendor_id FROM vendor_catalog_offers offer
            JOIN vendors vendor ON vendor.tenant_id=offer.tenant_id AND vendor.id=offer.vendor_id
            JOIN account_catalog_items item ON item.tenant_id=offer.tenant_id AND item.id=offer.account_catalog_item_id
            WHERE offer.tenant_id=? AND offer.effective_to IS NULL AND offer.account_catalog_item_id IN ("""+placeholders+") ORDER BY lower(vendor.name)",
            (rs,row)->new VendorOfferView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getString(8),rs.getBoolean(9)),parameters.toArray());
    }

    @Transactional
    public UUID addAccountProduct(UUID tenantId, String actorEmail, String name, String brand,
            String identifierType, String identifier, String accountSku, boolean expirationRequired) {
        setTenant(tenantId);
        UUID reactivated=reactivateAccountSkuIfPresent(tenantId,accountSku,name,brand,identifierType,identifier,expirationRequired);
        if(reactivated!=null)return reactivated;
        UUID actorId = actorId(actorEmail);
        String cleanIdentifier = normalizeIdentifier(identifierType, identifier);
        UUID globalId = cleanIdentifier.isBlank() ? null : jdbc.query("""
            SELECT global_product_id FROM global_product_identifiers
            WHERE identifier_type=? AND identifier_value=?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            identifierType.toUpperCase(Locale.ROOT), cleanIdentifier);
        if (globalId == null) {
            globalId = jdbc.queryForObject("""
                INSERT INTO global_catalog_products
                    (canonical_name,brand,requires_expiration_date,source_tenant_id,created_by)
                VALUES (?,?,?,?,?) RETURNING id
                """, UUID.class, name.trim(), blankToNull(brand), expirationRequired, tenantId, actorId);
            if (!cleanIdentifier.isBlank()) jdbc.update("""
                INSERT INTO global_product_identifiers
                    (global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,?,?,true)
                """, globalId, identifierType.toUpperCase(Locale.ROOT), cleanIdentifier);
        }
        return jdbc.queryForObject("""
            INSERT INTO account_catalog_items (tenant_id,global_product_id,account_sku,created_by)
            VALUES (?,?,?,?)
            ON CONFLICT (tenant_id,global_product_id) DO UPDATE SET
                account_sku=coalesce(EXCLUDED.account_sku,account_catalog_items.account_sku), updated_at=now()
            RETURNING id
            """, UUID.class, tenantId, globalId, blankToNull(accountSku), actorId);
    }

    @Transactional
    public UUID addImportedVendorProduct(UUID tenantId, String actorEmail, UUID vendorId,
            String vendorItemCode, String name, String brand, String identifierType,
            String identifier, String accountSku, boolean expirationRequired) {
        setTenant(tenantId);
        String cleanVendorCode=normalizeVendorItemCode(vendorItemCode);
        if(cleanVendorCode.isBlank())throw new IllegalArgumentException("Vendor item code is required.");
        UUID existing=jdbc.query("""
            SELECT account_catalog_item_id FROM vendor_catalog_offers
            WHERE tenant_id=? AND vendor_id=? AND upper(regexp_replace(vendor_item_code,'[^A-Za-z0-9]','','g'))=?
              AND effective_to IS NULL LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId,vendorId,cleanVendorCode);
        if(existing!=null){
            String incomingIdentifier=normalizeIdentifier(identifierType,identifier);
            if(!incomingIdentifier.isBlank()){
                UUID existingGlobal=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",UUID.class,tenantId,existing);
                UUID identifierGlobal=jdbc.query("SELECT global_product_id FROM global_product_identifiers WHERE identifier_type=? AND identifier_value=?",
                    rs->rs.next()?rs.getObject(1,UUID.class):null,identifierType.toUpperCase(Locale.ROOT),incomingIdentifier);
                if(identifierGlobal!=null&&!identifierGlobal.equals(existingGlobal))throw new IllegalArgumentException(
                    "Vendor item code "+vendorItemCode+" and identifier "+identifier+" belong to different global products. Review this row instead of creating a duplicate.");
            }
            reactivateAccountItem(existing,name,brand,expirationRequired);
            return existing;
        }
        VendorIdentity vendor=jdbc.query("SELECT name,vendor_code FROM vendors WHERE tenant_id=? AND id=?",
            rs->rs.next()?new VendorIdentity(rs.getString(1),rs.getString(2)):null,tenantId,vendorId);
        if(vendor==null)throw new IllegalArgumentException("Vendor was not found in this account.");
        UUID reactivated=reactivateAccountSkuIfPresent(tenantId,accountSku,name,brand,identifierType,identifier,expirationRequired);
        if(reactivated!=null){
            UUID reactivatedGlobal=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",
                UUID.class,tenantId,reactivated);
            saveGlobalVendorCode(reactivatedGlobal,tenantId,vendor,vendorItemCode);
            return reactivated;
        }
        String vendorKey=normalizeVendorKey(vendor.code()==null?vendor.name():vendor.code());
        UUID vendorGlobalId=jdbc.query("""
            SELECT global_product_id FROM global_product_vendor_codes
            WHERE vendor_key=? AND normalized_item_code=? ORDER BY last_seen_at DESC LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,vendorKey,cleanVendorCode);
        String cleanIdentifier=normalizeIdentifier(identifierType,identifier);
        UUID identifierGlobalId=null;
        if(!cleanIdentifier.isBlank()){
            identifierGlobalId=jdbc.query("SELECT global_product_id FROM global_product_identifiers WHERE identifier_type=? AND identifier_value=?",
                rs->rs.next()?rs.getObject(1,UUID.class):null,identifierType.toUpperCase(Locale.ROOT),cleanIdentifier);
        }
        if(vendorGlobalId!=null&&identifierGlobalId!=null&&!vendorGlobalId.equals(identifierGlobalId))
            throw new IllegalArgumentException("Vendor item code "+vendorItemCode+" and identifier "+identifier+" belong to different global products. Review this row instead of creating a duplicate.");
        UUID globalId=vendorGlobalId!=null?vendorGlobalId:identifierGlobalId;
        if(globalId==null){
            UUID actorId=actorId(actorEmail);
            globalId=jdbc.queryForObject("""
                INSERT INTO global_catalog_products
                    (canonical_name,brand,requires_expiration_date,source_tenant_id,created_by)
                VALUES (?,?,?,?,?) RETURNING id
                """,UUID.class,name.trim(),blankToNull(brand),expirationRequired,tenantId,actorId);
            if(!cleanIdentifier.isBlank()){
                int inserted=jdbc.update("""
                    INSERT INTO global_product_identifiers
                        (global_product_id,identifier_type,identifier_value,is_primary) VALUES (?,?,?,true)
                    ON CONFLICT (identifier_type,identifier_value) DO NOTHING
                    """,globalId,identifierType.toUpperCase(Locale.ROOT),cleanIdentifier);
                if(inserted==0){
                    UUID matched=jdbc.queryForObject("""
                        SELECT global_product_id FROM global_product_identifiers
                        WHERE identifier_type=? AND identifier_value=?
                        """,UUID.class,identifierType.toUpperCase(Locale.ROOT),cleanIdentifier);
                    if(matched!=null&&!matched.equals(globalId)){
                        jdbc.update("DELETE FROM global_catalog_products WHERE id=?",globalId);
                        globalId=matched;
                    }
                }
            }
        }
        UUID itemId=jdbc.queryForObject("""
            INSERT INTO account_catalog_items (tenant_id,global_product_id,account_sku,created_by)
            VALUES (?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
            ON CONFLICT (tenant_id,global_product_id) DO UPDATE SET
                account_sku=coalesce(EXCLUDED.account_sku,account_catalog_items.account_sku),updated_at=now()
            RETURNING id
            """,UUID.class,tenantId,globalId,blankToNull(accountSku),actorEmail);
        saveGlobalVendorCode(globalId,tenantId,vendor,vendorItemCode);
        return itemId;
    }

    /** Reuses an account SKU that is already present but hidden from active operational lookups. */
    private UUID reactivateAccountSkuIfPresent(UUID tenantId,String accountSku,String name,String brand,
            String identifierType,String identifier,boolean expirationRequired){
        String value=blankToNull(accountSku);if(value==null)return null;
        String normalized=value.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
        List<UUID> matches=jdbc.query("""
            SELECT id FROM account_catalog_items
            WHERE tenant_id=? AND upper(regexp_replace(account_sku,'[^A-Za-z0-9]','','g'))=?
            ORDER BY updated_at DESC LIMIT 2
            """,(rs,row)->rs.getObject(1,UUID.class),tenantId,normalized);
        if(matches.size()>1)throw new IllegalArgumentException("Item code "+accountSku+" matches more than one existing account product. Review the catalogue records before continuing.");
        if(matches.isEmpty())return null;
        UUID itemId=matches.getFirst();
        UUID globalId=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",
            UUID.class,tenantId,itemId);
        String cleanIdentifier=normalizeIdentifier(identifierType,identifier);
        if(!cleanIdentifier.isBlank()){
            UUID identifierGlobal=jdbc.query("SELECT global_product_id FROM global_product_identifiers WHERE identifier_type=? AND identifier_value=?",
                rs->rs.next()?rs.getObject(1,UUID.class):null,identifierType.toUpperCase(Locale.ROOT),cleanIdentifier);
            if(identifierGlobal!=null&&!identifierGlobal.equals(globalId))throw new IllegalArgumentException(
                "Identifier "+identifier+" belongs to another global product. Review it before reactivating item code "+accountSku+".");
            if(identifierGlobal==null)jdbc.update("""
                INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary)
                SELECT ?,?,?,NOT EXISTS(SELECT 1 FROM global_product_identifiers WHERE global_product_id=? AND is_primary)
                ON CONFLICT (identifier_type,identifier_value) DO NOTHING
                """,globalId,identifierType.toUpperCase(Locale.ROOT),cleanIdentifier,globalId);
        }
        reactivateAccountItem(itemId,name,brand,expirationRequired);
        return itemId;
    }

    private void reactivateAccountItem(UUID itemId,String name,String brand,boolean expirationRequired){
        jdbc.update("""
            UPDATE account_catalog_items SET status='ACTIVE',display_name=coalesce(nullif(?,''),display_name),updated_at=now()
            WHERE id=?
            """,name==null?null:name.trim(),itemId);
        jdbc.update("""
            UPDATE global_catalog_products product SET
              brand=coalesce(product.brand,nullif(?,'')),
              requires_expiration_date=product.requires_expiration_date OR ?
            FROM account_catalog_items item WHERE item.id=? AND product.id=item.global_product_id
            """,brand==null?null:brand.trim(),expirationRequired,itemId);
    }

    @Transactional
    public UUID addVendor(UUID tenantId, String name, String code, String currency,
            BigDecimal discountRate, BigDecimal defaultFreightAmount) {
        setTenant(tenantId);
        validateVendor(name,currency,discountRate,defaultFreightAmount);
        Boolean exists=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vendors WHERE tenant_id=? AND lower(name)=lower(?))",
            Boolean.class,tenantId,name.trim());
        if(Boolean.TRUE.equals(exists))throw new IllegalArgumentException("A vendor with this name already exists in the account.");
        return jdbc.queryForObject("""
            INSERT INTO vendors (tenant_id,name,vendor_code,currency,default_discount_rate,default_freight_amount)
            VALUES (?,?,?,?,?,?) RETURNING id
            """, UUID.class, tenantId, name.trim(), blankToNull(code), currency.toUpperCase(Locale.ROOT),
            orZero(discountRate), orZero(defaultFreightAmount));
    }

    /** Creates the product and, when requested, its new vendor in one transaction. */
    @Transactional
    public InlineProductResult addMarketplaceMappingProduct(UUID tenantId,String actorEmail,String name,String brand,
            String identifierType,String identifier,String accountSku,boolean expirationRequired,UUID vendorId,
            String newVendorName,String newVendorCode,String newVendorCurrency,BigDecimal unitCost){
        UUID selectedVendor=vendorId;String selectedVendorName=null;String offerCurrency="USD";
        if(selectedVendor==null&&newVendorName!=null&&!newVendorName.isBlank()){
            offerCurrency=newVendorCurrency==null||newVendorCurrency.isBlank()?"USD":newVendorCurrency.trim().toUpperCase(Locale.ROOT);
            selectedVendor=addVendor(tenantId,newVendorName,newVendorCode,
                offerCurrency,
                BigDecimal.ZERO,BigDecimal.ZERO);
            selectedVendorName=newVendorName.trim();
        }
        UUID productId;
        if(selectedVendor==null){
            productId=addAccountProduct(tenantId,actorEmail,name,brand,identifierType,identifier,accountSku,expirationRequired);
        }else{
            if(unitCost==null||unitCost.signum()<0)throw new IllegalArgumentException("Enter the current unit cost for this vendor.");
            if(selectedVendorName==null)selectedVendorName=jdbc.query("SELECT name FROM vendors WHERE tenant_id=? AND id=?",
                rs->rs.next()?rs.getString(1):null,tenantId,selectedVendor);
            if(selectedVendorName==null)throw new IllegalArgumentException("Choose an active vendor from this account.");
            offerCurrency=jdbc.query("SELECT currency FROM vendors WHERE tenant_id=? AND id=?",
                rs->rs.next()?rs.getString(1):"USD",tenantId,selectedVendor);
            productId=addImportedVendorProduct(tenantId,actorEmail,selectedVendor,accountSku,name,brand,
                identifierType,identifier,accountSku,expirationRequired);
            saveVendorOffer(tenantId,actorEmail,productId,selectedVendor,accountSku,unitCost,BigDecimal.ZERO,offerCurrency);
        }
        return new InlineProductResult(productId,selectedVendor,selectedVendorName);
    }

    @Transactional
    public void updateVendor(UUID tenantId, UUID vendorId, String name, String code, String currency,
            BigDecimal discountRate, BigDecimal defaultFreightAmount, String status) {
        setTenant(tenantId);
        validateVendor(name,currency,discountRate,defaultFreightAmount);
        int updated = jdbc.update("""
            UPDATE vendors SET name=?,vendor_code=?,currency=?,default_discount_rate=?,
                default_freight_amount=?,status=?,updated_at=now()
            WHERE tenant_id=? AND id=?
            """, name.trim(), blankToNull(code), currency.toUpperCase(Locale.ROOT), orZero(discountRate),
            orZero(defaultFreightAmount), "INACTIVE".equals(status) ? "INACTIVE" : "ACTIVE", tenantId, vendorId);
        if (updated == 0) throw new IllegalArgumentException("Vendor not found in this account.");
    }

    @Transactional
    public void saveVendorOffer(UUID tenantId, String actorEmail, UUID itemId, UUID vendorId,
            String vendorItemCode, BigDecimal listCost, BigDecimal discountRate, String currency) {
        saveVendorOffer(tenantId,actorEmail,itemId,vendorId,vendorItemCode,listCost,discountRate,currency,"MANUAL",null);
    }

    @Transactional
    public void updateImportedProductAttributes(UUID tenantId,UUID itemId,UUID vendorId,String vendorItemCode,
            String category,String unitOfMeasure,String packageSize,BigDecimal unitsPerCase,
            BigDecimal unitsOfSale,LocalDate effectiveFrom){
        setTenant(tenantId);
        String uom=unitOfMeasure==null||unitOfMeasure.isBlank()?null:unitOfMeasure.trim().toUpperCase(Locale.ROOT);
        BigDecimal pack=unitsPerCase==null?BigDecimal.ONE:unitsPerCase;
        jdbc.update("""
            UPDATE global_catalog_products SET
                category=coalesce(nullif(?,''),category),unit_of_measure=coalesce(?,unit_of_measure),
                package_size=coalesce(nullif(?,''),package_size),units_per_case=coalesce(?,units_per_case),updated_at=now()
            WHERE id=(SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?)
            """,category==null?"":category.trim(),uom,packageSize==null?"":packageSize.trim(),unitsPerCase,tenantId,itemId);
        VendorIdentity vendor=jdbc.query("SELECT name,vendor_code FROM vendors WHERE tenant_id=? AND id=?",
            rs->rs.next()?new VendorIdentity(rs.getString(1),rs.getString(2)):null,tenantId,vendorId);
        UUID globalId=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",UUID.class,tenantId,itemId);
        String vendorKey=normalizeVendorKey(vendor.code()==null?vendor.name():vendor.code()),code=normalizeVendorItemCode(vendorItemCode);
        UUID active=jdbc.query("SELECT id FROM global_product_packaging_versions WHERE vendor_key=? AND normalized_vendor_item_code=? AND status='ACTIVE' AND coalesce(package_size,'')=coalesce(?,'') AND unit_of_measure=? AND units_per_case=? AND coalesce(units_of_sale,1)=coalesce(?,1)",
            rs->rs.next()?rs.getObject(1,UUID.class):null,vendorKey,code,blankToNull(packageSize),uom==null?"EA":uom,pack,unitsOfSale);
        if(active==null){
            jdbc.update("UPDATE global_product_packaging_versions SET status='SUPERSEDED',effective_to=greatest(effective_from,coalesce(?,current_date)),updated_at=now() WHERE vendor_key=? AND normalized_vendor_item_code=? AND status='ACTIVE'",
                effectiveFrom,vendorKey,code);
            active=jdbc.queryForObject("""
                INSERT INTO global_product_packaging_versions(global_product_id,vendor_key,normalized_vendor_item_code,
                    package_size,unit_of_measure,units_per_case,units_of_sale,effective_from)
                VALUES(?,?,?,?,?,?,?,coalesce(?,current_date)) RETURNING id
                """,UUID.class,globalId,vendorKey,code,blankToNull(packageSize),uom==null?"EA":uom,pack,unitsOfSale,effectiveFrom);
        }
    }

    @Transactional
    public void updateDefaultFromInvoice(UUID tenantId,String actorEmail,UUID itemId,UUID vendorId,
            BigDecimal invoiceUnitCost,String currency,String sourceReference){
        setTenant(tenantId);
        String vendorItemCode=jdbc.query("""
            SELECT vendor_item_code FROM vendor_catalog_offers WHERE tenant_id=? AND vendor_id=?
              AND account_catalog_item_id=? AND effective_to IS NULL ORDER BY updated_at DESC LIMIT 1
            """,rs->rs.next()?rs.getString(1):null,tenantId,vendorId,itemId);
        saveVendorOffer(tenantId,actorEmail,itemId,vendorId,vendorItemCode,invoiceUnitCost,BigDecimal.ZERO,
            currency,"INVOICE",sourceReference);
    }

    @Transactional
    public void saveVendorOffer(UUID tenantId,String actorEmail,UUID itemId,UUID vendorId,
            String vendorItemCode,BigDecimal listCost,BigDecimal discountRate,String currency,
            String sourceType,String sourceReference,BigDecimal unitsOfSale,BigDecimal suggestedRetail,
            BigDecimal minimumQuantity,LocalDate effectiveFrom){
        saveVendorOffer(tenantId,actorEmail,itemId,vendorId,vendorItemCode,listCost,discountRate,currency,sourceType,sourceReference);
        setTenant(tenantId);
        jdbc.update("""
            UPDATE vendor_catalog_offers SET units_of_sale=?,suggested_retail=?,
                minimum_order_quantity=coalesce(?,minimum_order_quantity),
                effective_from=coalesce(?,effective_from),
                packaging_version_id=(SELECT version.id FROM global_product_packaging_versions version
                  JOIN global_product_vendor_codes code ON code.global_product_id=version.global_product_id
                  WHERE code.normalized_item_code=upper(regexp_replace(vendor_catalog_offers.vendor_item_code,'[^A-Za-z0-9]','','g'))
                    AND version.status='ACTIVE' ORDER BY version.updated_at DESC LIMIT 1),updated_at=now()
            WHERE tenant_id=? AND vendor_id=? AND account_catalog_item_id=? AND effective_to IS NULL
            """,unitsOfSale,suggestedRetail,minimumQuantity,effectiveFrom,tenantId,vendorId,itemId);
    }

    @Transactional
    public void saveVendorOffer(UUID tenantId, String actorEmail, UUID itemId, UUID vendorId,
            String vendorItemCode, BigDecimal listCost, BigDecimal discountRate, String currency,
            String sourceType, String sourceReference) {
        setTenant(tenantId);
        if(listCost==null||listCost.signum()<0)throw new IllegalArgumentException("Vendor price cannot be negative.");
        if(discountRate!=null&&(discountRate.signum()<0||discountRate.compareTo(BigDecimal.valueOf(100))>0))
            throw new IllegalArgumentException("Discount must be between 0 and 100 percent.");
        if(currency==null||!currency.trim().matches("[A-Za-z]{3}"))throw new IllegalArgumentException("Enter a three-letter currency code such as USD.");
        UUID actorId = actorId(actorEmail);
        UUID preferredVendor=jdbc.queryForObject("SELECT preferred_vendor_id FROM account_catalog_items WHERE tenant_id=? AND id=?",
            UUID.class,tenantId,itemId);
        boolean makeDefault=preferredVendor==null||preferredVendor.equals(vendorId);
        UUID offerId = jdbc.query("""
            SELECT id FROM vendor_catalog_offers
            WHERE tenant_id=? AND vendor_id=? AND account_catalog_item_id=? AND effective_from=current_date
            FOR UPDATE
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, vendorId, itemId);
        if (offerId == null) {
            jdbc.update("UPDATE vendor_catalog_offers SET effective_to=current_date,is_default=false WHERE tenant_id=? AND vendor_id=? AND account_catalog_item_id=? AND effective_to IS NULL",
                tenantId,vendorId,itemId);
            if(makeDefault) jdbc.update("UPDATE vendor_catalog_offers SET is_default=false WHERE tenant_id=? AND account_catalog_item_id=?",tenantId,itemId);
            offerId = jdbc.queryForObject("""
            INSERT INTO vendor_catalog_offers
                (tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,discount_rate,currency,is_default)
            VALUES (?,?,?,?,?,?,?,?) RETURNING id
            """, UUID.class, tenantId, vendorId, itemId, blankToNull(vendorItemCode), listCost,
            orZero(discountRate), currency.toUpperCase(Locale.ROOT),makeDefault);
        } else {
            if(makeDefault) jdbc.update("UPDATE vendor_catalog_offers SET is_default=false WHERE tenant_id=? AND account_catalog_item_id=? AND id<>?",tenantId,itemId,offerId);
            jdbc.update("""
                UPDATE vendor_catalog_offers SET vendor_item_code=?,list_cost=?,discount_rate=?,
                    currency=?,is_default=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """, blankToNull(vendorItemCode), listCost, orZero(discountRate),
                currency.toUpperCase(Locale.ROOT),makeDefault,tenantId,offerId);
        }
        BigDecimal netCost = listCost.multiply(BigDecimal.ONE.subtract(orZero(discountRate)
            .divide(BigDecimal.valueOf(100))));
        jdbc.update("""
            INSERT INTO vendor_cost_history
                (tenant_id,vendor_offer_id,source_type,source_reference,list_cost,discount_rate,net_unit_cost,currency,changed_by)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, tenantId, offerId, allowedCostSource(sourceType),blankToNull(sourceReference),listCost,orZero(discountRate),netCost,
            currency.toUpperCase(Locale.ROOT), actorId);
        if(preferredVendor==null) jdbc.update("UPDATE account_catalog_items SET preferred_vendor_id=?,updated_at=now() WHERE tenant_id=? AND id=?",
            vendorId,tenantId,itemId);
        if(vendorItemCode!=null&&!vendorItemCode.isBlank()){
            VendorIdentity vendor=jdbc.query("SELECT name,vendor_code FROM vendors WHERE tenant_id=? AND id=?",
                rs->rs.next()?new VendorIdentity(rs.getString(1),rs.getString(2)):null,tenantId,vendorId);
            UUID globalId=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",
                UUID.class,tenantId,itemId);
            if(vendor!=null&&globalId!=null)saveGlobalVendorCode(globalId,tenantId,vendor,vendorItemCode);
        }
    }

    private void saveGlobalVendorCode(UUID globalId,UUID tenantId,VendorIdentity vendor,String itemCode){
        String vendorKey=normalizeVendorKey(vendor.code()==null?vendor.name():vendor.code());
        String normalizedCode=normalizeVendorItemCode(itemCode);
        UUID existing=jdbc.query("""
            SELECT global_product_id FROM global_product_vendor_codes
            WHERE vendor_key=? AND normalized_item_code=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,vendorKey,normalizedCode);
        if(existing!=null&&!existing.equals(globalId))throw new IllegalArgumentException(
            "This vendor item code already belongs to another product. Review the product match before saving.");
        jdbc.update("""
            INSERT INTO global_product_vendor_codes
                (global_product_id,vendor_key,vendor_name,vendor_item_code,normalized_item_code,source_tenant_id)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (vendor_key,normalized_item_code) DO UPDATE SET
                vendor_name=EXCLUDED.vendor_name,vendor_item_code=EXCLUDED.vendor_item_code,
                source_tenant_id=EXCLUDED.source_tenant_id,last_seen_at=now()
            """,globalId,vendorKey,vendor.name(),itemCode.trim(),normalizedCode,tenantId);
    }

    @Transactional(readOnly=true)
    public List<LocationView> listLocations(UUID tenantId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT id,code,name,status FROM warehouse_locations
            WHERE tenant_id=? ORDER BY status DESC,upper(code),lower(name)
            """,(rs,row)->new LocationView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),tenantId);
    }

    @Transactional
    public void changeLocation(UUID tenantId,UUID id,String code,String name,boolean delete){
        setTenant(tenantId);
        var rows=jdbc.query("SELECT code FROM warehouse_locations WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs,n)->rs.getString(1),tenantId,id);
        if(rows.isEmpty())throw new IllegalArgumentException("This location is no longer available in this account.");
        var reasons=new java.util.ArrayList<String>();
        if("MAIN".equalsIgnoreCase(rows.getFirst()))reasons.add("MAIN is the platform's fallback location for receipts and inventory records");
        String[][] sources={{"account_catalog_item_locations","location_id","catalogue location assignments"},
            {"inventory_ledger_entries","location_id","inventory ledger records (including historical movements)"},
            {"receiving_line_receipts","location_id","receiving records"},
            {"order_inventory_reservations","location_id","order reservation records (including completed orders)"},
            {"buy_shipping_shipment_items","inventory_location_id","shipment item records"}};
        for(var source:sources){
            Long count=jdbc.queryForObject("SELECT count(*) FROM "+source[0]+" WHERE tenant_id=? AND "+source[1]+"=?",Long.class,tenantId,id);
            if(count!=null&&count>0)reasons.add(count+" "+source[2]);
        }
        Long uploads=jdbc.queryForObject("""
            SELECT count(DISTINCT i.id) FROM physical_count_imports i
            JOIN physical_count_import_rows r ON r.tenant_id=i.tenant_id AND r.physical_count_import_id=i.id
            WHERE i.tenant_id=? AND i.status IN ('STAGED','APPLYING')
              AND upper(trim(r.source_data->>(i.column_mapping->>'location')))=upper(?)
            """,Long.class,tenantId,rows.getFirst());
        if(uploads!=null&&uploads>0)reasons.add(uploads+" physical-count uploads awaiting completion; finish or cancel them first");
        if(!reasons.isEmpty())throw new IllegalArgumentException("This location cannot be renamed or deleted because it is used by: "+String.join("; ",reasons)+". Historical records must retain their location. Create a new location for future use; changing a product default does not move existing stock.");
        if(delete){jdbc.update("DELETE FROM warehouse_locations WHERE tenant_id=? AND id=?",tenantId,id);return;}
        String cleanCode=code==null?"":code.trim().toUpperCase(Locale.ROOT),cleanName=name==null?"":name.trim();
        if(!cleanCode.matches("[A-Z0-9][A-Z0-9._/-]{0,79}"))throw new IllegalArgumentException("Use a location code of up to 80 letters, numbers, dots, slashes or hyphens.");
        if(cleanName.isBlank()||cleanName.length()>160)throw new IllegalArgumentException("Enter a location name of up to 160 characters.");
        if(jdbc.queryForObject("SELECT count(*) FROM warehouse_locations WHERE tenant_id=? AND upper(code)=? AND id<>?",Long.class,tenantId,cleanCode,id)>0)
            throw new IllegalArgumentException("That location code already exists in this account.");
        jdbc.update("UPDATE warehouse_locations SET code=?,name=? WHERE tenant_id=? AND id=?",cleanCode,cleanName,tenantId,id);
    }

    @Transactional
    public UUID addLocation(UUID tenantId,String code,String name){
        setTenant(tenantId);
        String cleanCode=code==null?"":code.trim().toUpperCase(Locale.ROOT);
        String cleanName=name==null?"":name.trim();
        if(!cleanCode.matches("[A-Z0-9][A-Z0-9._/-]{0,79}"))
            throw new IllegalArgumentException("Use a short location code such as A-01 or BACKROOM.");
        if(cleanName.isBlank()||cleanName.length()>160)throw new IllegalArgumentException("Enter a location name.");
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM warehouse_locations WHERE tenant_id=? AND upper(code)=?",Integer.class,tenantId,cleanCode);
        if(exists!=null&&exists>0)throw new IllegalArgumentException("That location code already exists in this account.");
        return jdbc.queryForObject("INSERT INTO warehouse_locations(tenant_id,code,name) VALUES (?,?,?) RETURNING id",
            UUID.class,tenantId,cleanCode,cleanName);
    }

    @Transactional
    public void setDefaultLocation(UUID tenantId,UUID itemId,UUID locationId){
        setTenant(tenantId);
        Integer valid=jdbc.queryForObject("""
            SELECT count(*) FROM account_catalog_items item CROSS JOIN warehouse_locations location
            WHERE item.tenant_id=? AND item.id=? AND location.tenant_id=item.tenant_id
              AND location.id=? AND location.status='ACTIVE'
            """,Integer.class,tenantId,itemId,locationId);
        if(valid==null||valid!=1)throw new IllegalArgumentException("Choose an active location from this account.");
        jdbc.update("UPDATE account_catalog_item_locations SET is_default=false WHERE tenant_id=? AND account_catalog_item_id=? AND is_default",
            tenantId,itemId);
        jdbc.update("""
            INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
            VALUES (?,?,?,true)
            ON CONFLICT (tenant_id,account_catalog_item_id,location_id)
            DO UPDATE SET is_default=true,updated_at=now()
            """,tenantId,itemId,locationId);
    }

    private UUID actorId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)", UUID.class, email);
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static String normalizeIdentifier(String type, String value) {
        if (value == null) return "";
        String cleaned = value.trim().toUpperCase(Locale.ROOT);
        return SetOfNumericIdentifiers.contains(type.toUpperCase(Locale.ROOT)) ? cleaned.replaceAll("[^0-9]", "") : cleaned;
    }

    private static String allowedCostSource(String value) {
        return java.util.Set.of("MANUAL","CATALOG_IMPORT","INVOICE","PACKING_LIST","RECEIVING").contains(value)?value:"MANUAL";
    }

    private static void validateVendor(String name,String currency,BigDecimal discount,BigDecimal freight){
        if(name==null||name.isBlank())throw new IllegalArgumentException("Enter the vendor name.");
        if(currency==null||!currency.trim().matches("[A-Za-z]{3}"))throw new IllegalArgumentException("Enter a three-letter currency code such as USD.");
        if(discount!=null&&(discount.signum()<0||discount.compareTo(BigDecimal.valueOf(100))>0))throw new IllegalArgumentException("Default discount must be between 0 and 100 percent.");
        if(freight!=null&&freight.signum()<0)throw new IllegalArgumentException("Default freight amount cannot be negative.");
    }

    private static final java.util.Set<String> SetOfNumericIdentifiers = java.util.Set.of("UPC", "EAN", "GTIN", "ISBN");
    private static String normalizeVendorKey(String value){return value==null?"":value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");}
    private static String normalizeVendorItemCode(String value){
        String normalized=value==null?"":value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        return normalized.matches("\\d+")?normalized.replaceFirst("^0+(?!$)",""):normalized;
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static BigDecimal orZero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private record VendorIdentity(String name,String code){}
}
