package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class MappingPictureController {
 private final OrderPictureController pictures;
 private final CatalogRepository catalog;
 private final HttpClient images=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 public MappingPictureController(OrderPictureController pictures,CatalogRepository catalog){this.pictures=pictures;this.catalog=catalog;}
 @GetMapping("/app/marketplace-skus/mapping-picture") @ResponseBody
 public ResponseEntity<byte[]> picture(@RequestParam String sku,HttpSession session,Authentication auth){return pictures.picture(pictures.skuContext(session,sku,auth,false));}
 @PostMapping("/app/marketplace-skus/mapping-picture") @ResponseBody
 public ResponseEntity<?> update(@RequestParam String sku,@RequestParam(required=false) UUID productId,
   @RequestParam(defaultValue="false") boolean amazon,@RequestParam(required=false) MultipartFile image,
   HttpSession session,Authentication auth){
  var context=pictures.skuContext(session,sku,auth);
  try{
   byte[] bytes=null;String type=null,url=null;
   if(amazon){
    url=pictures.amazonImage(context);
    if(productId!=null){
     var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
     var response=images.send(request,HttpResponse.BodyHandlers.ofInputStream());
     try(var stream=response.body()){
      if(response.statusCode()!=200)throw new IllegalArgumentException("Amazon picture could not be downloaded. Your existing picture was kept.");
      bytes=stream.readNBytes(5_000_001);type=OrderPictureController.imageType(bytes);
     }
    }
   }else{
    if(image==null||image.getSize()>5_000_000)throw new IllegalArgumentException("Choose a JPG or PNG up to 5 MB.");
    bytes=image.getBytes();type=OrderPictureController.imageType(bytes);
   }
   if(productId!=null){
    catalog.saveProductImage(context.tenant(),auth.getName(),productId,type,amazon?"amazon-picture":image.getOriginalFilename(),bytes);
    url="/app/catalog/products/"+productId+"/image";
   }else{
    pictures.save(context,auth,bytes,type,url);
    if(url==null)url="/app/marketplace-skus/mapping-picture?sku="+URLEncoder.encode(sku,StandardCharsets.UTF_8);
   }
   return ResponseEntity.ok(Map.of("imageUrl",url));
  }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
  catch(Exception e){return ResponseEntity.status(502).body(Map.of("error","Picture update failed. Your existing picture was kept."));}
 }
}
