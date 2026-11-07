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
            @RequestParam(defaultValue="25") int size,Authentication auth,HttpSession session,Model model){
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        PageController.addAccessModel(auth,model);
        UUID tenant=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);
        if(!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection)){
            model.addAttribute("query",q.trim());model.addAttribute("amazonStore",false);model.addAttribute("chooseStore",true);
            model.addAttribute("fbaSync",new com.nextaicommerce.platform.sync.FbaManualSync.State(false,false,"NOT_STARTED","",null));
            model.addAttribute("shipments",new FbaReadRepository.ShipmentPage(java.util.List.of(),0,0,25));return "fba-shipments";
        }
        var store=workspace.findConnection(tenant,connection);
        model.addAttribute("fbaConnectionId",connection);
        model.addAttribute("query",q.trim());
        model.addAttribute("fbaSync",sync==null?new com.nextaicommerce.platform.sync.FbaManualSync.State(false,false,"NOT_STARTED","",null):sync.state(tenant,connection));
        model.addAttribute("amazonStore","AMAZON".equalsIgnoreCase(store.channel()));
        model.addAttribute("shipments", "AMAZON".equalsIgnoreCase(store.channel())
            ?repository.shipments(tenant,connection,q,page,size)
            :new FbaReadRepository.ShipmentPage(java.util.List.of(),0,0,25));
        return "fba-shipments";
    }
}
