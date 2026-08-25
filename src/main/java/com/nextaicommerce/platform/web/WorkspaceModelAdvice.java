package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
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
    void accountSwitcher(Authentication authentication, HttpSession session, Model model) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return;
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        model.addAttribute("accountOptions", repository.listAccountOptions(authentication.getName(), superAdmin));
        model.addAttribute("selectedStoreId", session.getAttribute(AccountSelectionController.STORE_ID));
        model.addAttribute("selectedStoreName", session.getAttribute(AccountSelectionController.STORE_NAME));
    }
}
