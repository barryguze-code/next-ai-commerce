package com.nextaicommerce.platform.web;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AccountSelectionController {
    static final String LAST_TENANT_COOKIE="nextai_last_tenant";
    static final String LAST_STORE_COOKIE="nextai_last_store";
    public static final String TENANT_ID = "selectedTenantId";
    public static final String TENANT_NAME = "selectedTenantName";
    public static final String STORE_ID = "selectedStoreId";
    public static final String STORE_NAME = "selectedStoreName";
    private final WorkspaceAccessRepository repository;

    AccountSelectionController(WorkspaceAccessRepository repository) { this.repository = repository; }

    @GetMapping("/app/select-account")
    String select(Authentication authentication, HttpSession session, Model model,
            HttpServletRequest request,HttpServletResponse response) {
        PageController.addAccessModel(authentication, model);
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        var accounts = repository.listAvailableAccounts(authentication.getName(), superAdmin);
        var rememberedTenant=cookieUuid(request,LAST_TENANT_COOKIE);
        if(rememberedTenant!=null){
            var option=repository.listAccountOptions(authentication.getName(),superAdmin).stream()
                .filter(account->account.id().equals(rememberedTenant)).findFirst().orElse(null);
            if(option!=null){
                session.setAttribute(TENANT_ID,option.id());session.setAttribute(TENANT_NAME,option.name());
                var rememberedStore=cookieUuid(request,LAST_STORE_COOKIE);
                option.stores().stream().filter(store->store.id().equals(rememberedStore)).findFirst().ifPresent(store->{
                    session.setAttribute(STORE_ID,store.id());session.setAttribute(STORE_NAME,store.name());
                });
                return "redirect:/app";
            }
            forget(response,LAST_TENANT_COOKIE);forget(response,LAST_STORE_COOKIE);
        }
        if (accounts.size() == 1) {
            session.setAttribute(TENANT_ID, accounts.getFirst().id());
            session.setAttribute(TENANT_NAME, accounts.getFirst().name());
            remember(response,LAST_TENANT_COOKIE,accounts.getFirst().id());
            return "redirect:/app";
        }
        model.addAttribute("accounts", accounts);
        return "select-account";
    }

    @PostMapping("/app/select-account")
    String choose(@RequestParam UUID tenantId, @RequestParam String tenantName,
            Authentication authentication, HttpSession session,HttpServletResponse response) {
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        if (!repository.canAccessAccount(tenantId, authentication.getName(), superAdmin))
            throw new org.springframework.security.access.AccessDeniedException("Account access denied");
        session.setAttribute(TENANT_ID, tenantId);
        session.setAttribute(TENANT_NAME, tenantName);
        session.removeAttribute(STORE_ID);
        session.removeAttribute(STORE_NAME);
        remember(response,LAST_TENANT_COOKIE,tenantId);forget(response,LAST_STORE_COOKIE);
        return "redirect:/app";
    }

    @PostMapping("/app/select-store")
    String chooseStore(@RequestParam UUID tenantId, @RequestParam String tenantName,
            @RequestParam UUID storeId, @RequestParam String storeName,
            @RequestParam(defaultValue = "/app") String returnTo,
            Authentication authentication, HttpSession session,HttpServletResponse response) {
        boolean superAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        boolean storeAllowed = repository.listAccountOptions(authentication.getName(), superAdmin).stream()
            .filter(account -> account.id().equals(tenantId))
            .flatMap(account -> account.stores().stream())
            .anyMatch(store -> store.id().equals(storeId));
        if (!repository.canAccessAccount(tenantId, authentication.getName(), superAdmin) || !storeAllowed)
            throw new org.springframework.security.access.AccessDeniedException("Store access denied");
        session.setAttribute(TENANT_ID, tenantId);
        session.setAttribute(TENANT_NAME, tenantName);
        session.setAttribute(STORE_ID, storeId);
        session.setAttribute(STORE_NAME, storeName);
        remember(response,LAST_TENANT_COOKIE,tenantId);remember(response,LAST_STORE_COOKIE,storeId);
        return "redirect:" + safeWorkspacePath(returnTo);
    }

    private static UUID cookieUuid(HttpServletRequest request,String name){
        if(request.getCookies()==null)return null;
        for(var cookie:request.getCookies())if(name.equals(cookie.getName()))try{return UUID.fromString(cookie.getValue());}catch(IllegalArgumentException ignored){return null;}
        return null;
    }
    private static void remember(HttpServletResponse response,String name,UUID value){
        response.addHeader("Set-Cookie",name+"="+value+"; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax");
    }
    private static void forget(HttpServletResponse response,String name){
        response.addHeader("Set-Cookie",name+"=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private static String safeWorkspacePath(String path) {
        return path != null && path.matches("/app(?:/(?:orders|marketplace-skus|inventory(?:/ledger)?))?") ? path : "/app";
    }
}
