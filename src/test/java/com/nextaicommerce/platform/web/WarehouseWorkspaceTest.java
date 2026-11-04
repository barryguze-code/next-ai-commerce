package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class WarehouseWorkspaceTest {
    @Test void warehousePickerRetainsSelectedStoreIdentityWithoutChangingInventoryOwnership() {
        UUID account=UUID.randomUUID(),store=UUID.randomUUID();
        var connection=new WorkspaceAccessRepository.ConnectionView(store,account,"Ibcore Amazon","Amazon US","AMAZON","seller","ATVPDKIKX0DER","Amazon US","🇺🇸","ACTIVE",null,true);
        var option=new WorkspaceAccessRepository.AccountOption(account,"Ibcore","IB","/images/account-fixture.png",List.of(connection));
        var repository=mock(WorkspaceAccessRepository.class);when(repository.listAccountOptions("fixture@example.test",false)).thenReturn(List.of(option));
        var auth=new UsernamePasswordAuthenticationToken("fixture@example.test","unused",List.of());
        var servlet=new MockServletContext();var request=new MockHttpServletRequest(servlet);var response=new MockHttpServletResponse();
        request.getSession().setAttribute(AccountSelectionController.TENANT_ID,account);
        request.getSession().setAttribute(AccountSelectionController.TENANT_NAME,"Ibcore");
        request.getSession().setAttribute(AccountSelectionController.STORE_ID,store);
        var advice=new WorkspaceModelAdvice(repository);
        for(String path:List.of("/app/inventory","/app/inventory/ledger","/app/inventory/replenishment","/app/receiving")){
            request.setRequestURI(path);var model=new ConcurrentModel();
            advice.accountSwitcher(auth,request.getSession(),request,model);
            assertThat(model.getAttribute("warehouseWorkspace")).isEqualTo(true);
            assertThat(request.getSession().getAttribute(AccountSelectionController.STORE_ID)).isEqualTo(store);
            model.addAttribute("selectedAccountName","Ibcore");model.addAttribute("selectedAccountId",account);
            var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
            var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
            var context=new WebContext(JakartaServletWebApplication.buildApplication(servlet).buildExchange(request,response));context.setVariables(model.asMap());
            String html=engine.process("fragments/context-switcher",context);
            assertThat(html).contains("Choose workspace","/images/account-fixture.png","name=\"returnTo\"","Amazon US","action=\"/app/select-store\"");
            String summary=html.substring(html.indexOf("<summary"),html.indexOf("</summary>"));assertThat(summary).doesNotContain("next-ai-mark-transparent.png","Warehouse inventory");
            assertThat(request.getSession().getAttribute(AccountSelectionController.TENANT_ID)).isEqualTo(account);
        }
        request.setRequestURI("/app/inventory/fba-shipments");var model=new ConcurrentModel();advice.accountSwitcher(auth,request.getSession(),request,model);
        assertThat(model.getAttribute("warehouseWorkspace")).isEqualTo(false);
    }
    @Test void accountSelectionKeepsWarehouseRouteAndClearsOldMarketplaceScope(){
        var repository=mock(WorkspaceAccessRepository.class);var auth=new UsernamePasswordAuthenticationToken("fixture@example.test","unused",List.of());
        UUID account=UUID.randomUUID();when(repository.canAccessAccount(account,auth.getName(),false)).thenReturn(true);
        var controller=new AccountSelectionController(repository);var session=new MockHttpSession();
        for(String path:List.of("/app/inventory","/app/inventory/ledger","/app/inventory/replenishment","/app/receiving")){
            session.setAttribute(AccountSelectionController.STORE_ID,UUID.randomUUID());
            assertThat(controller.choose(account,"Ibcore",auth,session,new MockHttpServletResponse(),path)).isEqualTo("redirect:"+path);
            assertThat(session.getAttribute(AccountSelectionController.TENANT_ID)).isEqualTo(account);
            assertThat(session.getAttribute(AccountSelectionController.STORE_ID)).isNull();
        }
        assertThat(controller.choose(account,"Ibcore",auth,session,new MockHttpServletResponse(),"https://example.test/")).isEqualTo("redirect:/app");
        assertThatThrownBy(()->controller.choose(UUID.randomUUID(),"Denied",auth,session,new MockHttpServletResponse(),"/app/inventory")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(session.getAttribute(AccountSelectionController.TENANT_ID)).isEqualTo(account);
    }
    @Test void onlyExplicitStoreSelectionChangesWorkspaceAndCollaborationReturnIsLocal(){
        var repository=mock(WorkspaceAccessRepository.class);var auth=new UsernamePasswordAuthenticationToken("fixture@example.test","unused",List.of());
        UUID account=UUID.randomUUID(),store=UUID.randomUUID(),old=UUID.randomUUID();
        var connection=new WorkspaceAccessRepository.ConnectionView(store,account,"Seller","Store one","AMAZON","seller","ATVPDKIKX0DER","Amazon US","🇺🇸","ACTIVE",null,true);
        when(repository.listAccountOptions(auth.getName(),false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(account,"Account","AC",null,List.of(connection))));when(repository.canAccessAccount(account,auth.getName(),false)).thenReturn(true);
        var session=new MockHttpSession();session.setAttribute(AccountSelectionController.STORE_ID,old);
        var controller=new AccountSelectionController(repository);
        assertThatThrownBy(()->controller.chooseStore(account,"Account",UUID.randomUUID(),"Denied","/app/collaboration",auth,session,new MockHttpServletResponse())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(session.getAttribute(AccountSelectionController.STORE_ID)).isEqualTo(old);
        assertThat(controller.chooseStore(account,"Account",store,"Store one","/app/collaboration",auth,session,new MockHttpServletResponse())).isEqualTo("redirect:/app/collaboration");
        assertThat(session.getAttribute(AccountSelectionController.STORE_ID)).isEqualTo(store);
    }
}
