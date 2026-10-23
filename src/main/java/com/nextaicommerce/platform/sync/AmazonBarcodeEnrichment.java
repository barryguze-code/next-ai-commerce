package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.marketplace.BarcodePackRules;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded Catalog Items reads, independent of whether the listing already has an image. */
@Component
public class AmazonBarcodeEnrichment {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AmazonSpApiClient amazon;
 private final MarketplaceSkuAutoMapper mapper;private final boolean enabled;
 public AmazonBarcodeEnrichment(JdbcTemplate jdbc,TransactionTemplate tx,AmazonSpApiClient amazon,
   MarketplaceSkuAutoMapper mapper,@Value("${app.amazon.catalog-barcodes-enabled:true}") boolean enabled){
  this.jdbc=jdbc;this.tx=tx;this.amazon=amazon;this.mapper=mapper;this.enabled=enabled;
 }
 @Scheduled(initialDelayString="${app.amazon.catalog-barcodes-initial-delay-ms:30000}",fixedDelayString="${app.amazon.catalog-barcodes-delay-ms:5000}")
 public void enrich(){
  if(!enabled)return;
  for(UUID tenant:jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(r,n)->r.getObject(1,UUID.class))){
   Candidate candidate=tx.execute(s->{scope(tenant);
    var rows=jdbc.query("""
      SELECT l.marketplace_connection_id,l.marketplace_id,l.asin FROM amazon_listings l
      JOIN marketplace_connections c ON c.tenant_id=l.tenant_id AND c.id=l.marketplace_connection_id
      WHERE l.tenant_id=? AND c.channel='AMAZON' AND c.status='ACTIVE' AND l.platform_status='VISIBLE'
        AND nullif(l.asin,'') IS NOT NULL AND nullif(l.marketplace_id,'') IS NOT NULL
        AND (l.catalog_barcodes_checked_at IS NULL OR l.catalog_barcodes_asin IS DISTINCT FROM l.asin
          OR l.catalog_barcodes_checked_at<now()-interval '30 days')
        AND (l.catalog_barcodes_retry_at IS NULL OR l.catalog_barcodes_retry_at<=now())
      ORDER BY l.catalog_barcodes_checked_at NULLS FIRST,l.seller_sku
      FOR UPDATE OF l SKIP LOCKED LIMIT 1
      """,(r,n)->new Candidate(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),tenant);
    if(rows.isEmpty())return null;var c=rows.getFirst();
    jdbc.update("UPDATE amazon_listings SET catalog_barcodes_retry_at=now()+interval '1 hour' WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND asin=?",tenant,c.connection(),c.marketplace(),c.asin());return c;
   });
   if(candidate==null)continue;
   try{
    var response=amazon.get(tenant,candidate.connection(),"/catalog/2022-04-01/items/"+encode(candidate.asin())
      +"?marketplaceIds="+encode(candidate.marketplace())+"&includedData=identifiers").json();
    if(!candidate.asin().equals(response.path("asin").asText()))continue;
    var codes=identifiers(response,candidate.marketplace());
    tx.executeWithoutResult(s->{scope(tenant);jdbc.update("""
       UPDATE amazon_listings SET catalog_barcodes=CAST(? AS jsonb),catalog_barcodes_asin=?,
         catalog_barcodes_checked_at=now(),catalog_barcodes_retry_at=NULL
       WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND asin=?
       """,new ObjectMapper().writeValueAsString(codes),candidate.asin(),tenant,candidate.connection(),candidate.marketplace(),candidate.asin());
       if(!codes.isEmpty())mapper.mapConnection(tenant,candidate.connection());});
   }catch(RuntimeException ex){
    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Amazon barcode enrichment deferred tenant={} connection={} asin={} error={}",tenant,candidate.connection(),candidate.asin(),ex.getClass().getSimpleName());
   }
  }
 }
 static List<String> identifiers(JsonNode response,String marketplace){
  Set<String> result=new TreeSet<>();
  for(var group:response.path("identifiers"))if(marketplace.equals(group.path("marketplaceId").asText()))
   for(var id:group.path("identifiers"))if(Set.of("UPC","EAN","GTIN").contains(id.path("identifierType").asText().toUpperCase(Locale.ROOT))){
    String code=BarcodePackRules.gtin(id.path("identifier").asText());if(code!=null)result.add(code);}
  return List.copyOf(result);
 }
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 private record Candidate(UUID connection,String marketplace,String asin){}
}
