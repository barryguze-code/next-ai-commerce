package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

@ControllerAdvice
public class WorkspaceModelAdvice {
    private final WorkspaceAccessRepository repository;

    WorkspaceModelAdvice(WorkspaceAccessRepository repository) {
        this.repository = repository;
    }

    @ModelAttribute
    void accountSwitcher(Authentication authentication, HttpSession session,
            HttpServletRequest request, Model model) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return;
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        var accountOptions = repository.listAccountOptions(authentication.getName(), superAdmin);
        model.addAttribute("accountOptions", accountOptions);
        model.addAttribute("currentWorkspacePath", request.getRequestURI());
        Object selectedAccountId = session.getAttribute(AccountSelectionController.TENANT_ID);
        accountOptions.stream().filter(account -> account.id().equals(selectedAccountId)).findFirst()
            .ifPresent(account -> model.addAttribute("selectedAccountInitials", account.initials()));
        Object selectedStoreId = session.getAttribute(AccountSelectionController.STORE_ID);
        model.addAttribute("selectedStoreId", selectedStoreId);
        model.addAttribute("selectedStoreName", session.getAttribute(AccountSelectionController.STORE_NAME));
        if (selectedStoreId instanceof java.util.UUID storeId) {
            accountOptions.stream().flatMap(account -> account.stores().stream())
                .filter(store -> store.id().equals(storeId)).findFirst()
                .ifPresent(store -> model.addAttribute("selectedStore", store));
        }
    }
}
