package com.nextaicommerce.platform.fba;

import com.nextaicommerce.platform.web.*;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class FbaShipmentsController {
    private final FbaReadRepository repository;
    private final WorkspaceAccessRepository workspace;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.nextaicommerce.platform.sync.FbaManualSync sync;
    public FbaShipmentsController(FbaReadRepository repository,WorkspaceAccessRepository workspace){
        this.repository=repository;this.workspace=workspace;
    }
    @org.springframework.beans.factory.annotation.Autowired private FbaDepletionRepository depletion;
    @org.springframework.beans.factory.annotation.Autowired private FbaWorkspaceRepository views;
    @org.springframework.beans.factory.annotation.Autowired private com.nextaicommerce.platform.catalog.CatalogRepository catalog;
    @org.springframework.beans.factory.annotation.Value("${app.fba.depletion.manual-enabled:false}") private boolean manualEnabled;
    @org.springframework.beans.factory.annotation.Autowired private FbaAutomaticDepletion automatic;
    private record Selection(UUID tenant,UUID connection){}
    private Selection selection(HttpSession session,UUID expectedConnection){
        if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant) || !(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        if(!connection.equals(expectedConnection)||!"AMAZON".equalsIgnoreCase(workspace.findConnection(tenant,connection).channel()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        return new Selection(tenant,connection);
    }
    private void requireManual(){if(!manualEnabled)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Manual depletion is not enabled.");}
    @GetMapping("/app/inventory/fba-shipments/depletion") @ResponseBody
    Object preview(@RequestParam String shipment,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return depletion.preview(scope.tenant(),scope.connection(),shipment);}
    public record Correction(String shipment,String sku,int units,String reason,String revision){}
    public record Confirmation(String shipment,UUID requestId,String revision,java.util.List<FbaDepletionRepository.Allocation> allocations){}
    @PostMapping("/app/inventory/fba-shipments/depletion/correction") @ResponseBody
    Object correct(@RequestBody Correction request,Authentication auth,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);depletion.correct(scope.tenant(),scope.connection(),request.shipment(),auth.getName(),request.sku(),request.units(),request.reason(),request.revision());return depletion.preview(scope.tenant(),scope.connection(),request.shipment());}
    @PostMapping("/app/inventory/fba-shipments/depletion/confirm") @ResponseBody
    Object confirm(@RequestBody Confirmation request,Authentication auth,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);if(request.requestId()==null)throw new IllegalArgumentException("A request ID is required.");return java.util.Map.of("id",depletion.deplete(scope.tenant(),scope.connection(),request.shipment(),auth.getName(),request.requestId(),request.revision(),request.allocations()),"message","Stock depleted");}
    @org.springframework.beans.factory.annotation.Autowired private FbaBatchDeduction batches;
    public record SelectedItems(java.util.List<String> shipments){}
    public record BatchConfirmation(UUID requestId,String revision,java.util.List<FbaBatchDeduction.Choice> choices){}
    @GetMapping("/app/inventory/fba-shipments/depletion/batch") @ResponseBody
    Object batch(@RequestParam java.util.List<String> shipments,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return batches.preview(scope.tenant(),scope.connection(),shipments);}
    @PostMapping("/app/inventory/fba-shipments/depletion/batch-confirm") @ResponseBody
    Object batchConfirm(@RequestBody BatchConfirmation request,Authentication auth,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return java.util.Map.of("ids",batches.confirm(scope.tenant(),scope.connection(),auth.getName(),request.requestId(),request.revision(),request.choices()),"message","Stock deducted for the complete selection.");}
    @PostMapping("/app/inventory/fba-shipments/depletion/refresh-items") @ResponseBody
    Object refreshItems(@RequestBody SelectedItems request,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);try{if(sync==null)throw new IllegalStateException("Shipment item refresh is unavailable.");return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("job",sync.requestItems(scope.tenant(),scope.connection(),request.shipments())));}catch(IllegalStateException e){return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("error",e.getMessage()));}}
    public record SettingsRequest(UUID warehouse,boolean automatic,long revision){}
    @GetMapping("/app/inventory/fba-shipments/depletion/settings") @ResponseBody
    Object settings(@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return java.util.Map.of("enabled",automatic.enabled(),"warehouses",automatic.settings(scope.tenant(),scope.connection()));}
    @PostMapping("/app/inventory/fba-shipments/depletion/settings") @ResponseBody
    Object settings(@RequestBody SettingsRequest request,Authentication auth,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);automatic.configure(scope.tenant(),scope.connection(),request.warehouse(),auth.getName(),request.automatic(),request.revision());return java.util.Map.of("saved",true,"enabled",automatic.enabled(),"message",automatic.enabled()?"Settings saved":"Preference saved; automatic depletion remains off here.");}
    @PostMapping("/app/inventory/fba-shipments/visibility") @ResponseBody
    Object visibility(@RequestParam String shipment,@RequestParam boolean removed,Authentication auth,@RequestParam UUID connection,HttpSession session){var scope=selection(session,connection);depletion.removed(scope.tenant(),scope.connection(),shipment,auth.getName(),removed);return java.util.Map.of("saved",true);}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    org.springframework.http.ResponseEntity<?> invalid(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error",e.getMessage()));}
    @PostMapping("/app/inventory/fba-shipments/sync") @ResponseBody
    org.springframework.http.ResponseEntity<?> sync(HttpSession session){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID),connection=(UUID)session.getAttribute(AccountSelectionController.STORE_ID);
        if(tenant==null||connection==null)return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error","Choose an Amazon store."));
        workspace.findConnection(tenant,connection);
        try{if(sync==null)throw new IllegalStateException("FBA sync is unavailable.");return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("job",sync.request(tenant,connection)));}
        catch(IllegalArgumentException|IllegalStateException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error",e.getMessage()));}
    }
    @GetMapping("/app/inventory/fba-shipments/sync") @ResponseBody
    Object syncState(HttpSession session){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID),connection=(UUID)session.getAttribute(AccountSelectionController.STORE_ID);
        workspace.findConnection(tenant,connection);return sync.state(tenant,connection);
    }
    @GetMapping("/app/inventory/fba-shipments/items") @ResponseBody
    FbaReadRepository.ShipmentItems items(@RequestParam String shipment,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,HttpSession session){
        if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant)
            ||!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        workspace.findConnection(tenant,connection);
        return repository.items(tenant,connection,shipment,q,page);
    }
    @GetMapping("/app/inventory/fba-shipments")
    String shipments(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID warehouse,Authentication auth,HttpSession session,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(auth,model);
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);
        if(!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection)){
            model.addAttribute("query",q.trim());model.addAttribute("amazonStore",false);model.addAttribute("chooseStore",true);
            model.addAttribute("fbaSync",new com.nextaicommerce.platform.sync.FbaManualSync.State(false,false,"NOT_STARTED","",null));
            model.addAttribute("shipments",new FbaReadRepository.ShipmentPage(java.util.List.of(),0,0,25));return "fba-shipments";
        }
        var store=workspace.findConnection(tenant,connection);
        model.addAttribute("fbaConnectionId",connection);model.addAttribute("canManageDepletion",auth.getAuthorities().stream().anyMatch(a->java.util.Set.of("ROLE_PLATFORM_ADMIN","ROLE_OWNER","ROLE_ADMIN").contains(a.getAuthority())));
        model.addAttribute("fbaTabs",views.tabs(tenant,connection,auth.getName()));
        model.addAttribute("fbaWarehouses",catalog.listWarehouses(tenant));
        model.addAttribute("selectedWarehouse",warehouse);model.addAttribute("selectedStatus",status);
        model.addAttribute("manualDepletionEnabled",manualEnabled);model.addAttribute("automaticDepletionEnabled",automatic.enabled());model.addAttribute("depletableShipments",views.depletable(tenant,connection));model.addAttribute("depletionStates",views.states(tenant,connection));
        model.addAttribute("query",q.trim());
        model.addAttribute("fbaSync",sync==null?new com.nextaicommerce.platform.sync.FbaManualSync.State(false,false,"NOT_STARTED","",null):sync.state(tenant,connection));
        model.addAttribute("amazonStore","AMAZON".equalsIgnoreCase(store.channel()));
        model.addAttribute("shipments", "AMAZON".equalsIgnoreCase(store.channel())
            ?repository.shipments(tenant,connection,q,page,size,auth.getName(),status.equals("REMOVED")?"":status,warehouse,status.equals("REMOVED"))
            :new FbaReadRepository.ShipmentPage(java.util.List.of(),0,0,25));
        return "fba-shipments";
    }
}
