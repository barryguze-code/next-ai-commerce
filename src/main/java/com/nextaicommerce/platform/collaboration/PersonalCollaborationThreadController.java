package com.nextaicommerce.platform.collaboration;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Session-independent context for the existing conversation dialog on the personal queue. */
@Controller
@RequestMapping("/app/collaboration/personal/context/{context}")
class PersonalCollaborationThreadController {
    private final PersonalCollaborationQueue queue;
    private final PersonalCollaborationActions actions;
    private final CollaborationRepository reviews;
    private final com.nextaicommerce.platform.catalog.CatalogRepository catalog;
    PersonalCollaborationThreadController(PersonalCollaborationQueue queue,PersonalCollaborationActions actions,CollaborationRepository reviews,com.nextaicommerce.platform.catalog.CatalogRepository catalog){this.queue=queue;this.actions=actions;this.reviews=reviews;this.catalog=catalog;}
    private boolean staff(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));}
    private PersonalCollaborationQueue.Card context(UUID id,Authentication auth){
        var card=queue.authorized(auth.getName(),staff(auth),new PersonalCollaborationQueue.Key("REVIEW",id));
        if(card.account()==null)throw new AccessDeniedException("Conversation unavailable.");
        return card;
    }
    private UUID account(UUID context,UUID id,Authentication auth){
        if(!context.equals(id))throw new AccessDeniedException("Conversation context does not match.");
        return context(context,auth).account();
    }
    @GetMapping("/reviews/{id}") @ResponseBody
    ResponseEntity<?> conversation(@PathVariable UUID context,@PathVariable UUID id,@RequestParam(defaultValue="TEAM_CHAT") String messageType,Authentication auth){
        UUID account=account(context,id,auth);String type=CollaborationController.normalizeMessageType(messageType);
        var review=reviews.review(account,id,auth.getName());if(review==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollaborationController.Conversation(review,reviews.messages(account,id,auth.getName(),type),type,
                "TEAM_CHAT".equals(type)?reviews.unreadMentionMessages(account,id,auth.getName()):List.of()));
    }
    @GetMapping("/picture") @ResponseBody
    ResponseEntity<byte[]> picture(@PathVariable UUID context,Authentication auth){
        UUID account=context(context,auth).account();var review=reviews.review(account,context,auth.getName());
        if(review==null)return ResponseEntity.notFound().build();
        String url=reviews.pictures(account,List.of(review),auth.getName()).get(context);
        if(url==null||!url.matches("/app/catalog/products/[0-9a-fA-F-]{36}/image"))return ResponseEntity.notFound().build();
        UUID product=UUID.fromString(url.substring("/app/catalog/products/".length(),url.length()-"/image".length()));
        var image=catalog.productImage(account,product);if(image==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.contentType())).header("X-Content-Type-Options","nosniff").body(image.bytes());
    }
    @GetMapping("/members") @ResponseBody
    Object members(@PathVariable UUID context,Authentication auth){return reviews.members(context(context,auth).account());}
    @PostMapping("/reviews/{id}/reply") @ResponseBody
    Object reply(@PathVariable UUID context,@PathVariable UUID id,@RequestParam(defaultValue="") String body,
            @RequestParam(defaultValue="TEAM_CHAT") String messageType,@RequestParam(required=false) String status,
            @RequestParam(required=false) List<MultipartFile> attachments,Authentication auth){
        account(context,id,auth);
        actions.reply(auth.getName(),staff(auth),id,body,CollaborationController.normalizeMessageType(messageType),status,CollaborationController.attachments(attachments));
        return Map.of("reviewId",id);
    }
    @PostMapping("/reviews/{id}/assignees") @ResponseBody
    Object assign(@PathVariable UUID context,@PathVariable UUID id,@RequestParam(required=false) Set<UUID> people,Authentication auth){
        account(context,id,auth);return actions.assign(auth.getName(),staff(auth),id,people==null?Set.of():people);
    }
    @PostMapping("/reviews/{id}/title") @ResponseBody
    Object title(@PathVariable UUID context,@PathVariable UUID id,@RequestParam String title,Authentication auth){
        account(context,id,auth);return actions.title(auth.getName(),staff(auth),id,title);
    }
    @PostMapping("/reviews/{id}/due-date") @ResponseBody
    Object due(@PathVariable UUID context,@PathVariable UUID id,@RequestParam(required=false) String dueDate,@RequestParam(defaultValue="America/Los_Angeles") String timeZone,Authentication auth){
        account(context,id,auth);return actions.due(auth.getName(),staff(auth),id,dueDate==null||dueDate.isBlank()?null:java.time.LocalDate.parse(dueDate),timeZone);
    }
    @PostMapping("/reviews/{id}/reopen") @ResponseBody
    Object reopen(@PathVariable UUID context,@PathVariable UUID id,Authentication auth){
        account(context,id,auth);actions.change(auth.getName(),staff(auth),new PersonalCollaborationQueue.Key("REVIEW",id),false);return Map.of("reviewId",id);
    }
    @PostMapping("/reviews/{id}/read") @ResponseBody
    ResponseEntity<?> read(@PathVariable UUID context,@PathVariable UUID id,@RequestParam(name="messageId") List<UUID> ids,Authentication auth){
        UUID account=account(context,id,auth);if(ids.size()>250)return ResponseEntity.badRequest().build();
        return reviews.acknowledgeMessages(account,id,auth.getName(),ids)?ResponseEntity.noContent().build():ResponseEntity.notFound().build();
    }
    @GetMapping("/attachments/{id}") @ResponseBody
    ResponseEntity<byte[]> file(@PathVariable UUID context,@PathVariable UUID id,Authentication auth){
        UUID account=context(context,auth).account();var file=reviews.attachment(account,context,id,auth.getName());if(file==null)return ResponseEntity.notFound().build();
        String name=java.net.URLEncoder.encode(file.fileName(),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+name).header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    ResponseEntity<?> invalid(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()));}
}
