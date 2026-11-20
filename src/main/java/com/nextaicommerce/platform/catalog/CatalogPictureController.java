package com.nextaicommerce.platform.catalog;

import com.nextaicommerce.platform.orders.OrderPictureController;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Explicit selection copies validated public artwork; it never changes SKU mappings or quantities. */
@Controller
public class CatalogPictureController {
 private final CatalogPictureService pictures;
 @org.springframework.beans.factory.annotation.Value("${app.integrations.external-enabled:true}") private boolean externalEnabled;
 public CatalogPictureController(CatalogPictureService pictures){this.pictures=pictures;}
 private static boolean global(Authentication auth){boolean admin=auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));if(!admin)throw new ResponseStatusException(HttpStatus.FORBIDDEN);return true;}
 private static UUID tenant(HttpSession session){return session.getAttribute("selectedTenantId") instanceof UUID id?id:null;}
 @GetMapping("/app/catalog/products/{id}/image/candidates") @ResponseBody
 public ResponseEntity<?> accountCandidates(@PathVariable UUID id,HttpSession session,Authentication auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("candidates",pictures.candidates(tenant(session),auth.getName(),id,false),"canSync",externalEnabled&&pictures.canSync(tenant(session),auth.getName(),id),"syncUnavailable","Fresh Amazon sync needs an enabled connection and credentials in this environment."));}
 @GetMapping("/app/platform/catalog/products/{id}/image/candidates") @ResponseBody
 public ResponseEntity<?> globalCandidates(@PathVariable UUID id,HttpSession session,Authentication auth){global(auth);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("candidates",pictures.candidates(null,auth.getName(),id,true),"canSync",false,"syncUnavailable","Use Account Catalogue for a fresh Amazon fetch; choosing a saved picture makes no Amazon API call."));}
 @PostMapping("/app/catalog/products/{id}/image/choose") @ResponseBody
 public ResponseEntity<?> accountChoose(@PathVariable UUID id,@RequestParam UUID candidate,HttpSession session,Authentication auth){return choose(id,candidate,session,auth,false,false);}
 @PostMapping("/app/platform/catalog/products/{id}/image/choose") @ResponseBody
 public ResponseEntity<?> globalChoose(@PathVariable UUID id,@RequestParam UUID candidate,@RequestParam(defaultValue="false") boolean confirmShared,HttpSession session,Authentication auth){global(auth);return choose(id,candidate,session,auth,true,confirmShared);}
 private ResponseEntity<?> choose(UUID id,UUID candidate,HttpSession session,Authentication auth,boolean global,boolean confirmed){
  if(global&&!confirmed)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirm the shared Global Catalogue picture change.");
  var source=pictures.selected(tenant(session),auth.getName(),id,global,candidate);
  try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()){
   var response=client.send(java.net.http.HttpRequest.newBuilder(URI.create(source.url())).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
   try(var input=response.body()){if(response.statusCode()!=200)throw new IllegalArgumentException("The saved marketplace image is unavailable. Your picture was kept.");byte[] bytes=input.readNBytes(5_000_001);String type=OrderPictureController.imageType(bytes);pictures.save(tenant(session),auth.getName(),id,global,source,type,bytes);}
   return ResponseEntity.ok(Map.of("imageUrl",path(id,global)));
  }catch(ResponseStatusException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return ResponseEntity.status(502).body(Map.of("error","The picture could not be copied. Your existing picture was kept."));}
 }
 @PostMapping("/app/platform/catalog/products/{id}/image/upload") @ResponseBody
 public ResponseEntity<?> globalUpload(@PathVariable UUID id,@RequestParam MultipartFile image,@RequestParam(defaultValue="false") boolean confirmShared,HttpSession session,Authentication auth)throws java.io.IOException{
  global(auth);if(!confirmShared)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirm the shared Global Catalogue picture change.");
  try{if(image.getSize()>5_000_000)throw new IllegalArgumentException("Choose a JPG or PNG up to 5 MB.");byte[] bytes=image.getBytes();String type=OrderPictureController.imageType(bytes);pictures.save(tenant(session),auth.getName(),id,true,null,type,bytes);return ResponseEntity.ok(Map.of("imageUrl",path(id,true)));}
  catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
 }
 private static String path(UUID id,boolean global){return (global?"/app/platform/catalog/products/":"/app/catalog/products/")+id+"/image";}
}
