package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.PageController;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Controller
public class CollaborationController {
    public record Conversation(CollaborationRepository.Review review,List<CollaborationRepository.Message> messages,String messageType) {}
    private static final Set<String> ATTACHMENT_TYPES=Set.of("image/png","image/jpeg","image/webp","application/pdf",
        "text/plain","text/csv","application/csv","application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final CollaborationRepository repository;
    private final CollaborationMentionService mentions;
    private final ObjectMapper json;

    public CollaborationController(CollaborationRepository repository,CollaborationMentionService mentions,ObjectMapper json){
        this.repository=repository;this.mentions=mentions;this.json=json;
    }

    @GetMapping("/app/collaboration")
    String index(@RequestParam(defaultValue="ACTIVE") String view,@RequestParam(required=false) String entityType,
            @RequestParam(required=false) String subjectType,@RequestParam(required=false) String subjectKey,
            @RequestParam(required=false) UUID threadId,HttpSession session,Authentication authentication,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(authentication,model);UUID tenantId=tenant(session);String selectedView=normalizeView(view);
        String selectedEntity=blankToNull(entityType!=null?entityType:subjectType);
        model.addAttribute("reviews",repository.reviews(tenantId,selectedView,selectedEntity,subjectKey,authentication.getName()));
        model.addAttribute("members",repository.members(tenantId));model.addAttribute("selectedView",selectedView);
        model.addAttribute("selectedEntity",selectedEntity);model.addAttribute("subjectKey",subjectKey);model.addAttribute("threadId",threadId);
        var returnTo=UriComponentsBuilder.fromPath("/app/collaboration").queryParam("view",selectedView);
        if(selectedEntity!=null)returnTo.queryParam("entityType",selectedEntity);if(subjectKey!=null&&!subjectKey.isBlank())returnTo.queryParam("subjectKey",subjectKey);
        model.addAttribute("currentReturnTo",returnTo.build().encode().toUriString());return "collaboration";
    }

    @PostMapping("/app/collaboration/reviews")
    String create(@RequestParam String subjectType,@RequestParam String subjectKey,@RequestParam String subjectLabel,
            @RequestParam(required=false) String marketplace,@RequestParam(defaultValue="CONVERSATION") String actionKind,
            @RequestParam(required=false) String title,@RequestParam(required=false) String message,
            @RequestParam(defaultValue="TEAM_CHAT") String messageType,@RequestParam(required=false) String contextSnapshot,
            @RequestParam(required=false) String parentUrl,@RequestParam(required=false) UUID assignedTo,
            @RequestParam(required=false) String dueDate,@RequestParam(defaultValue="/app/collaboration") String returnTo,
            @RequestParam(name="attachments",required=false) List<MultipartFile> attachments,
            HttpSession session,Authentication authentication,RedirectAttributes redirect){
        UUID tenantId=tenant(session);
        try{
            if(subjectType.isBlank()||subjectKey.isBlank()||subjectLabel.isBlank())throw new IllegalArgumentException("Choose the record for this conversation.");
            var files=attachments(attachments);if((message==null||message.isBlank())&&files.isEmpty())throw new IllegalArgumentException("Write a message or attach a file.");
            String type=normalizeMessageType(messageType),safeTitle=title==null||title.isBlank()?"Conversation about "+subjectLabel:title;
            String safeMessage=message==null||message.isBlank()?"Shared an attachment.":clean(message,4000);
            Instant due=dueDate==null||dueDate.isBlank()?null:LocalDate.parse(dueDate).atTime(17,0).atZone(ZoneId.systemDefault()).toInstant();
            var posted=repository.create(tenantId,clean(subjectType,40),clean(subjectKey,240),clean(subjectLabel,300),blankToNull(marketplace),
                clean(actionKind,50),clean(safeTitle,240),safeMessage,type,snapshot(contextSnapshot),safeParentUrl(parentUrl),
                authentication.getName(),assignedTo,due,files);
            if("TEAM_CHAT".equals(type))mentions.queue(tenantId,posted,safeMessage,authentication.getName());
            redirect.addFlashAttribute("collaborationSuccess","PRIVATE_NOTE".equals(type)?"Private note saved.":"Conversation started.");
        }catch(IllegalArgumentException ex){redirect.addFlashAttribute("collaborationError",ex.getMessage());}
        catch(Exception ex){redirect.addFlashAttribute("collaborationError","The message could not be saved. Nothing was lost; please try again.");}
        return "redirect:"+safeReturn(returnTo);
    }

    @PostMapping("/app/collaboration/reviews/{reviewId}/reply")
    String reply(@PathVariable UUID reviewId,@RequestParam(required=false) String body,
            @RequestParam(defaultValue="TEAM_CHAT") String messageType,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="/app/collaboration") String returnTo,
            @RequestParam(name="attachments",required=false) List<MultipartFile> attachments,
            HttpSession session,Authentication authentication,RedirectAttributes redirect){
        UUID tenantId=tenant(session);
        try{
            var files=attachments(attachments);String type=normalizeMessageType(messageType);
            if((body==null||body.isBlank())&&files.isEmpty()&&status==null)return "redirect:"+safeReturn(returnTo);
            String safeBody=body==null||body.isBlank()?null:clean(body,4000);
            var posted=repository.reply(tenantId,reviewId,authentication.getName(),safeBody,type,status,files);
            if("CLOSED".equals(status))mentions.queueCompletion(tenantId,posted,authentication.getName());
            else if("TEAM_CHAT".equals(type))mentions.queue(tenantId,posted,safeBody,authentication.getName());
        }catch(IllegalArgumentException ex){redirect.addFlashAttribute("collaborationError",ex.getMessage());}
        catch(Exception ex){redirect.addFlashAttribute("collaborationError","The message could not be saved. Please try again.");}
        return "redirect:"+safeReturn(returnTo);
    }

    @PostMapping("/app/collaboration/reviews/{reviewId}/reopen")
    String reopen(@PathVariable UUID reviewId,@RequestParam(defaultValue="/app/collaboration?view=ACTIVE") String returnTo,
            HttpSession session,Authentication authentication,RedirectAttributes redirect){
        try{repository.reopen(tenant(session),reviewId,authentication.getName());redirect.addFlashAttribute("collaborationSuccess","Conversation reopened.");}
        catch(IllegalArgumentException ex){redirect.addFlashAttribute("collaborationError",ex.getMessage());}
        catch(Exception ex){redirect.addFlashAttribute("collaborationError","The conversation could not be reopened. Please try again.");}
        return "redirect:"+safeReturn(returnTo);
    }

    @GetMapping("/app/collaboration/members") @ResponseBody
    List<CollaborationRepository.Member> members(HttpSession session){return repository.members(tenant(session));}

    @GetMapping("/app/collaboration/subject") @ResponseBody
    List<Conversation> subject(@RequestParam String subjectType,@RequestParam String subjectKey,HttpSession session,Authentication authentication){
        return repository.subjectReviews(tenant(session),subjectType,subjectKey,true,authentication.getName()).stream()
            .map(review->new Conversation(review,List.of(),"SUMMARY")).toList();
    }

    @GetMapping("/app/collaboration/summaries") @ResponseBody
    Map<String,CollaborationRepository.SubjectSummary> summaries(@RequestParam String entityType,
            @RequestParam(name="entityId") List<String> entityIds,HttpSession session,Authentication authentication){
        if(entityIds.size()>250)throw new IllegalArgumentException("Request up to 250 collaboration summaries at a time.");
        return repository.openSubjectSummaries(tenant(session),clean(entityType,40),
            entityIds.stream().filter(id->id!=null&&!id.isBlank()).distinct().toList(),authentication.getName());
    }

    @GetMapping("/app/collaboration/reviews/{reviewId}") @ResponseBody
    ResponseEntity<Conversation> conversation(@PathVariable UUID reviewId,@RequestParam(defaultValue="TEAM_CHAT") String messageType,
            HttpSession session,Authentication authentication){
        String type=normalizeMessageType(messageType);var review=repository.review(tenant(session),reviewId,authentication.getName());
        if(review==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new Conversation(review,repository.messages(tenant(session),reviewId,authentication.getName(),type),type));
    }

    @GetMapping("/app/collaboration/attachments/{attachmentId}") @ResponseBody
    ResponseEntity<byte[]> attachment(@PathVariable UUID attachmentId,HttpSession session,Authentication authentication){
        var attachment=repository.attachment(tenant(session),attachmentId,authentication.getName());if(attachment==null)return ResponseEntity.notFound().build();
        String encoded=URLEncoder.encode(attachment.fileName(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(attachment.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+encoded)
            .header("X-Content-Type-Options","nosniff").body(attachment.bytes());
    }

    private List<CollaborationRepository.AttachmentUpload> attachments(List<MultipartFile> files){
        if(files==null)return List.of();var present=files.stream().filter(file->file!=null&&!file.isEmpty()).toList();
        if(present.size()>3)throw new IllegalArgumentException("Attach up to three files to one message.");
        long total=present.stream().mapToLong(MultipartFile::getSize).sum();if(total>10_000_000)throw new IllegalArgumentException("Keep all attachments under 10 MB per message.");
        return present.stream().map(file->{
            if(file.getSize()>5_000_000)throw new IllegalArgumentException("Keep each attachment under 5 MB.");
            String type=file.getContentType()==null?"application/octet-stream":file.getContentType().toLowerCase(Locale.ROOT);
            if(!ATTACHMENT_TYPES.contains(type))throw new IllegalArgumentException("Attach an image, PDF, text, CSV, XLS, or XLSX file.");
            String name=file.getOriginalFilename()==null?"attachment":file.getOriginalFilename().replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).trim();
            if(name.isBlank())name="attachment";if(name.length()>255)name=name.substring(name.length()-255);
            try{return new CollaborationRepository.AttachmentUpload(name,type,file.getBytes());}catch(java.io.IOException ex){throw new IllegalArgumentException("One attachment could not be read.");}
        }).toList();
    }
    private String snapshot(String value){
        if(value==null||value.isBlank())return "{}";if(value.length()>16_000)throw new IllegalArgumentException("The record snapshot is too large.");
        try{var node=json.readTree(value);if(node==null||!node.isObject())throw new IllegalArgumentException("The record snapshot is invalid.");return json.writeValueAsString(node);}
        catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("The record snapshot is invalid.");}
    }
    private static UUID tenant(HttpSession session){Object selected=session.getAttribute(AccountSelectionController.TENANT_ID);if(selected instanceof UUID tenantId)return tenantId;throw new IllegalArgumentException("Choose an account first.");}
    private static String normalizeMessageType(String value){return "PRIVATE_NOTE".equalsIgnoreCase(value)?"PRIVATE_NOTE":"TEAM_CHAT";}
    private static String normalizeView(String value){String view=value==null?"ACTIVE":value.toUpperCase(Locale.ROOT);return Set.of("ACTIVE","MENTIONS","PRIVATE","CLOSED","ALL").contains(view)?view:"ACTIVE";}
    private static String clean(String value,int max){String result=value==null?"":value.trim();if(result.length()>max)throw new IllegalArgumentException("Keep the conversation details concise.");return result;}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String safeParentUrl(String value){return value!=null&&value.startsWith("/app")&&!value.startsWith("//")?value:null;}
    private static String safeReturn(String value){return value!=null&&value.startsWith("/app")&&!value.startsWith("//")?value:"/app/collaboration";}
}
