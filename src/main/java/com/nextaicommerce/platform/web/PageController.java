package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PageController {
    private final WorkspaceAccessRepository repository;

    PageController(WorkspaceAccessRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/login") String login() { return "login"; }
    @GetMapping({"/", "/app"})
    String app(@RequestParam(defaultValue = "false") boolean chooseStore,
            Authentication authentication, HttpSession session, Model model) {
        if (!addTenantModel(session, model)) return "redirect:/app/select-account";
        addAccessModel(authentication, model);
        model.addAttribute("chooseStore", chooseStore);
        return "app";
    }

    @GetMapping("/app/users")
    String users(Authentication authentication, HttpSession session, Model model) {
        if (!addTenantModel(session, model)) return "redirect:/app/select-account";
        addAccessModel(authentication, model);
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        String tenantName = (String) session.getAttribute(AccountSelectionController.TENANT_NAME);
        var members = repository.listMembers(tenantId);
        var stores = repository.listConnections(tenantId, tenantName);
        model.addAttribute("members", members);
        model.addAttribute("activeUsers", members.stream().filter(member -> "ACTIVE".equals(member.status())).count());
        model.addAttribute("pendingInvitations", repository.pendingInvitationCount(tenantId));
        model.addAttribute("stores", stores);
        model.addAttribute("connectedStores", stores.size());
        return "users";
    }

    @GetMapping({"/app/orders", "/app/inventory"})
    String module(HttpServletRequest request, Authentication authentication, HttpSession session, Model model) {
        if (!addTenantModel(session, model)) return "redirect:/app/select-account";
        addAccessModel(authentication, model);
        boolean storeSelected = session.getAttribute(AccountSelectionController.STORE_ID) instanceof UUID;
        model.addAttribute("storeSelectionRequired", !storeSelected);
        model.addAttribute("chooseStore", !storeSelected);
        String module = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        model.addAttribute("activeModule", module);
        model.addAttribute("moduleTitle", switch (module) {
            case "orders" -> "Orders";
            case "inventory" -> "Inventory";
            default -> "Workspace";
        });
        model.addAttribute("moduleDescription", switch (module) {
            case "orders" -> "Review and operate orders for the selected marketplace store.";
            case "inventory" -> "Monitor inventory for the selected marketplace store.";
            default -> "Your commerce workspace.";
        });
        return "module";
    }

    static void addAccessModel(Authentication authentication, Model model) {
        Set<String> roles = authentication == null ? Set.of() : authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean superAdmin = roles.contains("ROLE_PLATFORM_ADMIN");
        boolean tenantAdmin = roles.contains("ROLE_OWNER") || roles.contains("ROLE_ADMIN");
        model.addAttribute("isSuperAdmin", superAdmin);
        model.addAttribute("canManageUsers", superAdmin || tenantAdmin);
        model.addAttribute("canManageConnections", superAdmin || tenantAdmin);
        model.addAttribute("canViewOperations", superAdmin || tenantAdmin
            || roles.contains("ROLE_OPERATOR") || roles.contains("ROLE_VIEWER"));
        model.addAttribute("signedInEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("roleLabel", roleLabel(roles));
    }

    static boolean addTenantModel(HttpSession session, Model model) {
        Object tenantId = session.getAttribute(AccountSelectionController.TENANT_ID);
        Object tenantName = session.getAttribute(AccountSelectionController.TENANT_NAME);
        if (!(tenantId instanceof UUID) || !(tenantName instanceof String)) return false;
        model.addAttribute("selectedAccountId", tenantId);
        model.addAttribute("selectedAccountName", tenantName);
        return true;
    }

    private static String roleLabel(Set<String> roles) {
        if (roles.contains("ROLE_PLATFORM_ADMIN")) return "Super Admin";
        if (roles.contains("ROLE_OWNER")) return "Owner";
        if (roles.contains("ROLE_ADMIN")) return "Administrator";
        if (roles.contains("ROLE_OPERATOR")) return "Operator";
        if (roles.contains("ROLE_VIEWER")) return "Viewer";
        return "Member";
    }
}
