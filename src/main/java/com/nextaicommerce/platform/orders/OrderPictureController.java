package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class OrderPictureController {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private final WorkspaceAccessRepository access;
 private final AmazonSpApiClient amazon;
 public OrderPictureController(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access,AmazonSpApiClient amazon){this.jdbc=jdbc;this.tx=tx;this.access=access;this.amazon=amazon;}
 record Context(UUID tenant,UUID store,String sku,String asin,String marketplace){}
 private Context context(HttpSession session,UUID item,Authentication auth,boolean write){
  Object tenant=session.getAttribute("selectedTenantId"),store=session.getAttribute(AccountSelectionController.STORE_ID);
  if(!(tenant instanceof UUID t)||!(store instanceof UUID s))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an account and store first.");
  if(write&&!access.canOperateAccount(t,auth.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  return tx.execute(status->{scope(t);return jdbc.query("SELECT item.seller_sku,item.asin,orders.marketplace_id FROM amazon_order_items item JOIN amazon_orders orders ON orders.tenant_id=item.tenant_id AND orders.marketplace_connection_id=item.marketplace_connection_id AND orders.amazon_order_id=item.amazon_order_id WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.id=?",rs->{
   if(!rs.next()||rs.getString(1)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
   return new Context(t,s,rs.getString(1),rs.getString(2),rs.getString(3));
  },t,s,item);});
 }
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 static String imageType(byte[] bytes) throws java.io.IOException {
  if(bytes.length==0||bytes.length>5_000_000)throw new IllegalArgumentException("Choose a JPG or PNG up to 5 MB.");
  try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
   var readers=ImageIO.getImageReaders(input);
   if(!readers.hasNext())throw new IllegalArgumentException("Choose a valid JPG or PNG image.");
   var reader=readers.next();try{reader.setInput(input);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
    if(!Set.of("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>25_000_000)throw new IllegalArgumentException("Use a JPG or PNG no larger than 25 megapixels.");
    reader.read(0);
    return format.equals("png")?"image/png":"image/jpeg";
   }finally{reader.dispose();}
  }
 }
 private void save(Context c,Authentication auth,byte[] bytes,String type,String url){tx.executeWithoutResult(status->{scope(c.tenant());jdbc.update("""
  INSERT INTO order_sku_pictures(tenant_id,marketplace_connection_id,seller_sku,image_bytes,content_type,source_url,updated_by)
  VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id,seller_sku) DO UPDATE
  SET image_bytes=EXCLUDED.image_bytes,content_type=EXCLUDED.content_type,source_url=EXCLUDED.source_url,
      updated_by=EXCLUDED.updated_by,updated_at=now()
  """,c.tenant(),c.store(),c.sku(),bytes,type,url,auth.getName());});}
 @PostMapping("/app/orders/items/{item}/picture") @ResponseBody
 public ResponseEntity<?> upload(@PathVariable UUID item,@RequestParam("image") MultipartFile image,HttpSession session,Authentication auth) throws java.io.IOException {
  var c=context(session,item,auth,true);
  try{if(image.getSize()>5_000_000)throw new IllegalArgumentException("Choose a JPG or PNG up to 5 MB.");byte[] bytes=image.getBytes();String type=imageType(bytes);save(c,auth,bytes,type,null);return ResponseEntity.ok(Map.of("imageUrl","/app/orders/items/"+item+"/picture"));}
  catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
  catch(java.io.IOException e){return ResponseEntity.badRequest().body(Map.of("error","This image could not be read. Choose a valid JPG or PNG."));}
 }
 @PostMapping("/app/orders/items/{item}/picture/amazon") @ResponseBody
 public ResponseEntity<?> sync(@PathVariable UUID item,HttpSession session,Authentication auth){
  var c=context(session,item,auth,true);
  if(c.asin()==null)return ResponseEntity.badRequest().body(Map.of("error","This order item has no Amazon ASIN."));
  try{
   var response=amazon.get(c.tenant(),c.store(),"/catalog/2022-04-01/items/"+URLEncoder.encode(c.asin(),StandardCharsets.UTF_8)+"?marketplaceIds="+URLEncoder.encode(c.marketplace(),StandardCharsets.UTF_8)+"&includedData=images").json();
   for(var group:response.path("images"))if(c.marketplace().equals(group.path("marketplaceId").asText()))for(var image:group.path("images"))if("MAIN".equals(image.path("variant").asText())){
    String url=image.path("link").asText();URI uri=URI.create(url);String host=uri.getHost();
    if(!"https".equals(uri.getScheme())||host==null||!(host.endsWith(".media-amazon.com")||host.endsWith(".ssl-images-amazon.com")))continue;
    save(c,auth,null,null,url);return ResponseEntity.ok(Map.of("imageUrl",url));
   }
   return ResponseEntity.badRequest().body(Map.of("error","Amazon did not return a main image. Your existing picture was kept."));
  }catch(Exception e){return ResponseEntity.status(502).body(Map.of("error","Amazon image sync is unavailable. Your existing picture was kept."));}
 }
 @GetMapping("/app/orders/items/{item}/picture") @ResponseBody
 public ResponseEntity<byte[]> picture(@PathVariable UUID item,HttpSession session,Authentication auth){
  var c=context(session,item,auth,false);
  return tx.execute(status->{scope(c.tenant());return jdbc.query("SELECT image_bytes,content_type,source_url FROM order_sku_pictures WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=?",rs->{
   if(!rs.next())return ResponseEntity.notFound().build();
   if(rs.getString(3)!=null)return ResponseEntity.status(302).location(URI.create(rs.getString(3))).cacheControl(CacheControl.noStore()).build();
   return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(rs.getString(2))).body(rs.getBytes(1));
  },c.tenant(),c.store(),c.sku());});
 }
}
