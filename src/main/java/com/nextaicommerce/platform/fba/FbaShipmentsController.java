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
    @org.springframework.beans.factory.annotation.Autowired(required=false) private FbaShipmentHierarchy hierarchy;
    private final FbaReadRepository repository;
    private final WorkspaceAccessRepository workspace;
    @org.springframework.beans.factory.annotation.Value("${app.fba.replenishment-enabled:false}") private boolean replenishmentEnabled;
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
    @org.springframework.beans.factory.annotation.Autowired private FbaSkuShipmentHistory skuHistory;
    @GetMapping("/app/inventory/fba-shipments/sku-history") @ResponseBody
    Object skuHistory(@RequestParam String sku,@RequestParam UUID connection,HttpSession session){
        var scope=selection(session,connection);var market=workspace.findConnection(scope.tenant(),scope.connection()).marketplaceId();
        return replenishmentEnabled?skuHistory.groups(scope.tenant(),scope.connection(),market,sku):skuHistory.recent(scope.tenant(),scope.connection(),market,sku);
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
    Object batch(@RequestParam java.util.List<String> shipments,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);var value=batches.preview(scope.tenant(),scope.connection(),shipments);return java.util.Map.of("plans",value.plans(),"blockers",value.blockers(),"revision",value.revision(),"presentation",depletion.presentation(scope.tenant(),scope.connection(),value.plans().stream().flatMap(p->p.shipment().lines().stream()).map(FbaDepletionRepository.Line::sku).distinct().toList()));}
    @PostMapping("/app/inventory/fba-shipments/depletion/batch-confirm") @ResponseBody
    Object batchConfirm(@RequestBody BatchConfirmation request,Authentication auth,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return java.util.Map.of("ids",batches.confirm(scope.tenant(),scope.connection(),auth.getName(),request.requestId(),request.revision(),request.choices()),"message","Stock deducted for the complete selection.");}
    @PostMapping("/app/inventory/fba-shipments/depletion/refresh-items") @ResponseBody
    Object refreshItems(@RequestBody SelectedItems request,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);try{if(sync==null)throw new IllegalStateException("Shipment item refresh is unavailable.");return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("job",sync.requestItems(scope.tenant(),scope.connection(),request.shipments())));}catch(IllegalStateException e){return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("error",e.getMessage()));}}
    @org.springframework.beans.factory.annotation.Autowired private FbaDeductionAdjustments adjustments;
    @GetMapping("/app/inventory/fba-shipments/depletion/adjustment") @ResponseBody
    Object deductionHistory(@RequestParam String shipment,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return adjustments.review(scope.tenant(),scope.connection(),shipment,null);}
    @PostMapping("/app/inventory/fba-shipments/depletion/adjustment-review") @ResponseBody
    Object reviewAdjustment(@RequestBody FbaDeductionAdjustments.Request request,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);return adjustments.review(scope.tenant(),scope.connection(),request.shipment(),request.quantities());}
    @PostMapping("/app/inventory/fba-shipments/depletion/adjustment-confirm") @ResponseBody
    Object confirmAdjustment(@RequestBody FbaDeductionAdjustments.Request request,Authentication auth,@RequestParam UUID connection,HttpSession session){requireManual();var scope=selection(session,connection);var id=adjustments.confirm(scope.tenant(),scope.connection(),auth.getName(),request);return java.util.Map.of("id",id,"message","Adjustment recorded","review",adjustments.review(scope.tenant(),scope.connection(),request.shipment(),null));}
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
        return sync(session,false);
    }
    @PostMapping("/app/inventory/fba-shipments/sync-parents") @ResponseBody
    org.springframework.http.ResponseEntity<?> syncParents(HttpSession session){return sync(session,true);}
    private org.springframework.http.ResponseEntity<?> sync(HttpSession session,boolean parentsOnly){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID),connection=(UUID)session.getAttribute(AccountSelectionController.STORE_ID);
        if(tenant==null||connection==null)return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error","Choose an Amazon store."));
        workspace.findConnection(tenant,connection);
        try{if(sync==null)throw new IllegalStateException("FBA sync is unavailable.");return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("job",parentsOnly?sync.requestParents(tenant,connection):sync.request(tenant,connection)));}
        catch(IllegalArgumentException|IllegalStateException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error",e.getMessage()));}
    }
    @GetMapping("/app/inventory/fba-shipments/sync") @ResponseBody
    Object syncState(HttpSession session){
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID),connection=(UUID)session.getAttribute(AccountSelectionController.STORE_ID);
        workspace.findConnection(tenant,connection);return sync.state(tenant,connection);
    }
    @org.springframework.beans.factory.annotation.Autowired private FbaParentActions parentActions;
    @GetMapping("/app/inventory/fba-shipments/parent-items") @ResponseBody
    Object parentItems(@RequestParam String parent,@RequestParam UUID connection,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,HttpSession session){var s=selection(session,connection);return parentActions.contents(s.tenant(),s.connection(),parent,q,page);}
    @GetMapping("/app/inventory/fba-shipments/parent-review") @ResponseBody
    Object parentReview(@RequestParam String parent,@RequestParam UUID connection,HttpSession session){requireManual();var s=selection(session,connection);var r=parentActions.preview(s.tenant(),s.connection(),parent);return java.util.Map.of("group",r.group(),"plans",r.plans(),"blockers",r.blockers(),"revision",r.revision(),"presentation",depletion.presentation(s.tenant(),s.connection(),r.plans().stream().flatMap(p->p.shipment().lines().stream()).map(FbaDepletionRepository.Line::sku).distinct().toList()));}
    @GetMapping("/app/inventory/fba-shipments/parent-visibility") @ResponseBody
    Object parentVisibility(@RequestParam String parent,@RequestParam UUID connection,HttpSession session){var s=selection(session,connection);return parentActions.group(s.tenant(),s.connection(),parent);}
    @PostMapping("/app/inventory/fba-shipments/parent-confirm") @ResponseBody
    Object parentConfirm(@RequestBody FbaParentActions.Request r,@RequestParam UUID connection,Authentication auth,HttpSession session){requireManual();var s=selection(session,connection);return java.util.Map.of("ids",parentActions.confirm(s.tenant(),s.connection(),auth.getName(),r),"message","Stock decreased for all remaining shipments in the plan.");}
    @PostMapping("/app/inventory/fba-shipments/parent-visibility") @ResponseBody
    Object parentVisibility(@RequestBody FbaParentActions.Visibility r,@RequestParam UUID connection,Authentication auth,HttpSession session){var s=selection(session,connection);parentActions.visibility(s.tenant(),s.connection(),auth.getName(),r);return java.util.Map.of("saved",true);}
    @GetMapping("/app/inventory/fba-shipments/items") @ResponseBody
    Object items(@RequestParam String shipment,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,HttpSession session){
        if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant)
            ||!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        workspace.findConnection(tenant,connection);
        var items=repository.items(tenant,connection,shipment,q,page);
        if(!replenishmentEnabled)return items;
        var mapped=depletion.presentation(tenant,connection,items.rows().stream().map(FbaReadRepository.ShipmentItem::sku).toList());
        var quantities=new java.util.LinkedHashMap<String,java.util.List<FbaSkuShipmentHistory.ProductQuantity>>();
        for(var item:items.rows())quantities.put(item.sku(),mapped.stream().filter(m->m.sku().equals(item.sku())).flatMap(m->m.products().stream()).map(c->new FbaSkuShipmentHistory.ProductQuantity(c.itemCode(),c.name(),FbaShipmentParents.cases(item.shipped(),c.eachPerSku(),c.masterCase()))).toList());
        return java.util.Map.of("rows",items.rows(),"total",items.total(),"page",items.page(),"size",items.size(),"costs",items.costs(),"products",quantities);
    }
    @GetMapping("/app/inventory/fba-shipments")
    String shipments(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID warehouse,Authentication auth,HttpSession session,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(auth,model);
        model.addAttribute("fbaReplenishmentEnabled",replenishmentEnabled);
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
        if(warehouse!=null)model.addAttribute("selectedWarehouseDetails",catalog.warehouseDetails(tenant,warehouse));
        model.addAttribute("manualDepletionEnabled",manualEnabled);model.addAttribute("automaticDepletionEnabled",automatic.enabled());model.addAttribute("depletableShipments",views.depletable(tenant,connection));model.addAttribute("depletionStates",views.states(tenant,connection));
        model.addAttribute("query",q.trim());
        model.addAttribute("fbaSync",sync==null?new com.nextaicommerce.platform.sync.FbaManualSync.State(false,false,"NOT_STARTED","",null):sync.state(tenant,connection));
        model.addAttribute("amazonStore","AMAZON".equalsIgnoreCase(store.channel()));
        if("AMAZON".equalsIgnoreCase(store.channel())&&replenishmentEnabled&&hierarchy!=null){var grouped=hierarchy.page(tenant,connection,q,page,size,auth.getName(),status.equals("REMOVED")?"":status,warehouse,status.equals("REMOVED"));model.addAttribute("shipments",grouped.shipments());model.addAttribute("shipmentParents",grouped.parents());model.addAttribute("parentSummaries",grouped.summaries());model.addAttribute("parentDepletion",grouped.depletion());model.addAttribute("depletableParents",grouped.depletableParents());var first=new java.util.HashSet<String>();var seen=new java.util.HashSet<String>();for(var row:grouped.shipments().rows()){var parent=grouped.parents().get(row.id());if(parent!=null&&seen.add(parent.id()))first.add(row.id());}model.addAttribute("firstParentChildren",first);model.addAttribute("shipmentHierarchy",true);model.addAttribute("ambiguousShipmentParents",grouped.ambiguous());}
        else model.addAttribute("shipments", "AMAZON".equalsIgnoreCase(store.channel())?repository.shipments(tenant,connection,q,page,size,auth.getName(),status.equals("REMOVED")?"":status,warehouse,status.equals("REMOVED")):new FbaReadRepository.ShipmentPage(java.util.List.of(),0,0,25));
        return "fba-shipments";
    }
}
