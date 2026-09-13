package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Map;
import java.util.stream.Collectors;

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
        var connectionErrors = new java.util.LinkedHashMap<UUID,String>();
        for (var connection : connections) {
            if (connection.credentialsStored()) {
                try { credentialService.credentials(tenantId, connection.id()); }
                catch (IllegalStateException ex) {
                    connectionErrors.put(connection.id(), "Stored credentials cannot be opened. Re-enter all credentials and verify the connection. Imported data is retained.");
                }
            }
        }
        model.addAttribute("connectionErrors", connectionErrors);
        model.addAttribute("connections", connections);
        model.addAttribute("connectionCount", connections.size());
        model.addAttribute("activeConnectionCount", connections.stream()
            .filter(connection -> "ACTIVE".equals(connection.status()) && !connectionErrors.containsKey(connection.id())).count());
        model.addAttribute("setupConnectionCount", connections.stream()
            .filter(connection -> "PENDING".equals(connection.status()) || "ERROR".equals(connection.status()) || connectionErrors.containsKey(connection.id())).count());
        var syncStatuses=repository.listCurrentSyncStatuses(tenantId);
        model.addAttribute("syncStatuses",syncStatuses);
        model.addAttribute("syncInProgress",syncStatuses.stream().anyMatch(sync->
            "QUEUED".equals(sync.status())||"RUNNING".equals(sync.status())));
        model.addAttribute("syncJobsByConnection", repository.listCurrentSyncJobs(tenantId).stream()
            .collect(Collectors.groupingBy(WorkspaceAccessRepository.SyncJobView::connectionId)));
        return "connections";
    }

    @PostMapping("/app/connections/{connectionId}/disconnect")
    String disconnectConnection(@PathVariable UUID connectionId, HttpSession session, RedirectAttributes redirect) {
        UUID tenantId=(UUID)session.getAttribute(AccountSelectionController.TENANT_ID);
        if(tenantId==null)return "redirect:/app/select-account";
        try {
            repository.disconnectConnection(tenantId,connectionId);
            if(connectionId.equals(session.getAttribute(AccountSelectionController.STORE_ID))){
                session.removeAttribute(AccountSelectionController.STORE_ID);
                session.removeAttribute(AccountSelectionController.STORE_NAME);
            }
            redirect.addFlashAttribute("connectionSuccess","Store disconnected. Imported history was retained safely.");
        } catch(RuntimeException ex){redirect.addFlashAttribute("connectionError",ex.getMessage());}
        return "redirect:/app/connections";
    }

    @PostMapping("/app/connections/{connectionId}/credentials")
    String saveCredentials(@PathVariable UUID connectionId, @RequestParam Map<String, String> form,
            HttpSession session, RedirectAttributes redirect) {
        UUID tenantId = (UUID) session.getAttribute(AccountSelectionController.TENANT_ID);
        if (tenantId == null) return "redirect:/app/select-account";
        try {
            credentialService.connect(tenantId, connectionId, form);
            redirect.addFlashAttribute("connectionSuccess",
                "Connection verified. We are now preparing the store's Amazon data.");
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

    @PostMapping("/app/platform/accounts/{tenantId}/logo")
    String uploadLogo(@PathVariable UUID tenantId,@RequestParam MultipartFile logo,RedirectAttributes redirect){
        try{
            if(logo==null||logo.isEmpty())throw new IllegalArgumentException("Choose a PNG, JPEG, or WebP logo.");
            if(logo.getSize()>2_000_000)throw new IllegalArgumentException("Use a logo smaller than 2 MB.");
            String type=logo.getContentType()==null?"":logo.getContentType();
            if(!java.util.Set.of("image/png","image/jpeg","image/webp").contains(type))throw new IllegalArgumentException("Use a PNG, JPEG, or WebP logo.");
            repository.saveAccountLogo(tenantId,type,logo.getBytes());
            redirect.addFlashAttribute("accountSuccess","Company logo saved. It now appears wherever this account is selected.");
        }catch(Exception e){redirect.addFlashAttribute("accountError",e instanceof IllegalArgumentException?e.getMessage():"The company logo could not be saved.");}
        return "redirect:/app/platform/accounts";
    }
    @GetMapping("/app/platform/accounts/{tenantId}/logo") @ResponseBody
    ResponseEntity<byte[]> logo(@PathVariable UUID tenantId, Authentication authentication){
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        if (!repository.canAccessAccount(tenantId, authentication.getName(), superAdmin))
            return ResponseEntity.notFound().build();
        var logo=repository.accountLogo(tenantId);if(logo==null)return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(logo.contentType())).cacheControl(org.springframework.http.CacheControl.noCache()).body(logo.bytes());
    }

    @GetMapping("/app/platform/accounts/{tenantId}/open")
    String openAccount(@PathVariable UUID tenantId, HttpSession session) {
        var account = repository.listAllAccounts().stream()
            .filter(candidate -> candidate.id().equals(tenantId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Account was not found."));
        session.setAttribute(AccountSelectionController.TENANT_ID, account.id());
        session.setAttribute(AccountSelectionController.TENANT_NAME, account.name());
        session.removeAttribute(AccountSelectionController.STORE_ID);
        session.removeAttribute(AccountSelectionController.STORE_NAME);
        return "redirect:/app?chooseStore=true";
    }
}
