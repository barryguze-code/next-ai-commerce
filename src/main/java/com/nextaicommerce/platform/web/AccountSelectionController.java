package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AccountSelectionController {
    static final String TENANT_ID = "selectedTenantId";
    static final String TENANT_NAME = "selectedTenantName";
    private final WorkspaceAccessRepository repository;

    AccountSelectionController(WorkspaceAccessRepository repository) { this.repository = repository; }

    @GetMapping("/app/select-account")
    String select(Authentication authentication, HttpSession session, Model model) {
        PageController.addAccessModel(authentication, model);
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        var accounts = repository.listAvailableAccounts(authentication.getName(), superAdmin);
        if (accounts.size() == 1) {
            session.setAttribute(TENANT_ID, accounts.getFirst().id());
            session.setAttribute(TENANT_NAME, accounts.getFirst().name());
            return "redirect:/app";
        }
        model.addAttribute("accounts", accounts);
        return "select-account";
    }

    @PostMapping("/app/select-account")
    String choose(@RequestParam UUID tenantId, @RequestParam String tenantName,
            Authentication authentication, HttpSession session) {
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        if (!repository.canAccessAccount(tenantId, authentication.getName(), superAdmin))
            throw new org.springframework.security.access.AccessDeniedException("Account access denied");
        session.setAttribute(TENANT_ID, tenantId);
        session.setAttribute(TENANT_NAME, tenantName);
        return "redirect:/app";
    }
}
