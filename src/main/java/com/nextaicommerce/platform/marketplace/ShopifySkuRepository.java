package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.shopify.ShopifyCatalogClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Provider-specific listing projection. Mappings reuse account-owned components; quantities are never written here. */
@Repository
public class ShopifySkuRepository {
    private final JdbcTemplate jdbc;private final boolean mappingEnabled;private final ObjectMapper json=new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.shopify.ShopifyOrderInventory orderInventory;
    public ShopifySkuRepository(JdbcTemplate jdbc,@Value("${app.shopify.catalog.mapping-enabled:false}") boolean mappingEnabled){this.jdbc=jdbc;this.mappingEnabled=mappingEnabled;}
    void tenant(UUID id){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());}
    public record Row(UUID id,String gid,String productGid,String inventoryGid,String sku,String title,String variantTitle,String options,String barcodes,String productStatus,BigDecimal price,String currency,String image,String storefront,String shop,String revision,Instant observed,boolean mapped,boolean blocked,boolean mappingNeedsReview){
        public Row(UUID id,String gid,String productGid,String inventoryGid,String sku,String title,String variantTitle,String options,String barcodes,String productStatus,BigDecimal price,String currency,String image,String storefront,String shop,String revision,Instant observed,boolean mapped,boolean blocked){this(id,gid,productGid,inventoryGid,sku,title,variantTitle,options,barcodes,productStatus,price,currency,image,storefront,shop,revision,observed,mapped,blocked,false);}
        public String skuLabel(){return sku==null||sku.isBlank()?"No SKU":sku;}
        public String optionsLabel(){var j=new ObjectMapper();var labels=new ArrayList<String>();for(var o:j.readTree(options)){String name=o.path("name").asText(),value=o.path("value").asText();if("Title".equalsIgnoreCase(name)&&"Default Title".equalsIgnoreCase(value))continue;labels.add(name+": "+value);}return String.join(" · ",labels);}
        public String adminUrl(){return admin(shop,productGid,gid);}
        public String subjectKey(){return "SHOPIFY:"+id;}
        public String mappingLabel(){return mapped?(mappingNeedsReview?"Mapped · source changed; review":"Mapped"):blocked?"Needs mapping · automatic matching blocked":"Needs mapping";}
    }
    public record Page(List<Row> rows,long total,int page,int size){public int totalPages(){return Math.max(1,(int)Math.ceil(total/(double)size));}public int displayPage(){return page+1;}public boolean hasPrevious(){return page>0;}public boolean hasNext(){return page+1<totalPages();}}
    public static String admin(String shop,String product,String variant){
        if(shop==null||!shop.matches("[a-z0-9][a-z0-9-]*\\.myshopify\\.com")||product==null||!product.matches("gid://shopify/Product/[0-9]+")||variant==null||!variant.matches("gid://shopify/ProductVariant/[0-9]+"))return null;
        return "https://"+shop+"/admin/products/"+product.substring(product.lastIndexOf('/')+1)+"/variants/"+variant.substring(variant.lastIndexOf('/')+1);
    }
    private static final String FROM="""
        FROM shopify_variants v JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
        JOIN marketplace_connections c ON c.tenant_id=v.tenant_id AND c.id=v.marketplace_connection_id AND c.channel='SHOPIFY'
        LEFT JOIN marketplace_sku_mappings m ON m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id AND m.shopify_variant_id=v.id
        WHERE v.tenant_id=? AND v.marketplace_connection_id=?
        """;
    private static final String FIELDS="SELECT v.id,v.variant_gid,p.product_gid,v.inventory_item_gid,v.seller_sku,p.title,v.title,v.selected_options::text,v.barcodes::text,p.product_status,v.price,v.currency,coalesce(v.image_url,p.image_url),p.storefront_url,c.marketplace_identifier,v.observation_hash,v.observed_at,coalesce(m.status='ACTIVE',false),coalesce(m.auto_map_blocked,false),coalesce(m.status='ACTIVE' AND m.shopify_mapping_hash IS DISTINCT FROM v.mapping_hash,false) ";
    private Row row(ResultSet r,int n)throws SQLException{return new Row(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getBigDecimal(11),r.getString(12),r.getString(13),ShopifyCatalogClient.safePublicUrl(r.getString(14)),r.getString(15),r.getString(16),r.getTimestamp(17).toInstant(),r.getBoolean(18),r.getBoolean(19),r.getBoolean(20));}
    @Transactional(readOnly=true) public Row find(UUID tenant,UUID connection,UUID variant){tenant(tenant);return jdbc.query(FIELDS+FROM+" AND v.id=?",this::row,tenant,connection,variant).stream().findFirst().orElseThrow(()->new IllegalArgumentException("Shopify variant is unavailable in this store."));}
    @Transactional(readOnly=true)
    public Page list(UUID tenant,UUID connection,String query,String status,String sort,String direction,int page,int size){
        tenant(tenant);if(query==null||query.length()>250||!Set.of("ALL","ACTIVE","DRAFT","ARCHIVED","UNLISTED","UNMAPPED").contains(status))throw new IllegalArgumentException("Unsupported Shopify listing filter.");
        var args=new ArrayList<Object>(List.of(tenant,connection));String where="";
        if(!status.equals("ALL")){if(status.equals("UNMAPPED"))where+=" AND coalesce(m.status,'INACTIVE')<>'ACTIVE'";else{where+=" AND p.product_status=?";args.add(status);}}
        if(!query.isBlank()){
            where+="""
                AND (concat_ws(' ',v.seller_sku,p.title,v.title,v.variant_gid,p.product_gid,v.barcodes::text,v.selected_options::text) ILIKE ? ESCAPE '!' OR EXISTS(
                SELECT 1 FROM marketplace_sku_mapping_components part JOIN account_catalog_items item ON item.tenant_id=part.tenant_id AND item.id=part.account_catalog_item_id
                LEFT JOIN vendor_catalog_offers offer ON offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id AND offer.effective_to IS NULL
                WHERE part.tenant_id=m.tenant_id AND part.marketplace_sku_mapping_id=m.id AND m.status='ACTIVE' AND concat_ws(' ',item.account_sku,offer.vendor_item_code) ILIKE ? ESCAPE '!'))
                """;String text="%"+query.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(text);args.add(text);
        }
        String order=Map.of("listing","p.title","sku","v.seller_sku","status","p.product_status","price","v.price","date","v.observed_at","mapping","m.status").get(sort);
        if(order==null)throw new IllegalArgumentException("Unsupported Shopify sort column.");
        long total=jdbc.queryForObject("SELECT count(*) "+FROM+where,Long.class,args.toArray());size=Math.max(1,Math.min(10000,size));page=Math.max(0,Math.min(page,Math.max(0,(int)((total-1)/size))));
        args.add(size);args.add((long)page*size);
        String sql=FIELDS+FROM+where+" ORDER BY "+order+("desc".equals(direction)?" DESC":" ASC")+" NULLS LAST,v.variant_gid LIMIT ? OFFSET ?";
        var rows=jdbc.query(sql,this::row,args.toArray());
        return new Page(rows,total,page,size);
    }
    @Transactional(readOnly=true)
    public Map<String,Long> counts(UUID tenant,UUID connection){tenant(tenant);var result=new LinkedHashMap<String,Long>();jdbc.query("SELECT p.product_status,count(*) FROM shopify_variants v JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id WHERE v.tenant_id=? AND v.marketplace_connection_id=? GROUP BY p.product_status",r->{result.put(r.getString(1),r.getLong(2));},tenant,connection);result.put("ALL",result.values().stream().mapToLong(Long::longValue).sum());long mapped=jdbc.queryForObject("SELECT count(*) "+FROM+" AND m.status='ACTIVE'",Long.class,tenant,connection);result.put("MAPPED",mapped);result.put("UNMAPPED",result.get("ALL")-mapped);return result;}
    @Transactional(readOnly=true)
    public List<MarketplaceSkuRepository.MappingComponentView> components(UUID tenant,UUID connection,UUID variant){return componentBatch(tenant,connection,List.of(variant)).getOrDefault(variant,List.of());}
    @Transactional(readOnly=true)
    public Map<UUID,List<MarketplaceSkuRepository.MappingComponentView>> componentBatch(UUID tenant,UUID connection,List<UUID> variants){tenant(tenant);if(variants.isEmpty())return Map.of();var args=new ArrayList<Object>(List.of(tenant,connection));args.addAll(variants);var result=new LinkedHashMap<UUID,List<MarketplaceSkuRepository.MappingComponentView>>();jdbc.query("""
        SELECT m.shopify_variant_id,i.id,coalesce(i.display_name,p.canonical_name),o.vendor_item_code,i.account_sku,c.quantity,p.brand
        FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
        JOIN account_catalog_items i ON i.tenant_id=c.tenant_id AND i.id=c.account_catalog_item_id JOIN global_catalog_products p ON p.id=i.global_product_id
        LEFT JOIN LATERAL(SELECT vendor_item_code FROM vendor_catalog_offers o WHERE o.tenant_id=i.tenant_id AND o.account_catalog_item_id=i.id AND o.effective_to IS NULL ORDER BY o.is_default DESC,o.updated_at DESC LIMIT 1)o ON true
        WHERE m.tenant_id=? AND m.marketplace_connection_id=? AND m.status='ACTIVE' AND m.shopify_variant_id IN (
        """+String.join(",",Collections.nCopies(variants.size(),"?"))+") ORDER BY m.shopify_variant_id,c.sort_order,c.id",r->{result.computeIfAbsent(r.getObject(1,UUID.class),key->new ArrayList<>()).add(new MarketplaceSkuRepository.MappingComponentView(r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getBigDecimal(6),r.getString(7)));},args.toArray());return result;}
    @Transactional
    public void save(UUID tenant,UUID connection,UUID variant,String revision,List<String> products,List<String> quantities,Runnable authorize){
        authorize.run();tenant(tenant);var current=jdbc.queryForList("SELECT observation_hash FROM shopify_variants WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",String.class,tenant,connection,variant);
        if(current.isEmpty()||!current.getFirst().equals(revision))throw new IllegalArgumentException("This Shopify variant changed. Refresh and review the mapping.");
        var parts=validate(products,quantities);for(UUID id:parts.keySet().stream().sorted().toList())if(jdbc.queryForList("SELECT id FROM account_catalog_items WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE",UUID.class,tenant,id).isEmpty())throw new IllegalArgumentException("Choose products from this account's active catalogue.");
        authorize.run();tenant(tenant);write(tenant,connection,variant,parts,"MANUAL");
        if(orderInventory!=null)orderInventory.reconcileTenant(tenant);
    }
    static Map<UUID,BigDecimal> validate(List<String> products,List<String> quantities){
        if(products==null||quantities==null||products.isEmpty()||products.size()>20||products.size()!=quantities.size())throw new IllegalArgumentException("Choose one to twenty account products and quantities.");
        var parts=new LinkedHashMap<UUID,BigDecimal>();
        try{for(int i=0;i<products.size();i++){var value=new BigDecimal(quantities.get(i));if(value.signum()<=0||value.stripTrailingZeros().scale()>0||value.compareTo(BigDecimal.valueOf(10000))>0)throw new IllegalArgumentException();parts.merge(UUID.fromString(products.get(i)),value,BigDecimal::add);}}catch(RuntimeException e){throw new IllegalArgumentException("Choose account products and whole quantities between 1 and 10,000.");}
        if(parts.values().stream().anyMatch(v->v.compareTo(BigDecimal.valueOf(10000))>0))throw new IllegalArgumentException("Combined component quantity exceeds 10,000.");return parts;
    }
    private void write(UUID tenant,UUID connection,UUID variant,Map<UUID,BigDecimal> parts,String source){
        var first=parts.entrySet().iterator().next();
        var ids=jdbc.query("""
            INSERT INTO marketplace_sku_mappings(tenant_id,marketplace_connection_id,shopify_variant_id,marketplace_sku,asin,account_catalog_item_id,quantity_per_marketplace_unit,status,mapping_source,auto_map_blocked,shopify_mapping_hash)
            VALUES(?,?,?,NULL,NULL,?,?,'ACTIVE',?,false,(SELECT mapping_hash FROM shopify_variants WHERE tenant_id=? AND marketplace_connection_id=? AND id=?))
            ON CONFLICT(tenant_id,marketplace_connection_id,shopify_variant_id) WHERE shopify_variant_id IS NOT NULL DO UPDATE SET account_catalog_item_id=EXCLUDED.account_catalog_item_id,
            quantity_per_marketplace_unit=EXCLUDED.quantity_per_marketplace_unit,status='ACTIVE',mapping_source=EXCLUDED.mapping_source,auto_map_blocked=false,shopify_mapping_hash=EXCLUDED.shopify_mapping_hash,updated_at=now()
            WHERE ?='MANUAL' OR (marketplace_sku_mappings.status='INACTIVE' AND marketplace_sku_mappings.mapping_source='AUTO' AND NOT marketplace_sku_mappings.auto_map_blocked)
            RETURNING id
            """,(r,n)->r.getObject(1,UUID.class),tenant,connection,variant,first.getKey(),first.getValue(),source,tenant,connection,variant,source);
        if(ids.isEmpty())return;UUID mapping=ids.getFirst();jdbc.update("DELETE FROM marketplace_sku_mapping_components WHERE tenant_id=? AND marketplace_sku_mapping_id=?",tenant,mapping);int order=0;for(var part:parts.entrySet())jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order) VALUES(?,?,?,?,?)",tenant,mapping,part.getKey(),part.getValue(),order++);
    }
    @Transactional public void clear(UUID tenant,UUID connection,UUID variant,Runnable authorize){authorize.run();tenant(tenant);if(jdbc.update("UPDATE marketplace_sku_mappings SET status='INACTIVE',mapping_source='MANUAL',auto_map_blocked=true WHERE tenant_id=? AND marketplace_connection_id=? AND shopify_variant_id=?",tenant,connection,variant)==0)throw new IllegalArgumentException("This variant has no saved mapping.");}
    public record MappingCandidate(UUID variant,String sku,Map<UUID,BigDecimal> components,String reason){}
    @Transactional public void autoMap(UUID tenant,UUID connection,UUID run){
        if(!mappingEnabled)return;tenant(tenant);com.nextaicommerce.platform.catalog.CatalogIdentity.lock(jdbc);
        // Resolve the whole account-scoped plan before any write. Every write still preserves manual/block state.
        var plan=previewAutoMap(tenant,connection,run);
        var owned=plan.stream().flatMap(c->c.components().keySet().stream()).distinct().sorted().toList();
        for(UUID id:owned)if(jdbc.queryForList("SELECT id FROM account_catalog_items WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE",UUID.class,tenant,id).size()!=1)throw new IllegalArgumentException("Account catalogue changed during matching. Existing mappings were kept.");
        for(var candidate:plan)if(!candidate.components().isEmpty())write(tenant,connection,candidate.variant(),candidate.components(),"AUTO");
    }
    @Transactional(readOnly=true) public List<MappingCandidate> previewAutoMap(UUID tenant,UUID connection,UUID run){
        tenant(tenant);
        var items=new LinkedHashMap<UUID,ShopifySkuMatcher.Item>();
        var data=jdbc.queryForList("""
            SELECT i.id,coalesce(i.display_name,p.canonical_name) title,i.account_sku,o.vendor_item_code,g.identifier_value,p.unit_of_measure
            FROM account_catalog_items i JOIN global_catalog_products p ON p.id=i.global_product_id
            LEFT JOIN vendor_catalog_offers o ON o.tenant_id=i.tenant_id AND o.account_catalog_item_id=i.id AND o.effective_to IS NULL
            LEFT JOIN global_product_identifiers g ON g.global_product_id=p.id AND upper(g.identifier_type) IN ('UPC','EAN','GTIN')
            WHERE i.tenant_id=? AND i.status='ACTIVE' AND p.status='ACTIVE'
            """,tenant);
        for(var r:data){UUID id=(UUID)r.get("id");var item=items.computeIfAbsent(id,k->new ShopifySkuMatcher.Item(id,(String)r.get("title"),new HashSet<>(),new HashSet<>()));for(String field:List.of("account_sku","vendor_item_code"))if(r.get(field) instanceof String code&&!code.isBlank())item.codes().add(code);String gtin=BarcodePackRules.gtin((String)r.get("identifier_value"));if(gtin!=null&&Set.of("EA","EACH").contains(Objects.toString(r.get("unit_of_measure"),"").toUpperCase(Locale.ROOT)))item.gtins().add(gtin);}
        var variants=jdbc.queryForList("""
            SELECT v.id,v.seller_sku,concat_ws(' ',p.title,v.title,v.selected_options::text) title,v.barcodes::text,v.barcodes_complete,
            (SELECT count(*)>1 FROM shopify_variants other WHERE other.tenant_id=v.tenant_id AND other.marketplace_connection_id=v.marketplace_connection_id AND other.seller_sku=v.seller_sku) duplicate
            FROM shopify_variants v JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
            WHERE v.tenant_id=? AND v.marketplace_connection_id=? AND v.last_seen_run=? AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mappings m WHERE m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id AND m.shopify_variant_id=v.id AND (m.status='ACTIVE' OR m.auto_map_blocked OR m.mapping_source='MANUAL'))
            """,tenant,connection,run);
        var plan=new ArrayList<MappingCandidate>();for(var r:variants){var barcodes=new ArrayList<String>();for(var b:json.readTree(r.get("barcodes").toString()))barcodes.add(b.path("value").asText());var match=ShopifySkuMatcher.match((String)r.get("seller_sku"),(String)r.get("title"),barcodes,Boolean.TRUE.equals(r.get("barcodes_complete")),Boolean.TRUE.equals(r.get("duplicate")),List.copyOf(items.values()));plan.add(new MappingCandidate((UUID)r.get("id"),(String)r.get("seller_sku"),match.components(),match.state()));}return List.copyOf(plan);
    }
}
