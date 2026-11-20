package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CollaborationBoardController {
    private final ParentTenantRepository parents;private final CollaborationTaskQueueRepository queue;
    private final CollaborationBoardRepository board;private final WorkspaceAccessRepository accounts;
    public CollaborationBoardController(ParentTenantRepository parents,CollaborationTaskQueueRepository queue,CollaborationBoardRepository board,WorkspaceAccessRepository accounts){this.parents=parents;this.queue=queue;this.board=board;this.accounts=accounts;}
    private boolean admin(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));}
    private List<WorkspaceAccessRepository.AccountOption> options(UUID parent,Authentication auth){
        Set<UUID> included=parents.accounts(parent,auth.getName());
        return accounts.listAccountOptions(auth.getName(),admin(auth)).stream().filter(a->included.contains(a.id())).toList();
    }
    private void validateScope(UUID parent,UUID account,UUID store,Authentication auth){
        if(account==null&&store==null){parents.accounts(parent,auth.getName());return;}
        var choices=options(parent,auth);
        if(account!=null&&choices.stream().noneMatch(a->a.id().equals(account)))throw new org.springframework.security.access.AccessDeniedException("This account is not available.");
        if(store!=null&&choices.stream().filter(a->account==null||a.id().equals(account)).flatMap(a->a.stores().stream()).noneMatch(s->s.id().equals(store)))throw new org.springframework.security.access.AccessDeniedException("This marketplace is not available.");
    }
    @GetMapping("/app/collaboration/board")
    String index(@RequestParam(required=false) UUID parent,Authentication auth,HttpSession session,Model model){
        PageController.addTenantModel(session,model);
        PageController.addAccessModel(auth,model);var choices=parents.available(auth.getName());
        UUID selected=parent;
        if(selected==null&&!choices.isEmpty())selected=choices.getFirst().id();
        UUID finalSelected=selected;if(selected!=null&&choices.stream().noneMatch(p->p.id().equals(finalSelected)))throw new org.springframework.security.access.AccessDeniedException("Choose an available parent tenant.");
        model.addAttribute("parents",choices);model.addAttribute("selectedParent",selected);model.addAttribute("boardUser",auth.getName());
        model.addAttribute("boardAccounts",selected==null?List.of():options(selected,auth));
        model.addAttribute("boardPeople",selected==null?List.of():parents.members(selected,auth.getName()));
        model.addAttribute("canSetupParents",admin(auth));return "collaboration-board";
    }
    record TenantQueue(ParentTenantRepository.Parent tenant, boolean canOpen, List<QueueEntry> entries) {}
    record QueueEntry(UUID id, String title, String state, Integer position, String account) {}

    @GetMapping("/app/collaboration/board/overall")
    String overall(Authentication auth, HttpSession session, Model model) {
        var directory=parents.staffDirectory(auth.getName());
        var memberships=parents.available(auth.getName()).stream().map(ParentTenantRepository.Parent::id)
                .collect(java.util.stream.Collectors.toSet());
        var groups=new ArrayList<TenantQueue>();
        for(var tenant:directory) {
            var entries=new ArrayList<QueueEntry>();
            if(memberships.contains(tenant.id())) {
                var labels=new HashMap<UUID,String>();
                options(tenant.id(),auth).forEach(a->labels.put(a.id(),a.name()));
                for(String state:List.of("OPEN","IN_PROGRESS"))
                    for(var task:board.page(tenant.id(),auth.getName(),state,false,null,null,"",5,0,null,null,null,null,"UTC").cards())
                        entries.add(new QueueEntry(task.id(),task.title(),task.state(),task.position(),
                                task.accountId()==null?"Tenant task":labels.getOrDefault(task.accountId(),"Restricted account")));
                entries.sort(Comparator.comparing(QueueEntry::position));
            }
            groups.add(new TenantQueue(tenant,memberships.contains(tenant.id()),entries));
        }
        PageController.addTenantModel(session,model);
        PageController.addAccessModel(auth,model);
        model.addAttribute("overallGroups",groups);
        model.addAttribute("canSetupParents",true);
        return "collaboration-board";
    }

    @GetMapping("/app/collaboration/board/data") @ResponseBody
    ResponseEntity<?> data(@RequestParam UUID parent,@RequestParam String state,@RequestParam(defaultValue="false") boolean mine,
        @RequestParam(required=false) UUID account,@RequestParam(required=false) UUID store,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="5") int size,@RequestParam(defaultValue="0") int after,
        @RequestParam(required=false) Instant before,@RequestParam(required=false) UUID beforeId,@RequestParam(required=false) Instant asOf,
        @RequestParam(required=false) String group,@RequestParam(defaultValue="America/Los_Angeles") String zone,Authentication auth){
        validateScope(parent,account,store,auth);
        var result=board.page(parent,auth.getName(),state,mine,account,store,q,size,after,before,beforeId,asOf,group,zone);
        // Account/store IDs and display names are not linked-record access grants.
        var choices=options(parent,auth);Set<UUID> visibleAccounts=new HashSet<>(),visibleStores=new HashSet<>();
        choices.forEach(a->{visibleAccounts.add(a.id());a.stores().forEach(s->visibleStores.add(s.id()));});
        var safe=result.cards().stream().map(c->new CollaborationBoardRepository.Card(c.id(),c.title(),c.state(),c.position(),visibleAccounts.contains(c.accountId())?c.accountId():null,visibleStores.contains(c.storeId())?c.storeId():null,c.dueDate(),c.timeZone(),c.closedAt(),c.assignees(),(c.accountId()!=null&&!visibleAccounts.contains(c.accountId()))||(c.storeId()!=null&&!visibleStores.contains(c.storeId())))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollaborationBoardRepository.Page(safe,result.more(),result.revision(),result.asOf(),result.groups()));
    }
    @PostMapping("/app/collaboration/board/tasks") @ResponseBody
    Object create(@RequestParam UUID parent,@RequestParam(required=false) UUID account,@RequestParam(required=false) UUID store,
        @RequestParam String title,@RequestParam(defaultValue="") String body,@RequestParam(required=false) Set<UUID> people,@RequestParam(required=false) java.time.LocalDate date,@RequestParam(defaultValue="America/Los_Angeles") String zone,Authentication auth){
        validateScope(parent,account,store,auth);return queue.create(parent,account,store,title,body,auth.getName(),people==null?Set.of():people,date,zone);
    }
    @PostMapping("/app/collaboration/board/tasks/{id}/position") @ResponseBody
    Object move(@PathVariable UUID id,@RequestParam UUID parent,@RequestParam int position,@RequestParam long revision,Authentication auth){return queue.move(parent,id,auth.getName(),position,revision);}
    @PostMapping("/app/collaboration/board/tasks/{id}/state") @ResponseBody
    Object state(@PathVariable UUID id,@RequestParam UUID parent,@RequestParam String state,@RequestParam long revision,Authentication auth){return queue.state(parent,id,auth.getName(),state,revision);}
    @PostMapping("/app/collaboration/board/tasks/{id}/assignees") @ResponseBody
    Object assign(@PathVariable UUID id,@RequestParam UUID parent,@RequestParam(required=false) Set<UUID> people,Authentication auth){queue.assign(parent,id,auth.getName(),people==null?Set.of():people,false);return Map.of("saved",true);}
    @GetMapping("/app/collaboration/board/tasks/{id}/assignees") @ResponseBody
    Object assignees(@PathVariable UUID id,@RequestParam UUID parent,Authentication auth){var assigned=queue.assignments(parent,id,auth.getName());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("assigned",assigned,"people",parents.members(parent,auth.getName())));}
    @PostMapping("/app/collaboration/board/tasks/{id}/leave-assignment") @ResponseBody
    Object unassign(@PathVariable UUID id,@RequestParam UUID parent,Authentication auth){queue.assign(parent,id,auth.getName(),Set.of(),true);return Map.of("saved",true);}
    @PostMapping("/app/collaboration/board/tasks/{id}/due-date") @ResponseBody
    Object due(@PathVariable UUID id,@RequestParam UUID parent,@RequestParam(required=false) java.time.LocalDate date,@RequestParam(defaultValue="America/Los_Angeles") String zone,Authentication auth){queue.due(parent,id,auth.getName(),date,zone);return Map.of("saved",true);}
    @GetMapping("/app/collaboration/board/tasks/{id}") @ResponseBody
    ResponseEntity<?> task(@PathVariable UUID id, @RequestParam UUID parent, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("id", id, "title", board.taskTitle(parent, id, auth.getName())));
    }
    @GetMapping("/app/collaboration/board/tasks/{id}/messages") @ResponseBody
    Object messages(@PathVariable UUID id,@RequestParam UUID parent,@RequestParam(required=false) Instant before,@RequestParam(required=false) UUID beforeId,Authentication auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(board.messages(parent,id,auth.getName(),before,beforeId));}
    @PostMapping("/app/collaboration/board/tasks/{id}/messages") @ResponseBody
    Object reply(@PathVariable UUID id,@RequestParam UUID parent,@RequestParam(defaultValue="") String body,@RequestParam(required=false) List<org.springframework.web.multipart.MultipartFile> attachments,Authentication auth){board.reply(parent,id,auth.getName(),body,CollaborationController.attachments(attachments));return Map.of("saved",true);}
    @GetMapping("/app/collaboration/board/tasks/{id}/files/{fileId}") @ResponseBody
    ResponseEntity<byte[]> file(@PathVariable UUID id,@PathVariable UUID fileId,@RequestParam UUID parent,Authentication auth){
        var file=board.file(parent,id,fileId,auth.getName());if(file==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.contentType()))
            .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(file.bytes());
    }
    @GetMapping("/app/platform/parent-tenants")
    String setup(Authentication auth,Model model){PageController.addAccessModel(auth,model);model.addAttribute("accounts",accounts.listAllAccounts());model.addAttribute("people",parents.setupPeople(auth.getName()));return "parent-tenants";}
    @PostMapping("/app/platform/parent-tenants")
    String setup(@RequestParam String name,@RequestParam UUID manager,@RequestParam String managerRole,@RequestParam Set<UUID> accounts,@RequestParam(required=false) Set<UUID> people,Authentication auth,RedirectAttributes redirect){
        try{UUID id=parents.create(auth.getName(),name,accounts,people==null?Set.of():people,manager,managerRole);return "redirect:/app/collaboration/board";}
        catch(org.springframework.dao.DataIntegrityViolationException e){redirect.addFlashAttribute("error","An account or person is already assigned to another tenant. Review the selected accounts and people.");return "redirect:/app/platform/parent-tenants";}
    }
    @ExceptionHandler(CollaborationTaskQueueRepository.QueueChanged.class) @ResponseBody
    ResponseEntity<?> conflict(RuntimeException e){return ResponseEntity.status(409).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class}) @ResponseBody
    ResponseEntity<?> invalid(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("error","Check the task details and selected scope, then try again."));}
}
