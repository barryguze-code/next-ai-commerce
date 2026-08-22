package com.nextaicommerce.platform.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import com.nextaicommerce.platform.web.PageController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PageController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"app.local-admin.email=test@example.com","app.local-admin.password=test-password"})
class SecurityConfigTest {
    @Autowired MockMvc mvc;

    @Test void loginIsPublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("login"));
    }

    @Test void workspaceRequiresAuthentication() throws Exception {
        mvc.perform(get("/app")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test void authenticatedUserCanOpenWorkspace() throws Exception {
        mvc.perform(get("/app").with(user("operator@example.com"))).andExpect(status().isOk()).andExpect(view().name("app"));
    }
}
