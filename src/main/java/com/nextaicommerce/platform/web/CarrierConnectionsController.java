package com.nextaicommerce.platform.web;

import com.nextaicommerce.platform.shipping.CarrierConnectionRepository;
import com.nextaicommerce.platform.shipping.CarrierConnectionService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class CarrierConnectionsController {
    private final CarrierConnectionService connections;private final WorkspaceAccessRepository access;private final MemberAccessService members;
    public CarrierConnectionsController(CarrierConnectionService connections,WorkspaceAccessRepository access,MemberAccessService members){this.connections=connections;this.access=access;this.members=members;}
    @GetMapping("/app/connections/carriers")
    String page(HttpSession session,Authentication authentication,Model model) {
        if(!PageController.addTenantModel(session,model))return "redirect:/app/select-account";
        authorize(session,authentication);PageController.addAccessModel(authentication,model);
        return "carrier-connections";
    }
    @GetMapping("/app/connections/carriers/data") @ResponseBody
    List<CarrierConnectionService.View> list(HttpSession session,Authentication actor){authorize(session,actor);return connections.views(tenant(session));}
    @PostMapping("/app/connections/carriers/save") @ResponseBody
    CarrierConnectionService.View save(@RequestBody CarrierConnectionService.Input input,HttpSession session,Authentication actor){authorize(session,actor);UUID tenant=tenant(session);return connections.view(tenant,connections.save(tenant,input));}
    @PostMapping("/app/connections/carriers/{id}/health") @ResponseBody
    CarrierConnectionService.View health(@PathVariable UUID id,HttpSession session,Authentication actor){authorize(session,actor);UUID tenant=tenant(session);return connections.view(tenant,connections.health(tenant,id));}
    @PostMapping("/app/connections/carriers/{id}/delete") @ResponseBody
    Map<String,Boolean> delete(@PathVariable UUID id,HttpSession session,Authentication actor){authorize(session,actor);connections.delete(tenant(session),id);return Map.of("deleted",true);}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) @ResponseBody
    ResponseEntity<Map<String,String>> invalid(RuntimeException error){return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) @ResponseBody
    ResponseEntity<Map<String,String>> duplicate(){return ResponseEntity.status(409).body(Map.of("message","This FedEx account is already connected in this environment."));}
    private void authorize(HttpSession session,Authentication actor){
        UUID tenant=tenant(session);
        if(actor==null||!actor.isAuthenticated()||!access.canOperateAccount(tenant,actor.getName()))throw new org.springframework.security.access.AccessDeniedException("Carrier administration is unavailable in this account.");
        if(access.isCurrentPlatformAdmin(actor.getName()))return;
        var current=members.current(tenant,actor.getName());
        if(current==null||!java.util.Set.of("OWNER","ADMIN").contains(current.role())
            ||current.restricted()&&(!(session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID store)||!current.stores().contains(store)))
            throw new org.springframework.security.access.AccessDeniedException("Only current account administrators can manage carrier connections.");
    }
    static UUID tenant(HttpSession session){if(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID id)return id;throw new IllegalArgumentException("Choose an account first.");}
}
