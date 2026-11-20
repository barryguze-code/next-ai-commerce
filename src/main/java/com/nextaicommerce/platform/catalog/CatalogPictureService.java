package com.nextaicommerce.platform.catalog;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Saved public marketplace artwork. Private account uploads are never global candidates. */
@Service
public class CatalogPictureService {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CatalogRepository catalog;private final WorkspaceAccessRepository access;
 public CatalogPictureService(JdbcTemplate jdbc,TransactionTemplate tx,CatalogRepository catalog,WorkspaceAccessRepository access){this.jdbc=jdbc;this.tx=tx;this.catalog=catalog;this.access=access;}
 public record Candidate(UUID id,String sku,String asin,String imageUrl,boolean mixedBundle,String channel,String reference){
  public Candidate(UUID id,String sku,String asin,String imageUrl,boolean mixedBundle){this(id,sku,asin,imageUrl,mixedBundle,"AMAZON",asin);}
 }
 record Source(UUID tenant,UUID connection,UUID listing,UUID item,UUID global,String sku,String asin,String url,boolean mixed,String channel,String reference) {
  Source(UUID tenant,UUID connection,UUID listing,UUID item,UUID global,String sku,String asin,String url,boolean mixed){this(tenant,connection,listing,item,global,sku,asin,url,mixed,"AMAZON",asin);}
  Candidate candidate(){return new Candidate(listing,sku,asin,url,mixed,channel,reference);}
 }
 public static boolean publicAmazonImage(String value){
  try{URI uri=URI.create(value);String host=uri.getHost();return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&host!=null&&(host.equals("m.media-amazon.com")||host.endsWith(".media-amazon.com")||host.endsWith(".ssl-images-amazon.com"));}catch(RuntimeException e){return false;}
 }
 void admin(String actor){if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users u JOIN platform_administrators a ON a.user_id=u.id AND a.active WHERE lower(u.email)=lower(?) AND u.status='ACTIVE')",Boolean.class,actor)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 private void account(UUID tenant,String actor){if(tenant==null||!access.canOperateAccount(tenant,actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private List<Source> sources(UUID tenant,UUID id,boolean global){return sources(tenant,id,global,true);}
 private List<Source> sources(UUID tenant,UUID id,boolean global,boolean savedImage){
  scope(tenant);
  return jdbc.query("""
   WITH effective AS (
    SELECT c.tenant_id,c.marketplace_sku_mapping_id mapping_id,c.account_catalog_item_id item_id FROM marketplace_sku_mapping_components c WHERE c.tenant_id=?
    UNION ALL SELECT m.tenant_id,m.id,m.account_catalog_item_id FROM marketplace_sku_mappings m WHERE m.tenant_id=? AND m.account_catalog_item_id IS NOT NULL
     AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id)
   )
   SELECT DISTINCT l.id,i.id item_id,i.global_product_id,m.marketplace_connection_id,l.seller_sku,l.asin,l.image_url,
     EXISTS(SELECT 1 FROM effective other JOIN account_catalog_items oi ON oi.tenant_id=other.tenant_id AND oi.id=other.item_id
       WHERE other.tenant_id=m.tenant_id AND other.mapping_id=m.id AND oi.global_product_id<>i.global_product_id) mixed
   FROM effective e JOIN account_catalog_items i ON i.tenant_id=e.tenant_id AND i.id=e.item_id
   JOIN marketplace_sku_mappings m ON m.tenant_id=e.tenant_id AND m.id=e.mapping_id AND m.status='ACTIVE'
   JOIN marketplace_connections cn ON cn.tenant_id=m.tenant_id AND cn.id=m.marketplace_connection_id AND cn.channel='AMAZON' AND cn.status='ACTIVE'
   JOIN amazon_listings l ON l.tenant_id=m.tenant_id AND l.marketplace_connection_id=m.marketplace_connection_id AND l.seller_sku=m.marketplace_sku
   WHERE i.tenant_id=? AND """+(global?" i.global_product_id":" i.id")+"""
    =? AND l.asin IS NOT NULL AND l.asin<>''
    AND (m.asin IS NULL OR m.asin='' OR m.asin=l.asin)
   ORDER BY mixed,l.seller_sku,l.id
   """,(r,n)->new Source(tenant,r.getObject("marketplace_connection_id",UUID.class),r.getObject("id",UUID.class),r.getObject("item_id",UUID.class),r.getObject("global_product_id",UUID.class),r.getString("seller_sku"),r.getString("asin"),r.getString("image_url"),r.getBoolean("mixed")),tenant,tenant,tenant,id).stream().filter(s->!savedImage||publicAmazonImage(s.url())).toList();
 }
 private List<Source> shopifySources(UUID tenant,UUID item){
  return jdbc.query("""
   SELECT DISTINCT v.id,m.marketplace_connection_id,i.global_product_id,v.seller_sku,v.variant_gid,coalesce(v.image_url,p.image_url) image_url,
    EXISTS(SELECT 1 FROM marketplace_sku_mapping_components other JOIN account_catalog_items oi ON oi.tenant_id=other.tenant_id AND oi.id=other.account_catalog_item_id WHERE other.tenant_id=m.tenant_id AND other.marketplace_sku_mapping_id=m.id AND oi.global_product_id<>i.global_product_id) mixed
   FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components part ON part.tenant_id=m.tenant_id AND part.marketplace_sku_mapping_id=m.id
   JOIN account_catalog_items i ON i.tenant_id=part.tenant_id AND i.id=part.account_catalog_item_id
   JOIN marketplace_connections cn ON cn.tenant_id=m.tenant_id AND cn.id=m.marketplace_connection_id AND cn.channel='SHOPIFY' AND cn.status='ACTIVE'
   JOIN shopify_variants v ON v.tenant_id=m.tenant_id AND v.marketplace_connection_id=m.marketplace_connection_id AND v.id=m.shopify_variant_id
   JOIN shopify_products p ON p.tenant_id=v.tenant_id AND p.marketplace_connection_id=v.marketplace_connection_id AND p.id=v.product_id
   WHERE m.tenant_id=? AND i.id=? AND m.status='ACTIVE'
   ORDER BY v.variant_gid
   """,(r,n)->new Source(tenant,r.getObject("marketplace_connection_id",UUID.class),r.getObject("id",UUID.class),item,r.getObject("global_product_id",UUID.class),Objects.requireNonNullElse(r.getString("seller_sku"),"No SKU"),null,r.getString("image_url"),r.getBoolean("mixed"),"SHOPIFY",r.getString("variant_gid")),tenant,item).stream().filter(v->com.nextaicommerce.platform.shopify.ShopifyCatalogClient.image(v.url())!=null).toList();
 }
 private List<Source> resolve(UUID tenant,String actor,UUID id,boolean global){
  if(!global){account(tenant,actor);if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_catalog_items WHERE tenant_id=? AND id=?)",Boolean.class,tenant,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND);var result=new ArrayList<>(sources(tenant,id,false));
   var stores=access.listAccountOptions(actor,access.isCurrentPlatformAdmin(actor)).stream().filter(a->a.id().equals(tenant)).flatMap(a->a.stores().stream()).filter(c->"SHOPIFY".equals(c.channel())).map(c->c.id()).collect(java.util.stream.Collectors.toSet());scope(tenant);
   result.addAll(shopifySources(tenant,id).stream().filter(v->stores.contains(v.connection())).toList());return result;}
  admin(actor);return sharedSources(id);
 }
 private List<Source> sharedSources(UUID id){
  List<Source> result=new ArrayList<>();String previous=jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)",String.class);
  RuntimeException failure=null;
  try{for(UUID t:jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(r,n)->r.getObject(1,UUID.class)))result.addAll(sources(t,id,true));}
  catch(RuntimeException e){failure=e;throw e;}
  finally{try{jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,previous==null?"":previous);}catch(RuntimeException cleanup){if(failure==null)throw cleanup;failure.addSuppressed(cleanup);}}
  return result.stream().sorted(Comparator.comparing(Source::mixed).thenComparing(Source::sku).thenComparing(Source::listing)).toList();
 }
 /** Only public, single-product artwork crosses this boundary; no private image, SKU or account metadata is returned. */
 public String publicProductFallback(UUID tenant,String actor,UUID product){return tx.execute(s->{account(tenant,actor);
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM global_catalog_products WHERE id=? AND status='ACTIVE')",Boolean.class,product)))return null;
  return sharedSources(product).stream().filter(v->!v.mixed()).map(Source::url).findFirst().orElse(null);
 });}
 public void requireAdministrator(String actor){admin(actor);}
 public boolean canSync(UUID tenant,String actor,UUID item){return tx.execute(s->{account(tenant,actor);return sources(tenant,item,false,false).stream().filter(v->!v.mixed()).anyMatch(v->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_connection_credentials WHERE tenant_id=? AND marketplace_connection_id=?)",Boolean.class,tenant,v.connection())));});}
 public List<Candidate> candidates(UUID tenant,String actor,UUID id,boolean global){return tx.execute(s->resolve(tenant,actor,id,global).stream().map(Source::candidate).distinct().toList());}
 public String fallback(String actor,UUID product){return tx.execute(s->resolve(null,actor,product,true).stream().filter(v->!v.mixed()).map(Source::url).findFirst().orElse(null));}
 Source selected(UUID tenant,String actor,UUID id,boolean global,UUID candidate){return tx.execute(s->resolve(tenant,actor,id,global).stream().filter(v->v.listing().equals(candidate)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"The saved SKU picture or mapping changed. Open the chooser again.")));}
 void save(UUID tenant,String actor,UUID id,boolean global,Source expected,String type,byte[] bytes){tx.executeWithoutResult(s->{
  if(global)admin(actor);else account(tenant,actor);
  if(expected!=null&&!resolve(tenant,actor,id,global).contains(expected))throw new ResponseStatusException(HttpStatus.CONFLICT,"The SKU mapping or picture changed. No picture was saved.");
  if(!global){catalog.saveProductImage(tenant,actor,id,type,expected==null?"uploaded-picture":expected.channel().toLowerCase(Locale.ROOT)+"-"+expected.reference(),bytes);return;}
  UUID provenance=expected==null?tenant:expected.tenant();if(provenance==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an account for the upload provenance.");
  jdbc.update("""
   INSERT INTO global_catalog_product_images(global_product_id,image_bytes,image_content_type,source_asin,source_tenant_id) VALUES (?,?,?,?,?)
   ON CONFLICT(global_product_id) DO UPDATE SET image_bytes=EXCLUDED.image_bytes,image_content_type=EXCLUDED.image_content_type,
    source_asin=EXCLUDED.source_asin,source_tenant_id=EXCLUDED.source_tenant_id,updated_at=now()
   """,id,bytes,type,expected==null?"MANUAL":expected.asin(),provenance);
 });}
}
