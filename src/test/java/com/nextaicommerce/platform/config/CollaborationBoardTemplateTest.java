package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import com.nextaicommerce.platform.collaboration.ParentTenantRepository;

class CollaborationBoardTemplateTest {
    @Test void boardAndExplicitParentSetupRenderWithNoImplicitGrouping() throws Exception{
        var servlet=new MockServletContext();var app=JakartaServletWebApplication.buildApplication(servlet);
        var context=new WebContext(app.buildExchange(new MockHttpServletRequest(servlet),new MockHttpServletResponse()));
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCacheable(false);
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        UUID parent=UUID.fromString("00000000-0000-0000-0000-000000000001");
        context.setVariables(Map.of("parents",List.of(new ParentTenantRepository.Parent(parent,"Fixture parent")),"selectedParent",parent,"boardUser","fixture@example.test","boardAccounts",List.of(),"boardPeople",List.of(),"canSetupParents",true,"canEditCatalog",true,"canViewOperations",true,"isSuperAdmin",true));
        context.setVariable("personalUser","fixture@example.test");
        context.setVariable("signedInEmail","fixture@example.test");
        String shared=engine.process("fragments/sidebar-user",context);
        assertThat(shared).contains("/js/confirm-dialog.js?v=","/css/confirm-dialog.css");
        Files.createDirectories(Path.of(".local/browser-fixtures"));Files.writeString(Path.of(".local/browser-fixtures/shared-confirm.html"),shared);
        String personal=engine.process("personal-collaboration",context);
        Files.createDirectories(Path.of(".local/browser-fixtures"));Files.writeString(Path.of(".local/browser-fixtures/personal-collaboration.html"),personal);
        assertThat(personal).contains("data-personal-scope=\"ACTIVE\"","data-personal-scope=\"PRIVATE\"",
            "data-personal-scope=\"CLOSED\"","data-personal-scope=\"ASSIGNED\"","All accessible accounts");
        assertThat(personal.split("id=\"collaboration-due-editor\"",-1)).hasSize(2);
        assertThat(personal.split("id=\"contextual-thread-dialog\"",-1)).hasSize(2);
        context.setVariable("selectedAccountId",parent);context.setVariable("selectedStoreId",parent);
        assertThat(engine.process("personal-collaboration",context)).contains("data-workspace-account=\""+parent+"\"","data-workspace-store=\""+parent+"\"");
        String html=engine.process("collaboration-board",context);
        assertThat(html).contains("All accounts","All marketplaces","@Me","In progress","This month","data-state=\"DONE\"","Fixture parent");
        Path fixture=Path.of(".local/browser-fixtures/collaboration-board.html");Files.createDirectories(fixture.getParent());Files.writeString(fixture,html);
        context.setVariable("selectedParent",null);assertThat(engine.process("collaboration-board",context)).contains("Choose your home tenant").doesNotContain("class=\"collaboration-kanban\"");
        context.setVariable("accounts",List.of());context.setVariable("people",List.of());
        assertThat(engine.process("parent-tenants",context)).contains("Other members need separate account access","Create tenant");
    }
}
