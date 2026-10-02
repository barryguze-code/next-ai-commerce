package com.nextaicommerce.platform.config;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.MemberAccessService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Re-check the selected account before URL authorization, not merely at login. */
final class CurrentMembershipFilter extends OncePerRequestFilter {
    private final ObjectProvider<MemberAccessService> services;
    CurrentMembershipFilter(ObjectProvider<MemberAccessService> services){this.services=services;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        var session=request.getSession(false);String path=request.getServletPath();
        var service=services.getIfAvailable();
        if(service==null||auth==null||!auth.isAuthenticated()||session==null||!path.startsWith("/app")
                ||path.equals("/app/select-account")||path.equals("/app/select-store")
                ||auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"))
                ||!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID tenant)) {
            chain.doFilter(request,response);return;
        }
        var access=service.current(tenant,auth.getName());
        if(access==null) {
            clearSelection(session);
            response.sendError(403,"Your access to this account has changed. Choose another account.");return;
        }
        Object selected=session.getAttribute(AccountSelectionController.STORE_ID);
        if(access.restricted() && selected instanceof UUID id && !access.stores().contains(id)) {
            session.removeAttribute(AccountSelectionController.STORE_ID);session.removeAttribute(AccountSelectionController.STORE_NAME);
            response.sendError(403,"Your access to this store has been revoked. Choose an assigned store.");return;
        }
        if(access.restricted() && selected==null && (path.startsWith("/app/orders")||path.startsWith("/app/marketplace-skus"))) {
            response.sendError(403,"Choose an assigned store first.");return;
        }
        var original=SecurityContextHolder.getContext();
        var scoped=SecurityContextHolder.createEmptyContext();
        var current=new UsernamePasswordAuthenticationToken(auth.getPrincipal(),auth.getCredentials(),List.of(new SimpleGrantedAuthority("ROLE_"+access.role())));
        current.setDetails(auth.getDetails());scoped.setAuthentication(current);SecurityContextHolder.setContext(scoped);
        try{chain.doFilter(request,response);}finally{SecurityContextHolder.setContext(original);}
    }
    private static void clearSelection(HttpSession session){
        for(String key:List.of(AccountSelectionController.TENANT_ID,AccountSelectionController.TENANT_NAME,AccountSelectionController.STORE_ID,AccountSelectionController.STORE_NAME))session.removeAttribute(key);
    }
}
