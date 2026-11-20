package com.nextaicommerce.platform.collaboration;

import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/app/platform/catalog/discussions")
public class GlobalProductDiscussionController {
 @ModelAttribute void noCache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 private final GlobalProductDiscussionRepository repository;
 public GlobalProductDiscussionController(GlobalProductDiscussionRepository repository){this.repository=repository;}
 static String actor(Authentication auth){if(auth==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return auth.getName();}
 private static void scope(String type){if(!"GLOBAL_PRODUCT".equals(type))throw new IllegalArgumentException("Choose a global product discussion.");}
 @GetMapping("/subject") Object subject(Authentication auth,@RequestParam String subjectType,@RequestParam UUID subjectKey){String user=actor(auth);scope(subjectType);return repository.subject(user,subjectKey);}
 @GetMapping("/summaries") Object summaries(Authentication auth,@RequestParam String entityType,@RequestParam(name="entityId") List<String> ids){String user=actor(auth);scope(entityType);if(ids.size()>250)throw new IllegalArgumentException("Request up to 250 products.");return repository.summaries(user,ids.stream().distinct().map(UUID::fromString).toList());}
 @GetMapping("/reviews/{id}") Object conversation(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="TEAM_CHAT") String messageType){String user=actor(auth);team(messageType);return repository.conversation(user,id);}
 @GetMapping("/members") Object members(Authentication auth){return repository.members(actor(auth));}
 @PostMapping("/reviews") Object create(Authentication auth,@RequestParam String subjectType,@RequestParam UUID subjectKey,@RequestParam(required=false) String message,@RequestParam(defaultValue="TEAM_CHAT") String messageType,@RequestParam(name="attachments",required=false) List<MultipartFile> attachments){String user=actor(auth);scope(subjectType);team(messageType);return Map.of("reviewId",repository.create(user,subjectKey,message,CollaborationController.attachments(attachments)));}
 @PostMapping("/reviews/{id}/reply") Object reply(Authentication auth,@PathVariable UUID id,@RequestParam(required=false) String body,@RequestParam(required=false) String status,@RequestParam(defaultValue="TEAM_CHAT") String messageType,@RequestParam(name="attachments",required=false) List<MultipartFile> attachments){String user=actor(auth);team(messageType);return Map.of("reviewId",repository.reply(user,id,body,status,CollaborationController.attachments(attachments)));}
 @PostMapping("/reviews/{id}/reopen") Object reopen(Authentication auth,@PathVariable UUID id){return Map.of("reviewId",repository.reopen(actor(auth),id));}
 @GetMapping("/attachments/{id}") ResponseEntity<byte[]> attachment(Authentication auth,@PathVariable UUID id){var file=repository.attachment(actor(auth),id);String name=URLEncoder.encode(file.fileName(),StandardCharsets.UTF_8).replace("+","%20");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+name).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(file.contentType())).body(file.bytes());}
 private static void team(String value){if(!"TEAM_CHAT".equals(value))throw new IllegalArgumentException("Global discussions are shared with platform administrators.");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
}
