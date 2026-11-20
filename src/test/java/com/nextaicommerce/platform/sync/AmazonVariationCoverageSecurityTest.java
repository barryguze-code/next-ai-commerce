package com.nextaicommerce.platform.sync;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import com.nextaicommerce.platform.web.*;
import com.nextaicommerce.platform.config.SecurityConfig;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest(controllers=AmazonVariationCoverageController.class,properties={"app.amazon.variation.operator-enabled=true","app.amazon.variation.coverage-enabled=true"})
@Import(SecurityConfig.class)
class AmazonVariationCoverageSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AmazonVariationCoverageGrant grant;
    @MockitoBean AmazonVariationRelationshipSync sync;
    @MockitoBean MemberAccessService members;
    @MockitoBean WorkspaceAccessRepository workspace;
    static final String URL="/app/marketplace-skus/variation-coverage";
    UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),approval=UUID.randomUUID();MockHttpSession session;
    @BeforeEach void setup(){session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.STORE_ID,store);
        when(grant.displayScope()).thenReturn(Map.of("account","Synthetic account","store","Synthetic store"));when(grant.scope()).thenReturn(new Scope(tenant,store,"TEST"));when(grant.approval()).thenReturn(approval);when(grant.inspect(false)).thenReturn(Map.of("state","NOT_STARTED"));
        when(members.current(tenant,"operator")).thenReturn(new MemberAccessService.Access("OPERATOR",true,Set.of(store)));
    }
    @Test void pageDoesNotStartAndRendersCsrfForNativeForm()throws Exception{
        var result=mvc.perform(get(URL).session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isOk()).andExpect(view().name("variation-coverage-operator")).andReturn();
        String html=result.getResponse().getContentAsString();assertThat(html).contains("Start approved relationship scan").doesNotContain("Request cancellation");verifyNoInteractions(sync);
        var token=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);assertThat(token.find()).isTrue();
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).param("approval",approval.toString()).param("_csrf",token.group(1)))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(URL));verify(sync).startCoverage(approval);
    }
    @Test void startAndCancelRequireCsrf()throws Exception{
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).param("approval",approval.toString())).andExpect(status().isForbidden());
        mvc.perform(post(URL+"/cancel").session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());verifyNoInteractions(sync);verify(grant,never()).inspect(true);
    }
    @Test void wrongAccountOrStoreRejectedEvenForPlatformAdmin()throws Exception{
        for(String field:List.of(AccountSelectionController.TENANT_ID,AccountSelectionController.STORE_ID)){
            Object old=session.getAttribute(field);session.setAttribute(field,UUID.randomUUID());
            mvc.perform(get(URL).session(session).with(user("platform").roles("PLATFORM_ADMIN"))).andExpect(status().isForbidden());session.setAttribute(field,old);
        }
        verify(grant,never()).inspect(false);verifyNoInteractions(sync);
    }
    @Test void viewersAndRevokedStoreCannotOperate()throws Exception{
        when(members.current(tenant,"viewer")).thenReturn(new MemberAccessService.Access("VIEWER",true,Set.of(store)));
        mvc.perform(get(URL).session(session).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        when(members.current(tenant,"operator")).thenReturn(new MemberAccessService.Access("OPERATOR",true,Set.of()));
        mvc.perform(post(URL+"/cancel").session(session).with(user("operator").roles("OPERATOR")).with(csrf())).andExpect(status().isForbidden());verifyNoInteractions(sync);verify(grant,never()).inspect(true);
    }
    @Test void failureExplainsPrerequisiteWithoutLeakingException()throws Exception{
        when(sync.startCoverage(approval)).thenThrow(new IllegalStateException("private database detail"));
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).with(csrf()).param("approval",approval.toString()))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("approval, catalogue manifest or active-job prerequisite"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database detail"))));
    }
    @Test void completedScanHasNoStartOrCancel()throws Exception{
        when(grant.inspect(false)).thenReturn(Map.of("state","COMPLETED","seed_count",549,"get_reserved","550","oauth_reserved","1","deadline","2026-10-22T23:00:00Z"));
        mvc.perform(get(URL).session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Start approved relationship scan")))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Request cancellation"))));verifyNoInteractions(sync);
    }
}
