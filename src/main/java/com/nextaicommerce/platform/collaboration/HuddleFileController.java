package com.nextaicommerce.platform.collaboration;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.nextaicommerce.platform.web.AccountSelectionController;

/** Bounded temporary files. Bytes never travel in WebSocket room updates. */
@Controller
public class HuddleFileController {
 private record File(UUID room,UUID tenant,String name,String type,byte[] bytes,Instant expires){}
 private record Access(UUID tenant,UUID user,HuddleService.HuddleView room){}
 private final Map<UUID,File> files=new ConcurrentHashMap<>();
 private final HuddleService huddles;
 private final CollaborationRepository repository;
 private final HuddleWebSocketHandler socket;
 public HuddleFileController(HuddleService huddles,CollaborationRepository repository,HuddleWebSocketHandler socket){this.huddles=huddles;this.repository=repository;this.socket=socket;}
 private Access access(UUID room,HttpSession session,Authentication auth){
  if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID selected))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  var member=repository.currentMember(selected,auth.getName());if(member==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  UUID tenant=huddles.tenantForParticipant(room,member.id());
  if(tenant==null||!repository.huddleTenants(auth.getName()).contains(tenant))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  var view=huddles.find(tenant,room,member.id());if(view==null||!"ACTIVE".equals(view.status()))throw new ResponseStatusException(HttpStatus.GONE);
  return new Access(tenant,member.id(),view);
 }
 static String fileType(byte[] bytes){
  if(bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10)return "image/png";
  if(bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255)return "image/jpeg";
  if(bytes.length>=5&&new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
  for(byte b:bytes)if(b==0||((b&255)<32&&b!=9&&b!=10&&b!=13))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a PNG, JPEG, PDF, or plain text file.");
  return "text/plain";
 }
 @PostMapping("/app/huddles/{room}/files") @ResponseBody
 public synchronized Map<String,String> upload(@PathVariable UUID room,@RequestParam MultipartFile file,HttpSession session,Authentication auth)throws java.io.IOException{
  if(auth.getAuthorities().stream().noneMatch(a->Set.of("ROLE_PLATFORM_ADMIN","ROLE_OWNER","ROLE_ADMIN","ROLE_OPERATOR").contains(a.getAuthority())))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  var access=access(room,session,auth);
  if(file.isEmpty()||file.getSize()>2*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a file up to 2 MB.");
  cleanup();long total=files.values().stream().mapToLong(f->f.bytes.length).sum(),roomTotal=files.values().stream().filter(f->f.room.equals(room)).mapToLong(f->f.bytes.length).sum();
  if(total+file.getSize()>64*1024*1024||roomTotal+file.getSize()>8*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Temporary file storage is full. Use saved collaboration for more files.");
  byte[] bytes=file.getBytes();String type=fileType(bytes),name=Optional.ofNullable(file.getOriginalFilename()).orElse("Attachment").replaceAll("[\\r\\n\\\\/]","_");if(name.length()>120)name=name.substring(0,120);
  UUID id=UUID.randomUUID();files.put(id,new File(room,access.tenant,name,type,bytes,Instant.now().plusSeconds(2700)));
  try{var updated=huddles.message(access.tenant,room,access.user,"Shared file: "+name+"\n/app/huddles/"+room+"/files/"+id);socket.publishFile(access.tenant,updated);}
  catch(RuntimeException failure){files.remove(id);throw failure;}
  return Map.of("message","File shared. Temporary files expire after 45 minutes.");
 }
 @GetMapping("/app/huddles/{room}/files/{id}") @ResponseBody
 public ResponseEntity<byte[]> download(@PathVariable UUID room,@PathVariable UUID id,HttpSession session,Authentication auth){
  access(room,session,auth);var file=files.get(id);if(file==null||!file.room.equals(room)||file.expires.isBefore(Instant.now()))throw new ResponseStatusException(HttpStatus.GONE);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.type))
   .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox")
   .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name,java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(file.bytes);
 }
 @Scheduled(fixedDelay=60000)
 public synchronized void cleanup(){files.entrySet().removeIf(entry->entry.getValue().expires.isBefore(Instant.now()));}
}
