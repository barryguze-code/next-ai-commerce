package com.nextaicommerce.platform.config;

import com.nextaicommerce.platform.invitation.InvitationWorkflowService;
import com.nextaicommerce.platform.web.InvitationController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InvitationController.class)
@Import({SecurityConfig.class, InvitationLoginFlowTest.LoginUsers.class})
class InvitationLoginFlowTest {
    @Autowired MockMvc mvc;
    @MockitoBean InvitationWorkflowService workflow;
    @MockitoBean com.nextaicommerce.platform.web.WorkspaceAccessRepository workspace;
    private final UUID tenant = UUID.randomUUID();
    private static final String EMAIL = "invitee@example.com";

    @TestConfiguration static class LoginUsers {
        @Bean InMemoryUserDetailsManager users(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername(EMAIL)
                .password(encoder.encode("test-password")).roles("VIEWER").build());
        }
    }

    @Test void emailBrowserWithoutFetchHeadersResumesInvitationAfterLogin() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/invitation/accept").servletPath("/invitation/accept")
            .param("tenantId", tenant.toString()).param("token", "synthetic-token").session(session))
            .andExpect(status().is3xxRedirection());
        var saved = (org.springframework.security.web.savedrequest.SavedRequest)
            session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertNotNull(saved, "Email browsers must not lose invitations without Fetch Metadata headers");
        mvc.perform(post("/login").session(session).with(csrf())
            .param("username", EMAIL).param("password", "test-password"))
            .andExpect(redirectedUrl(saved.getRedirectUrl()));
        when(workflow.accountName(tenant)).thenReturn("Invited account");
        mvc.perform(get("/invitation/accept").session(session)
            .param("tenantId", tenant.toString()).param("token", "synthetic-token"))
            .andExpect(status().isOk()).andExpect(view().name("accept-invitation"));
        verify(workflow, never()).acceptExisting(any(), any(), any());
        mvc.perform(post("/invitation/accept").session(session).with(csrf())
            .param("tenantId", tenant.toString()).param("token", "synthetic-token"))
            .andExpect(redirectedUrl("/app/select-account"));
        verify(workflow).acceptExisting(tenant, "synthetic-token", EMAIL);
    }

    @Test void backgroundRequestsStillCannotBecomeLoginDestinations() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/app/collaboration/summaries").servletPath("/app/collaboration/summaries")
            .session(session)).andExpect(status().is3xxRedirection());
        assertNull(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST"));
    }

    @Test void acceptingStillRequiresCsrf() throws Exception {
        mvc.perform(post("/invitation/accept").with(user(EMAIL).roles("VIEWER"))
            .param("tenantId", tenant.toString()).param("token", "synthetic-token"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(workflow);
    }
}
