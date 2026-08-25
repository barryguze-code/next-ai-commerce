package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.ConnectionView;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.MemberView;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class TemplateRenderingTest {
    @Test
    void usersTemplateParsesAndRenders() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(
            new MockHttpServletRequest(servletContext), new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);
        context.setVariables(Map.ofEntries(
            Map.entry("canViewOperations", true), Map.entry("canManageConnections", true),
            Map.entry("isSuperAdmin", true), Map.entry("selectedAccountName", "Ibcore"),
            Map.entry("signedInEmail", "barry.guze@gmail.com"), Map.entry("roleLabel", "Super Admin"),
            Map.entry("activeUsers", 1L), Map.entry("pendingInvitations", 0),
            Map.entry("connectedStores", 2),
            Map.entry("members", List.of(new MemberView(UUID.randomUUID(), "Barry",
                "barry.guze@gmail.com", "OWNER", "ACTIVE", 1))),
            Map.entry("stores", List.of(
                new ConnectionView(UUID.randomUUID(), UUID.randomUUID(), "Ibcore", "Ibcore Amazon",
                    "AMAZON", "seller", "ATVPDKIKX0DER", "Amazon US", "🇺🇸", "ACTIVE", null, false),
                new ConnectionView(UUID.randomUUID(), UUID.randomUUID(), "Ibcore", "Ibcore Walmart",
                    "WALMART", "IBCORE-WALMART", "Walmart-US", "Walmart US", "🇺🇸", "PENDING", null, true)))
        ));

        String rendered = engine.process("users", context);
        assertThat(rendered).contains("Users &amp; Access", "Ibcore", "Amazon US", "Walmart US",
            "/images/channels/amazon-official.png", "/images/channels/walmart-official.png",
            "type=\"button\" aria-label=\"Close invitation\"");
    }
}
