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
        if((request.getRequestURI().matches("/app/inventory/physical-counts/[^/]+/progress")
                || request.getRequestURI().matches("/app/catalog/imports/[^/]+/progress")
                || request.getRequestURI().matches("/app/orders/sync/[^/]+/progress"))
                && session.getAttribute(AccountSelectionController.TENANT_ID) instanceof java.util.UUID)return;
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        var accountOptions = repository.listAccountOptions(authentication.getName(), superAdmin);
        restoreRememberedWorkspace(request,session,accountOptions);
        model.addAttribute("accountOptions", accountOptions);
        model.addAttribute("currentWorkspacePath", request.getRequestURI());
        Object selectedAccountId = session.getAttribute(AccountSelectionController.TENANT_ID);
        accountOptions.stream().filter(account -> account.id().equals(selectedAccountId)).findFirst()
            .ifPresent(account -> { model.addAttribute("selectedAccountInitials", account.initials()); model.addAttribute("selectedAccountLogoUrl", account.logoUrl()); });
        Object selectedStoreId = session.getAttribute(AccountSelectionController.STORE_ID);
        model.addAttribute("selectedStoreId", selectedStoreId);
        model.addAttribute("selectedStoreName", session.getAttribute(AccountSelectionController.STORE_NAME));
        if (selectedStoreId instanceof java.util.UUID storeId) {
            accountOptions.stream().flatMap(account -> account.stores().stream())
                .filter(store -> store.id().equals(storeId)).findFirst()
                .ifPresent(store -> model.addAttribute("selectedStore", store));
        }
    }

    private static void restoreRememberedWorkspace(HttpServletRequest request,HttpSession session,
            java.util.List<WorkspaceAccessRepository.AccountOption> options) {
        Object selected=session.getAttribute(AccountSelectionController.TENANT_ID);
        if(selected instanceof java.util.UUID)return;
        session.removeAttribute(AccountSelectionController.TENANT_ID);session.removeAttribute(AccountSelectionController.TENANT_NAME);
        session.removeAttribute(AccountSelectionController.STORE_ID);session.removeAttribute(AccountSelectionController.STORE_NAME);
        java.util.UUID remembered=cookieUuid(request,AccountSelectionController.LAST_TENANT_COOKIE);
        var account=options.stream().filter(option->option.id().equals(remembered)).findFirst().orElse(null);
        if(account==null&&options.size()==1)account=options.getFirst();
        if(account==null)return;
        session.setAttribute(AccountSelectionController.TENANT_ID,account.id());
        session.setAttribute(AccountSelectionController.TENANT_NAME,account.name());
        java.util.UUID rememberedStore=cookieUuid(request,AccountSelectionController.LAST_STORE_COOKIE);
        account.stores().stream().filter(store->store.id().equals(rememberedStore)).findFirst().ifPresent(store->{
            session.setAttribute(AccountSelectionController.STORE_ID,store.id());
            session.setAttribute(AccountSelectionController.STORE_NAME,store.name());
        });
    }

    private static java.util.UUID cookieUuid(HttpServletRequest request,String name){
        if(request.getCookies()==null)return null;
        for(var cookie:request.getCookies())if(name.equals(cookie.getName()))try{return java.util.UUID.fromString(cookie.getValue());}catch(IllegalArgumentException ignored){return null;}
        return null;
    }
}
