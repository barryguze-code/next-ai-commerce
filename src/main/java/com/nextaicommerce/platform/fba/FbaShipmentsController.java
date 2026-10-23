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
    public FbaShipmentsController(FbaReadRepository repository,WorkspaceAccessRepository workspace){
        this.repository=repository;this.workspace=workspace;
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
        if(!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID connection))
            return "redirect:/app/select-account";
        var store=workspace.findConnection(tenant,connection);
        model.addAttribute("query",q.trim());
        model.addAttribute("amazonStore","AMAZON".equalsIgnoreCase(store.channel()));
        model.addAttribute("shipments", "AMAZON".equalsIgnoreCase(store.channel())
            ?repository.shipments(tenant,connection,q,page,size)
            :new FbaReadRepository.ShipmentPage(java.util.List.of(),0,0,25));
        return "fba-shipments";
    }
}
