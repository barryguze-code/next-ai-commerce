package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Slowly enriches Amazon listings with their official MAIN catalogue image. */
@Component
public class AmazonListingImageScheduler {
    private static final Logger log=LoggerFactory.getLogger(AmazonListingImageScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AmazonSpApiClient amazon;
    private final boolean enabled;
    private int tenantCursor;

    AmazonListingImageScheduler(JdbcTemplate jdbc,TransactionTemplate transactions,AmazonSpApiClient amazon,
            @Value("${app.amazon.catalog-images-enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.transactions=transactions;this.amazon=amazon;this.enabled=enabled;
    }

    @Scheduled(fixedDelayString="${app.amazon.catalog-image-delay-ms:2500}")
    public void enrichOneListingPerAccount(){
        if(!enabled)return;
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",
            (rs,row)->rs.getObject(1,UUID.class));
        if(tenants.isEmpty())return;
        for(int offset=0;offset<tenants.size();offset++){
            int index=Math.floorMod(tenantCursor+offset,tenants.size());UUID tenantId=tenants.get(index);
            Candidate candidate=transactions.execute(status->claim(tenantId));
            if(candidate==null)continue;
            tenantCursor=(index+1)%tenants.size();
            try{
                log.info("Amazon image enrichment: requesting MAIN image | ASIN={} | API=Catalog Items 2022-04-01 | cadence=one missing image every 2.5 seconds",
                    candidate.asin());
                JsonNode response=amazon.get(candidate.tenantId(),candidate.connectionId(),
                    "/catalog/2022-04-01/items/"+encode(candidate.asin())+"?marketplaceIds="+
                    encode(candidate.marketplaceId())+"&includedData=images").json();
                String image=mainImage(response,candidate.marketplaceId());
                transactions.executeWithoutResult(status->{setTenant(candidate.tenantId());jdbc.update("""
                    UPDATE amazon_listings SET image_url=?,image_source_url=?,image_status=?,image_attempts=0,
                        image_next_attempt_at=NULL,image_failure_code=NULL,image_failure_message=NULL,
                        image_checked_at=now(),updated_at=now()
                    WHERE tenant_id=? AND marketplace_connection_id=? AND asin=?
                    """,image,image,image==null?"NO_IMAGE":"AVAILABLE",candidate.tenantId(),candidate.connectionId(),candidate.asin());});
                log.info("Amazon image enrichment complete: ASIN={} | result={}",candidate.asin(),
                    image!=null?"main image saved; this SKU will not be requested again":"Amazon returned no image");
            }catch(RuntimeException ex){
                if(isNotFound(ex)){
                    transactions.executeWithoutResult(status->{setTenant(candidate.tenantId());jdbc.update("""
                        UPDATE amazon_listings SET platform_status='REMOVED',amazon_removed_at=now(),
                            image_status='NOT_FOUND',image_next_attempt_at=NULL,image_failure_code='NOT_FOUND',
                            image_failure_message=?,image_checked_at=now(),updated_at=now()
                        WHERE tenant_id=? AND marketplace_connection_id=? AND asin=? AND image_url IS NULL
                        """,safeFailure(ex),candidate.tenantId(),candidate.connectionId(),candidate.asin());});
                    log.info("Amazon image enrichment: ASIN={} not found; listing marked Removed and will not be requested again",
                        candidate.asin());
                }else{
                    transactions.executeWithoutResult(status->{setTenant(candidate.tenantId());jdbc.update("""
                        UPDATE amazon_listings SET image_status='RETRY',image_attempts=image_attempts+1,
                            image_next_attempt_at=now()+(CASE WHEN image_attempts<1 THEN interval '1 hour'
                                WHEN image_attempts<2 THEN interval '6 hours' WHEN image_attempts<3 THEN interval '1 day'
                                ELSE interval '7 days' END),image_failure_code='TEMPORARY',image_failure_message=?,
                            image_checked_at=now(),updated_at=now()
                        WHERE tenant_id=? AND marketplace_connection_id=? AND asin=? AND image_url IS NULL
                        """,safeFailure(ex),candidate.tenantId(),candidate.connectionId(),candidate.asin());});
                    log.warn("Amazon catalogue image retry scheduled tenant={} connection={} asin={}: {}",candidate.tenantId(),
                        candidate.connectionId(),candidate.asin(),safeFailure(ex));
                }
            }
            return;
        }
        tenantCursor=(tenantCursor+1)%tenants.size();
    }

    private Candidate claim(UUID tenantId){
        setTenant(tenantId);
        List<Candidate> candidates=jdbc.query("""
            SELECT listing.tenant_id,listing.marketplace_connection_id,listing.marketplace_id,listing.asin
            FROM amazon_listings listing
            JOIN marketplace_connections connection ON connection.tenant_id=listing.tenant_id
                AND connection.id=listing.marketplace_connection_id
            WHERE listing.tenant_id=? AND connection.channel='AMAZON' AND connection.status='ACTIVE'
              AND listing.asin IS NOT NULL AND listing.asin<>'' AND listing.image_url IS NULL
              AND listing.platform_status='VISIBLE' AND listing.image_status IN ('PENDING','RETRY')
              AND (listing.image_next_attempt_at IS NULL OR listing.image_next_attempt_at<=now())
            ORDER BY CASE WHEN EXISTS (
                SELECT 1 FROM purchase_order_items item
                JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
                JOIN receiving_sessions session ON session.tenant_id=po.tenant_id AND session.id=po.receiving_session_id
                JOIN account_catalog_items account_item ON account_item.tenant_id=item.tenant_id
                    AND account_item.id=item.account_catalog_item_id
                LEFT JOIN global_product_identifiers identifier ON identifier.global_product_id=account_item.global_product_id
                WHERE item.tenant_id=listing.tenant_id AND session.status NOT IN ('POSTED','CANCELLED')
                  AND (upper(identifier.identifier_value)=upper(coalesce(listing.product_id,''))
                    OR EXISTS (SELECT 1 FROM marketplace_sku_mappings mapping
                      WHERE mapping.tenant_id=item.tenant_id
                        AND mapping.account_catalog_item_id=account_item.id
                        AND mapping.marketplace_connection_id=listing.marketplace_connection_id
                        AND (upper(mapping.marketplace_sku)=upper(listing.seller_sku)
                          OR upper(coalesce(mapping.asin,''))=upper(coalesce(listing.asin,'')))))
            ) THEN 0 ELSE 1 END,listing.last_seen_at DESC
            FOR UPDATE OF listing SKIP LOCKED LIMIT 1
            """,(rs,row)->new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),tenantId);
        if(candidates.isEmpty())return null;
        Candidate candidate=candidates.getFirst();
        jdbc.update("UPDATE amazon_listings SET image_checked_at=now(),image_next_attempt_at=now()+interval '10 minutes' WHERE tenant_id=? AND marketplace_connection_id=? AND asin=?",
            candidate.tenantId(),candidate.connectionId(),candidate.asin());
        return candidate;
    }

    static String mainImage(JsonNode root,String marketplaceId){
        JsonNode groups=root.path("images");
        if(!groups.isArray())return null;
        String fallback=null;
        for(JsonNode group:groups){
            if(marketplaceId!=null&&!marketplaceId.equals(group.path("marketplaceId").asText()))continue;
            JsonNode images=group.path("images");if(!images.isArray())continue;
            for(JsonNode image:images){
                String link=image.path("link").asText(null);if(link==null||link.isBlank())continue;
                if(fallback==null)fallback=link;
                if("MAIN".equalsIgnoreCase(image.path("variant").asText()))return link;
            }
        }
        return fallback;
    }

    static boolean isNotFound(Throwable failure){
        for(Throwable current=failure;current!=null;current=current.getCause()){
            if(current instanceof AmazonSpApiClient.AmazonApiException api && api.status()==404)return true;
            String message=current.getMessage();
            if(message!=null){String normalized=message.toUpperCase(Locale.ROOT).replaceAll("\\s+","");
                if(normalized.contains("\"CODE\":\"NOT_FOUND\"")||normalized.contains("'CODE':'NOT_FOUND'"))return true;
            }
        }
        return false;
    }

    private static String safeFailure(Throwable failure){
        String message=failure.getMessage();if(message==null||message.isBlank())return failure.getClass().getSimpleName();
        return message.length()<=480?message:message.substring(0,480);
    }

    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private record Candidate(UUID tenantId,UUID connectionId,String marketplaceId,String asin){}
}
