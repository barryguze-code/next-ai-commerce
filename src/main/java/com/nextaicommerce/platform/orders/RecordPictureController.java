package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Account-owned artwork for non-product records. */
@Controller
public class RecordPictureController {
 private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final WorkspaceAccessRepository access;
 public RecordPictureController(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceAccessRepository access){this.jdbc=jdbc;this.tx=tx;this.access=access;}
 private UUID currentUser(Authentication auth){return jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?) AND status='ACTIVE'",UUID.class,auth.getName());}
 private void userScope(UUID id,HttpSession session,Authentication auth,boolean write){
  if(id.equals(currentUser(auth)))return;
  UUID tenant=account(session,auth,false);
  boolean platform=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
  if(write&&!access.canAdministerAccount(tenant,auth.getName(),platform))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users u WHERE u.id=? AND (EXISTS(SELECT 1 FROM tenant_memberships m WHERE m.user_id=u.id AND m.tenant_id=?) OR EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=u.id AND p.active)))",Boolean.class,id,tenant)))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  if(write&&!platform&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_administrators WHERE user_id=? AND active)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 @GetMapping("/app/profile/picture") @ResponseBody
 public ResponseEntity<byte[]> ownPicture(HttpSession session,Authentication auth){return get("USER",currentUser(auth),session,auth);}
 @PostMapping("/app/profile/picture") @ResponseBody
 public ResponseEntity<?> ownUpload(@RequestParam MultipartFile image,HttpSession session,Authentication auth) throws java.io.IOException{return upload("USER",currentUser(auth),image,session,auth);}
 private UUID account(HttpSession session,Authentication auth,boolean write){
  if(!(session.getAttribute("selectedTenantId") instanceof UUID id))throw new ResponseStatusException(HttpStatus.CONFLICT);
  boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
  if(!access.canAccessAccount(id,auth.getName(),admin)||(write&&!access.canOperateAccount(id,auth.getName())))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  return id;
 }
 private void scope(UUID tenant,String type,UUID id){
  String table=switch(type){case "VENDOR"->"vendors";case "SHIPMENT"->"receiving_sessions";case "USER"->"tenant_memberships";default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST);};
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  String key="USER".equals(type)?"user_id":"id";
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE tenant_id=? AND "+key+"=?)",Boolean.class,tenant,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
 }
 @GetMapping("/app/catalog/record-pictures/{type}/{id}") @ResponseBody
 public ResponseEntity<byte[]> get(@PathVariable String type,@PathVariable UUID id,HttpSession session,Authentication auth){
  if("USER".equals(type))return tx.execute(s->{userScope(id,session,auth,false);return jdbc.query("SELECT content_type,image_bytes FROM user_profile_pictures WHERE user_id=?",rs->rs.next()?ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.parseMediaType(rs.getString(1))).body(rs.getBytes(2)):ResponseEntity.notFound().build(),id);});
  UUID tenant=account(session,auth,false);
  return tx.execute(s->{scope(tenant,type,id);return jdbc.query("SELECT content_type,image_bytes FROM record_picture_overrides WHERE tenant_id=? AND entity_type=? AND entity_id=?",rs->rs.next()?ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.parseMediaType(rs.getString(1))).body(rs.getBytes(2)):ResponseEntity.notFound().build(),tenant,type,id);});
 }
 @PostMapping("/app/catalog/record-pictures/{type}/{id}") @ResponseBody
 public ResponseEntity<?> upload(@PathVariable String type,@PathVariable UUID id,@RequestParam MultipartFile image,HttpSession session,Authentication auth) throws java.io.IOException {
  UUID tenant="USER".equals(type)?null:account(session,auth,true);
  try{
   if(image.getSize()>5_000_000)throw new IllegalArgumentException("Choose a JPG or PNG up to 5 MB.");
   byte[] bytes=image.getBytes();String mime=OrderPictureController.imageType(bytes);
   tx.executeWithoutResult(s->{
    if("USER".equals(type)){userScope(id,session,auth,true);jdbc.update("INSERT INTO user_profile_pictures(user_id,content_type,image_bytes,updated_by) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET content_type=EXCLUDED.content_type,image_bytes=EXCLUDED.image_bytes,updated_by=EXCLUDED.updated_by,updated_at=now()",id,mime,bytes,auth.getName());}
    else{scope(tenant,type,id);jdbc.update("INSERT INTO record_picture_overrides(tenant_id,entity_type,entity_id,content_type,image_bytes,updated_by) VALUES (?,?,?,?,?,?) ON CONFLICT(tenant_id,entity_type,entity_id) DO UPDATE SET content_type=EXCLUDED.content_type,image_bytes=EXCLUDED.image_bytes,updated_by=EXCLUDED.updated_by,updated_at=now()",tenant,type,id,mime,bytes,auth.getName());}
   });
   return ResponseEntity.ok(Map.of("imageUrl","/app/catalog/record-pictures/"+type+"/"+id));
  }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
 }
}
