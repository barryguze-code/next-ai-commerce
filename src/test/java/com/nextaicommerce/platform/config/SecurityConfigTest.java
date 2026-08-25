package com.nextaicommerce.platform.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import com.nextaicommerce.platform.web.PageController;
import com.nextaicommerce.platform.web.AdministrationController;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import com.nextaicommerce.platform.web.MarketplaceCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import java.util.List;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PageController.class, AdministrationController.class, AccountSelectionController.class})
@Import({SecurityConfig.class, SecurityConfigTest.StubConfig.class})
@TestPropertySource(properties={"app.local-admin.email=test@example.com","app.local-admin.password=test-password"})
class SecurityConfigTest {
    private static final UUID TENANT_ID = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @TestConfiguration
    static class StubConfig {
        @Bean MarketplaceCredentialService marketplaceCredentialService(WorkspaceAccessRepository repository) {
            return new MarketplaceCredentialService(repository, "");
        }
        @Bean WorkspaceAccessRepository workspaceAccessRepository() {
            return new WorkspaceAccessRepository(null) {
                @Override public List<AccountView> listAllAccounts() { return List.of(); }
                @Override public List<AccountView> listAvailableAccounts(String email, boolean superAdmin) { return List.of(); }
                @Override public List<ConnectionView> listConnections(UUID tenantId, String tenantName) { return List.of(); }
                @Override public List<MemberView> listMembers(UUID tenantId) { return List.of(); }
                @Override public int pendingInvitationCount(UUID tenantId) { return 0; }
                @Override public boolean canAccessAccount(UUID tenantId, String email, boolean superAdmin) { return true; }
                @Override public void addConnection(UUID tenantId, String channel, String displayName,
                        String sellerIdentifier, String marketplaceIdentifier) {}
            };
        }
    }

    @Test void loginIsPublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("login"));
    }

    @Test void workspaceRequiresAuthentication() throws Exception {
        mvc.perform(get("/app")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test void logoutPostsWithCsrfAndReturnsToLogin() throws Exception {
        mvc.perform(post("/logout").with(user("admin@example.com")).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?logout"));
    }

    @Test void authenticatedUserCanOpenWorkspace() throws Exception {
        mvc.perform(get("/app").with(user("operator@example.com"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("app"));
    }

    @Test void usersPageRequiresAuthentication() throws Exception {
        mvc.perform(get("/app/users")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test void authenticatedAdminCanOpenUsersPage() throws Exception {
        mvc.perform(get("/app/users").with(user("admin@example.com").roles("PLATFORM_ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("users"));
    }

    @Test void tenantAdminCanManageUsersAndConnections() throws Exception {
        mvc.perform(get("/app/users").with(user("admin@example.com").roles("ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("users"));
        mvc.perform(get("/app/connections").with(user("admin@example.com").roles("ADMIN"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("connections"));
    }

    @Test void walmartConnectionDoesNotRequireAmazonSellerId() throws Exception {
        mvc.perform(post("/app/connections").with(user("admin@example.com").roles("ADMIN")).with(csrf())
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore")
                .param("channel", "WALMART")
                .param("displayName", "Ibcore Walmart")
                .param("marketplaceIdentifier", "Walmart-US"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/app/connections"));
    }

    @Test void operatorCannotManageUsersOrConnections() throws Exception {
        mvc.perform(get("/app/users").with(user("operator@example.com").roles("OPERATOR")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/app/connections").with(user("operator@example.com").roles("OPERATOR")))
            .andExpect(status().isForbidden());
    }

    @Test void viewerCanOpenOperationalReadPages() throws Exception {
        mvc.perform(get("/app/orders").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("module"));
        mvc.perform(get("/app/inventory").with(user("viewer@example.com").roles("VIEWER"))
                .sessionAttr("selectedTenantId", TENANT_ID).sessionAttr("selectedTenantName", "Ibcore"))
            .andExpect(status().isOk()).andExpect(view().name("module"));
    }

    @Test void onlySuperAdminCanOpenPlatformAccounts() throws Exception {
        mvc.perform(get("/app/platform/accounts").with(user("owner@example.com").roles("OWNER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/app/platform/accounts").with(user("super@example.com").roles("PLATFORM_ADMIN")))
            .andExpect(status().isOk()).andExpect(view().name("accounts"));
    }
}
