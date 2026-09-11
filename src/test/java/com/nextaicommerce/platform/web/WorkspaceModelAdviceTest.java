package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;

class WorkspaceModelAdviceTest {
    @Test void restoresAuthorizedRememberedAccountBeforeControllerRuns(){
        UUID tenantId=UUID.randomUUID(),storeId=UUID.randomUUID();
        var store=new WorkspaceAccessRepository.ConnectionView(storeId,tenantId,"Ibcore","Amazon US","AMAZON",
            "seller","ATVPDKIKX0DER","Amazon US","🇺🇸","ACTIVE",null,true);
        var option=new WorkspaceAccessRepository.AccountOption(tenantId,"Ibcore","IB",null,List.of(store));
        var repository=new WorkspaceAccessRepository(null){
            @Override public List<AccountOption> listAccountOptions(String email,boolean superAdmin){return List.of(option);}
        };
        var request=new MockHttpServletRequest();request.setRequestURI("/app/inventory/physical-counts/"+UUID.randomUUID());
        request.setCookies(new jakarta.servlet.http.Cookie(AccountSelectionController.LAST_TENANT_COOKIE,tenantId.toString()),
            new jakarta.servlet.http.Cookie(AccountSelectionController.LAST_STORE_COOKIE,storeId.toString()));
        var authentication=new UsernamePasswordAuthenticationToken("user@example.com","n/a",List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        new WorkspaceModelAdvice(repository).accountSwitcher(authentication,request.getSession(),request,new ConcurrentModel());
        assertThat(request.getSession().getAttribute(AccountSelectionController.TENANT_ID)).isEqualTo(tenantId);
        assertThat(request.getSession().getAttribute(AccountSelectionController.STORE_ID)).isEqualTo(storeId);
    }

    @Test void ignoresRememberedAccountThatUserCannotAccess(){
        var request=new MockHttpServletRequest();request.setRequestURI("/app/inventory");
        request.setCookies(new jakarta.servlet.http.Cookie(AccountSelectionController.LAST_TENANT_COOKIE,UUID.randomUUID().toString()));
        var authentication=new UsernamePasswordAuthenticationToken("user@example.com","n/a",List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        var repository=new WorkspaceAccessRepository(null){@Override public List<AccountOption> listAccountOptions(String email,boolean superAdmin){return List.of();}};
        new WorkspaceModelAdvice(repository).accountSwitcher(authentication,request.getSession(),request,new ConcurrentModel());
        assertThat(request.getSession().getAttribute(AccountSelectionController.TENANT_ID)).isNull();
    }
}
