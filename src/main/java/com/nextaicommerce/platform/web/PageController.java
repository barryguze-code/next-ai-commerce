package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        var syncStatuses=repository.listCurrentSyncStatuses(tenantId).stream()
            .filter(sync -> "QUEUED".equals(sync.status()) || "RUNNING".equals(sync.status()))
            .toList();
        model.addAttribute("syncStatuses",syncStatuses);
        model.addAttribute("syncInProgress",syncStatuses.stream().anyMatch(sync->
            "QUEUED".equals(sync.status())||"RUNNING".equals(sync.status())));
        model.addAttribute("syncJobsByConnection", repository.listCurrentSyncJobs(tenantId).stream()
            .collect(Collectors.groupingBy(WorkspaceAccessRepository.SyncJobView::connectionId)));
        model.addAttribute("chooseStore", chooseStore);
        return "app";
    }

    @GetMapping("/app/users")
    String users(Authentication authentication, HttpSession session, Model model,
            @RequestParam(required=false) UUID account,@RequestParam(defaultValue="false") boolean invite) {
        addAccessModel(authentication, model);
        String email=authentication.getName();
        boolean staff=repository.isCurrentPlatformAdmin(email);
        var choices=repository.listAccountOptions(email,staff);
        UUID workspace=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);
        UUID target=account==null?workspace:account;
        if(target==null) return "redirect:/app/select-account";
        var selected=choices.stream().filter(a->a.id().equals(target)).findFirst()
            .orElseThrow(()->new org.springframework.security.access.AccessDeniedException("This account is unavailable."));
        boolean manage=repository.canAdministerAccount(target,email,staff);
        model.addAttribute("selectedAccountId",target);
        model.addAttribute("selectedAccountName",selected.name());
        model.addAttribute("workspaceScope",workspace);
        model.addAttribute("filterAccounts",choices);
        model.addAttribute("manageFilteredAccount",manage);
        model.addAttribute("openInvitation",invite && manage);
        model.addAttribute("canInviteHome",manage && repository.canInviteHome(target,email));
        var permittedStores=selected.stores().stream().map(WorkspaceAccessRepository.ConnectionView::id).collect(Collectors.toSet());
        var members = manage?repository.listMembers(target).stream().map(m->{
            var visible=m.storeIds().stream().filter(permittedStores::contains).toList();
            return new WorkspaceAccessRepository.MemberView(m.id(),m.displayName(),m.email(),m.role(),m.status(),visible.size(),
                visible,m.protectedMember() || visible.size()!=m.storeIds().size());
        }).toList():repository.directoryMembers(target);
        var invitationRows = manage?repository.listInvitations(target):java.util.List.<WorkspaceAccessRepository.InvitationView>of();
        var stores = selected.stores();
        model.addAttribute("members", members);
        model.addAttribute("invitations", invitationRows);
        model.addAttribute("activeUsers", members.stream().filter(member -> "ACTIVE".equals(member.status())).count());
        model.addAttribute("pendingInvitations", invitationRows.stream()
            .filter(invitation -> "PENDING".equals(invitation.status()) && !invitation.expired()).count());
        model.addAttribute("stores", stores);
        model.addAttribute("connectedStores", stores.size());
        return "users";
    }

    public static void addAccessModel(Authentication authentication, Model model) {
        Set<String> roles = authentication == null ? Set.of() : authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean superAdmin = roles.contains("ROLE_PLATFORM_ADMIN");
        boolean tenantAdmin = roles.contains("ROLE_OWNER") || roles.contains("ROLE_ADMIN");
        model.addAttribute("isSuperAdmin", superAdmin);
        model.addAttribute("canManageUsers", superAdmin || tenantAdmin);
        model.addAttribute("canManageConnections", superAdmin || tenantAdmin);
        model.addAttribute("canEditCatalog", superAdmin || tenantAdmin || roles.contains("ROLE_OPERATOR"));
        model.addAttribute("canViewOperations", superAdmin || tenantAdmin
            || roles.contains("ROLE_OPERATOR") || roles.contains("ROLE_VIEWER"));
        model.addAttribute("signedInEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("roleLabel", roleLabel(roles));
    }

    public static boolean addTenantModel(HttpSession session, Model model) {
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
