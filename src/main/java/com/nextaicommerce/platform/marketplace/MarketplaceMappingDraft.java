package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only dialog defaults. The existing explicit Save mapping remains the only publisher. */
@Service
public class MarketplaceMappingDraft {
 private final JdbcTemplate jdbc;private final CatalogRepository catalog;
 public MarketplaceMappingDraft(JdbcTemplate jdbc,CatalogRepository catalog){this.jdbc=jdbc;this.catalog=catalog;}
 public record Product(UUID id,String name,String matchedCode,String imageUrl,String brand){public Product(UUID id,String name,String matchedCode,String imageUrl){this(id,name,matchedCode,imageUrl,null);}}
 public record Suggestion(int quantity,Product product,String itemCode){public Suggestion(int quantity,Product product){this(quantity,product,null);}}
 record Code(UUID id,String name,String code,String brand){Code(UUID id,String name,String code){this(id,name,code,null);}}
 static MarketplaceSkuAutoMapper.ParsedSku parsed(String sku){try{return MarketplaceSkuAutoMapper.parse(sku);}catch(IllegalArgumentException e){return MarketplaceSkuAutoMapper.parse("");}}
 static int quantity(String sku){var parsed=parsed(sku);return !parsed.explicitBundle()&&parsed.bundleComponents().isEmpty()&&parsed.quantityPerMarketplaceUnit()>=1&&parsed.quantityPerMarketplaceUnit()<=10000?parsed.quantityPerMarketplaceUnit():1;}
 static Product unique(String sku,List<Code> codes){var parsed=parsed(sku);if(parsed.explicitBundle()||!parsed.bundleComponents().isEmpty())return null;Set<String> tokens=new HashSet<>(parsed.itemCodeTokens());Map<UUID,Code> matches=new LinkedHashMap<>();for(Code code:codes)if(tokens.contains(MarketplaceSkuAutoMapper.normalizeCode(code.code())))matches.putIfAbsent(code.id(),code);if(matches.size()!=1)return null;Code match=matches.values().iterator().next();return new Product(match.id(),match.name(),match.code(),null,match.brand());}
 static String detectedItemCode(String sku){
  var value=parsed(sku);if(value.explicitBundle()||!value.bundleComponents().isEmpty())return null;
  var codes=value.itemCodeTokens().stream().filter(t->t.matches("[0-9]+")).distinct().toList();if(codes.size()!=1)return null;
  for(String token:sku.trim().split("[^A-Za-z0-9]+"))if(token.matches("[0-9]+")&&MarketplaceSkuAutoMapper.normalizeCode(token).equals(codes.getFirst()))return token;
  return null;
 }
 @Transactional(readOnly=true)
 public Suggestion suggest(UUID tenant,String sku){
  if(sku==null||sku.length()>500)return new Suggestion(1,null);
  var parsed=parsed(sku);int quantity=quantity(sku);if(parsed.explicitBundle()||!parsed.bundleComponents().isEmpty()||parsed.itemCodeTokens().isEmpty())return new Suggestion(quantity,null);
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  List<String> tokens=parsed.itemCodeTokens().stream().distinct().toList();List<Object> args=new ArrayList<>();args.add(tenant);args.add(tenant);args.addAll(tokens);
  List<Code> codes=jdbc.query("""
   WITH codes AS (
    SELECT i.id,coalesce(i.display_name,p.canonical_name) name,i.account_sku code,p.brand FROM account_catalog_items i JOIN global_catalog_products p ON p.id=i.global_product_id
     WHERE i.tenant_id=? AND i.status='ACTIVE' AND i.account_sku IS NOT NULL
    UNION ALL
    SELECT i.id,coalesce(i.display_name,p.canonical_name),o.vendor_item_code,p.brand FROM vendor_catalog_offers o
     JOIN account_catalog_items i ON i.tenant_id=o.tenant_id AND i.id=o.account_catalog_item_id JOIN global_catalog_products p ON p.id=i.global_product_id
     WHERE o.tenant_id=? AND i.status='ACTIVE' AND o.effective_to IS NULL AND o.vendor_item_code IS NOT NULL
   ), normalized AS(SELECT *,upper(regexp_replace(code,'[^A-Za-z0-9]','','g')) key FROM codes)
   SELECT id,name,code,brand FROM normalized WHERE (CASE WHEN key ~ '^[0-9]+$' THEN coalesce(nullif(ltrim(key,'0'),''),'0') ELSE key END) IN (
   """+String.join(",",Collections.nCopies(tokens.size(),"?"))+") ORDER BY id,code",(r,n)->new Code(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4)),args.toArray());
  Product product=unique(sku,codes);if(product!=null)product=new Product(product.id(),product.name(),product.matchedCode(),catalog.pickerImages(tenant,List.of(product.id())).get(product.id()),product.brand());
  return new Suggestion(quantity,product,detectedItemCode(sku));
 }
}
