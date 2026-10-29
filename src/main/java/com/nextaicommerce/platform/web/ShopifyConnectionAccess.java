package com.nextaicommerce.platform.web;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class ShopifyConnectionAccess {
    private final MemberAccessService members;
    private final WorkspaceAccessRepository repository;
    public ShopifyConnectionAccess(MemberAccessService members,WorkspaceAccessRepository repository){this.members=members;this.repository=repository;}
    public void require(UUID tenant,UUID connection,Authentication actor){
        if(tenant==null||actor==null||!actor.isAuthenticated())throw new AccessDeniedException("Choose an account you administer.");
        if(actor.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"))&&repository.isCurrentPlatformAdmin(actor.getName()))return;
        var access=members.current(tenant,actor.getName());
        if(access==null||!("OWNER".equals(access.role())||"ADMIN".equals(access.role()))
                ||access.restricted()&&(connection==null||!access.stores().contains(connection)))
            throw new AccessDeniedException("Only this account's authorized administrators can manage this store.");
    }
}
