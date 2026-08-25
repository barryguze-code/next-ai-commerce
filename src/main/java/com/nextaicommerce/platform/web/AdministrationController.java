package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Map;

@Controller
public class AdministrationController {
    private final WorkspaceAccessRepository repository;
    private final MarketplaceCredentialService credentialService;

    AdministrationController(WorkspaceAccessRepository repository, MarketplaceCredentialService credentialService) {
        this.repository = repository;
        this.credentialService = credentialService;
    }

    @GetMapping("/app/connections")
    String connections(Authentication authentication, HttpSession session, Model model) {
        if (!PageController.addTenantModel(session, model)) return "redirect:/app/select-account";
        PageController.addAccessModel(authentication, model);
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        String tenantName = (String) session.getAttribute(AccountSelectionController.TENANT_NAME);
        var connections = repository.listConnections(tenantId, tenantName);
        model.addAttribute("connections", connections);
        model.addAttribute("connectionCount", connections.size());
        model.addAttribute("activeConnectionCount", connections.stream()
            .filter(connection -> "ACTIVE".equals(connection.status())).count());
        model.addAttribute("setupConnectionCount", connections.stream()
            .filter(connection -> !"ACTIVE".equals(connection.status())).count());
        return "connections";
    }

    @PostMapping("/app/connections/{connectionId}/credentials")
    String saveCredentials(@PathVariable UUID connectionId, @RequestParam Map<String, String> form,
            HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        if (tenantId == null) return "redirect:/app/select-account";
        try {
            credentialService.connect(tenantId, connectionId, form);
            redirect.addFlashAttribute("connectionSuccess", "Connection verified and credentials saved securely.");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("connectionError", ex.getMessage());
        }
        return "redirect:/app/connections";
    }

    @PostMapping("/app/connections")
    String addConnection(@RequestParam String channel, @RequestParam String displayName,
            @RequestParam(defaultValue = "") String sellerIdentifier,
            @RequestParam String marketplaceIdentifier,
            HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        if (tenantId == null) return "redirect:/app/select-account";
        try {
            repository.addConnection(tenantId, channel, displayName, sellerIdentifier, marketplaceIdentifier);
            redirect.addFlashAttribute("connectionSuccess", "Store added. Select Set up to connect it.");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("connectionError", ex.getMessage());
        }
        return "redirect:/app/connections";
    }

    @GetMapping("/app/platform/accounts")
    String accounts(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean create, Model model) {
        PageController.addAccessModel(authentication, model);
        var accounts = repository.listAllAccounts();
        model.addAttribute("accounts", accounts);
        model.addAttribute("accountCount", accounts.size());
        model.addAttribute("autoOpenAddAccount", create);
        return "accounts";
    }

    @PostMapping("/app/platform/accounts")
    String addAccount(@RequestParam String displayName, RedirectAttributes redirect) {
        try {
            repository.addAccount(displayName);
            redirect.addFlashAttribute("accountSuccess", "Account created. Open it to add marketplace stores.");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("accountError", ex.getMessage());
        }
        return "redirect:/app/platform/accounts";
    }

    @GetMapping("/app/platform/accounts/{tenantId}/open")
    String openAccount(@PathVariable UUID tenantId, HttpSession session) {
        var account = repository.listAllAccounts().stream()
            .filter(candidate -> candidate.id().equals(tenantId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Account was not found."));
        session.setAttribute(AccountSelectionController.TENANT_ID, account.id());
        session.setAttribute(AccountSelectionController.TENANT_NAME, account.name());
        return "redirect:/app/connections";
    }
}
