package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
class PersonalCollaborationController {
    private final PersonalCollaborationQueue queue;
    private final PersonalCollaborationActions actions;
    private final CollaborationRepository reviews;
    private final com.nextaicommerce.platform.web.WorkspaceAccessRepository accounts;
    PersonalCollaborationController(PersonalCollaborationQueue queue, PersonalCollaborationActions actions, CollaborationRepository reviews,com.nextaicommerce.platform.web.WorkspaceAccessRepository accounts){this.queue=queue;this.actions=actions;this.reviews=reviews;this.accounts=accounts;}
    @GetMapping(value="/app/collaboration",params={"!view","!threadId","!entityType","!subjectType","!subjectKey"})
    String personal(Authentication auth,HttpSession session,Model model){
        PageController.addTenantModel(session,model); PageController.addAccessModel(auth,model);
        model.addAttribute("personalUser",auth.getName());model.addAttribute("members",java.util.List.of());
        model.addAttribute("collaborationAccounts",accounts.listAccountOptions(auth.getName(),staff(auth)));
        return "personal-collaboration";
    }
    private boolean staff(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));}
    @GetMapping("/app/collaboration/personal/data") @ResponseBody
    ResponseEntity<?> data(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ASSIGNED") String scope,Authentication auth){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(presentation(queue.view(auth.getName(),staff(auth),scope,q),auth));
    }
    record Presentation(java.util.List<PersonalCollaborationQueue.Row> rows,long revision,
            java.util.Map<UUID,CollaborationRepository.Review> reviews,java.util.Map<UUID,String> pictures,java.util.Map<String,java.util.List<String>> closedGroups,java.util.Map<String,java.time.Instant> dates,java.util.List<CollaborationCompletionWindow> windows,java.util.Set<UUID> editableAccounts) {}
    private Presentation presentation(PersonalCollaborationQueue.Page page,Authentication auth){
        var details=new java.util.LinkedHashMap<UUID,CollaborationRepository.Review>();
        var pictures=new java.util.HashMap<UUID,String>();
        var grouped=new java.util.HashMap<UUID,java.util.List<CollaborationRepository.Review>>();
        var requested=new java.util.LinkedHashMap<UUID,java.util.Map<UUID,java.util.List<UUID>>>();
        for(var row:page.rows()){
            var c=row.card();
            if(c.sourceRestricted()&&c.contextAccount()!=null&&accounts.accountLogo(c.contextAccount())!=null)pictures.put(c.key().id(),"/app/collaboration/personal/tasks/"+c.key().id()+"/account-logo");
            UUID reviewId=c.key().type().equals("REVIEW")?c.key().id():c.alias();
            if(reviewId!=null&&c.account()!=null)requested.computeIfAbsent(c.account(),id->new java.util.LinkedHashMap<>())
                .computeIfAbsent(reviewId,id->new java.util.ArrayList<>()).add(c.key().id());
        }
        requested.forEach((account,ids)->{
            // Bound parameter count; no cross-request cache of permissions or mutable task state.
            var keys=new java.util.ArrayList<>(ids.keySet());
            for(int offset=0;offset<keys.size();offset+=200){
                for(var review:reviews.reviewsByIds(account,keys.subList(offset,Math.min(offset+200,keys.size())),auth.getName())){
                    ids.get(review.id()).forEach(key->details.put(key,review));
                    grouped.computeIfAbsent(account,id->new java.util.ArrayList<>()).add(review);
                }
            }
        });
        grouped.forEach((account,items)->{
            var artwork=reviews.pictures(account,items,auth.getName());
            details.forEach((key,review)->{if(artwork.containsKey(review.id())){String url=artwork.get(review.id());
                pictures.put(key,url.matches("/app/catalog/products/[0-9a-fA-F-]{36}/image")?"/app/collaboration/personal/context/"+review.id()+"/picture":url);
            }});
        });
        var buckets=new java.util.LinkedHashMap<String,java.util.List<String>>();
        var windows=CollaborationCompletionWindow.at(java.time.Instant.now(),java.time.ZoneId.of("America/Los_Angeles"));
        var closed=page.rows();
        var dates=new java.util.LinkedHashMap<String,java.time.Instant>();
        closed.forEach(r->dates.put(r.card().key().type()+r.card().key().id(),r.card().created().toInstant()));
        for(var window:windows){
            var ids=closed.stream().skip(5).filter(r->window.contains(r.card().created().toInstant()))
                .map(r->r.card().key().type()+r.card().key().id()).toList();
            if(!ids.isEmpty())buckets.put(window.key(),ids);
        }
        var editable=page.rows().stream().map(r->r.card().account()).filter(java.util.Objects::nonNull).distinct()
            .filter(id->accounts.canOperateAccount(id,auth.getName())).collect(java.util.stream.Collectors.toSet());
        return new Presentation(page.rows(),page.revision(),details,pictures,buckets,dates,windows,editable);
    }
    @GetMapping("/app/collaboration/personal/tasks/{id}/account-logo") @ResponseBody
    ResponseEntity<byte[]> taskLogo(@PathVariable UUID id,Authentication auth){
        var card=queue.authorized(auth.getName(),staff(auth),new PersonalCollaborationQueue.Key("TASK",id));
        if(card.contextAccount()==null)return ResponseEntity.notFound().build();
        var logo=accounts.accountLogo(card.contextAccount());if(logo==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(logo.contentType())).header("X-Content-Type-Options","nosniff").body(logo.bytes());
    }

    @PostMapping("/app/collaboration/personal/position") @ResponseBody
    ResponseEntity<?> position(@RequestParam String type,@RequestParam UUID id,@RequestParam String targetType,
            @RequestParam UUID target,@RequestParam long revision,@RequestParam(defaultValue="BEFORE") String placement,Authentication auth){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(presentation(queue.load(auth.getName(),staff(auth),
                new PersonalCollaborationQueue.Key(type,id),new PersonalCollaborationQueue.Key(targetType,target),revision,"AFTER".equals(placement)),auth));
    }
    @PostMapping("/app/collaboration/personal/open-source")
    String source(@RequestParam UUID id,@RequestParam String returnTo,Authentication auth,HttpSession session){
        var card=queue.authorized(auth.getName(),staff(auth),new PersonalCollaborationQueue.Key("REVIEW",id));
        if(card.account()==null)throw new org.springframework.security.access.AccessDeniedException("Source unavailable.");
        var review=reviews.review(card.account(),id,auth.getName());
        if(review==null)throw new org.springframework.security.access.AccessDeniedException("Source unavailable.");
        if(!returnTo.startsWith("/app/")||returnTo.contains("\\")||returnTo.contains("\r")||returnTo.contains("\n"))throw new IllegalArgumentException("Choose an application source.");
        var option=accounts.listAccountOptions(auth.getName(),staff(auth)).stream().filter(a->a.id().equals(card.account())).findFirst()
            .orElseThrow(()->new org.springframework.security.access.AccessDeniedException("Source unavailable."));
        var store=option.stores().stream().filter(s->s.id().equals(review.storeId())).findFirst().orElse(null);
        if(review.storeId()!=null&&store==null)throw new org.springframework.security.access.AccessDeniedException("Source store access required.");
        session.setAttribute(AccountSelectionController.TENANT_ID,option.id());session.setAttribute(AccountSelectionController.TENANT_NAME,option.name());
        if(store==null){session.removeAttribute(AccountSelectionController.STORE_ID);session.removeAttribute(AccountSelectionController.STORE_NAME);}
        else{session.setAttribute(AccountSelectionController.STORE_ID,store.id());session.setAttribute(AccountSelectionController.STORE_NAME,store.name());}
        if("SHIPMENT".equals(review.subjectType())&&returnTo.startsWith("/app/receiving")&&review.subjectKey()!=null){
            try{return "redirect:/app/receiving/"+UUID.fromString(review.subjectKey());}catch(IllegalArgumentException ignored){/* Non-receiving shipment keys retain their source URL. */}
        }
        return "redirect:"+returnTo;
    }

    @PostMapping("/app/collaboration/personal/open-review")
    String openReview(@RequestParam UUID id,Authentication auth,HttpSession session){
        var key=new PersonalCollaborationQueue.Key("REVIEW",id);
        var card=queue.authorized(auth.getName(),staff(auth),key);
        if (!card.account().equals(session.getAttribute(AccountSelectionController.TENANT_ID))) {
            session.setAttribute(AccountSelectionController.TENANT_ID,card.account());
            session.setAttribute(AccountSelectionController.TENANT_NAME,card.context());
            session.removeAttribute(AccountSelectionController.STORE_ID);
            session.removeAttribute(AccountSelectionController.STORE_NAME);
        }
        return "redirect:/app/collaboration?threadId="+id;
    }

    @PostMapping("/app/collaboration/personal/reviews/{id}/assignees") @ResponseBody
    Object assign(@PathVariable UUID id,@RequestParam(required=false) java.util.Set<UUID> people,Authentication auth){
        return actions.assign(auth.getName(),staff(auth),id,people==null?java.util.Set.of():people);
    }
    @PostMapping("/app/collaboration/personal/reviews/{id}/due-date") @ResponseBody
    Object due(@PathVariable UUID id,@RequestParam(required=false) String dueDate,
            @RequestParam(defaultValue="America/Los_Angeles") String timeZone,Authentication auth){
        return actions.due(auth.getName(),staff(auth),id,dueDate==null||dueDate.isBlank()?null:java.time.LocalDate.parse(dueDate),timeZone);
    }

    @GetMapping("/app/collaboration/personal/members") @ResponseBody
    Object members(@RequestParam UUID account,Authentication auth){
        if(!accounts.canOperateAccount(account,auth.getName()))throw new org.springframework.security.access.AccessDeniedException("Task creation is unavailable.");
        return reviews.members(account);
    }
    @PostMapping("/app/collaboration/personal/create") @ResponseBody
    ResponseEntity<?> create(@RequestParam UUID account,@RequestParam(required=false) UUID store,
            @RequestParam String title,@RequestParam(defaultValue="") String description,
            @RequestParam(required=false) java.util.Set<UUID> people,@RequestParam(required=false) String dueDate,
            @RequestParam(defaultValue="America/Los_Angeles") String timeZone,Authentication auth){
        try{
            actions.create(auth.getName(),staff(auth),account,store,title,description,people==null?java.util.Set.of():people,
                dueDate==null||dueDate.isBlank()?null:java.time.LocalDate.parse(dueDate),timeZone);
            return ResponseEntity.ok(java.util.Map.of("url","/app/collaboration"));
        }catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(java.util.Map.of("error",ex.getMessage()));}
    }

    @PostMapping("/app/collaboration/personal/state") @ResponseBody
    ResponseEntity<Void> state(@RequestParam String type,@RequestParam UUID id,@RequestParam boolean close,Authentication auth){
        actions.change(auth.getName(),staff(auth),new PersonalCollaborationQueue.Key(type,id),close);
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler({CollaborationTaskQueueRepository.QueueChanged.class,org.springframework.dao.CannotSerializeTransactionException.class})
    ResponseEntity<?> changed(){return ResponseEntity.status(409).build();}
}
