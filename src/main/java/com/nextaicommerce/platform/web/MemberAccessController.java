package com.nextaicommerce.platform.web;

import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class MemberAccessController {
    private final MemberAccessService service;
    public MemberAccessController(MemberAccessService service){this.service=service;}
    @PostMapping("/app/users/{userId}/stores/{storeId}/revoke")
    String revokeStore(@PathVariable UUID userId,@PathVariable UUID storeId,Authentication auth,HttpSession session,@RequestParam(required=false) UUID account,RedirectAttributes redirect) {
        UUID tenant=account==null?(UUID)session.getAttribute(AccountSelectionController.TENANT_ID):account;
        if(tenant==null)return "redirect:/app/select-account";
        try{service.revokeStore(tenant,userId,storeId,auth.getName());redirect.addFlashAttribute("invitationSuccess","Store access revoked. Other stores and accounts are unchanged.");}
        catch(IllegalArgumentException e){redirect.addFlashAttribute("invitationError",e.getMessage());}
        return "redirect:/app/users?account="+tenant;
    }
    @PostMapping("/app/users/{userId}/access")
    String update(@PathVariable UUID userId,@RequestParam(defaultValue="VIEWER") String role,
            @RequestParam(required=false) Set<UUID> storeIds,@RequestParam(defaultValue="false") boolean revoke,
            Authentication auth,HttpSession session,@RequestParam(required=false) UUID account,RedirectAttributes redirect) {
        UUID tenant=account==null?(UUID)session.getAttribute(AccountSelectionController.TENANT_ID):account;
        if(tenant==null)return "redirect:/app/select-account";
        try {
            service.update(tenant,userId,auth.getName(),role,storeIds==null?Set.of():storeIds,revoke);
            redirect.addFlashAttribute("invitationSuccess",revoke?"Access to this account and all its stores revoked. Other accounts are unchanged.":"User role and store access updated.");
        }catch(IllegalArgumentException e){redirect.addFlashAttribute("invitationError",e.getMessage());}
        return "redirect:/app/users?account="+tenant;
    }
}
