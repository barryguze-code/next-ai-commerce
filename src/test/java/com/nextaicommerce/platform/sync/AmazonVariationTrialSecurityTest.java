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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@WebMvcTest(controllers=AmazonVariationTrialController.class,properties="app.amazon.variation.operator-enabled=true")
@Import(SecurityConfig.class)
class AmazonVariationTrialSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AmazonVariationTrial trial;
    @MockitoBean AmazonVariationRelationshipSync sync;
    @MockitoBean MemberAccessService members;
    @MockitoBean WorkspaceAccessRepository workspace;
    MockHttpSession session;
    static final String URL="/app/marketplace-skus/variation-trial";
    @BeforeEach void setup(){session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,AmazonVariationTrial.TENANT);session.setAttribute(AccountSelectionController.STORE_ID,AmazonVariationTrial.CONNECTION);
        when(members.current(AmazonVariationTrial.TENANT,"operator")).thenReturn(new MemberAccessService.Access("OPERATOR",true,Set.of(AmazonVariationTrial.CONNECTION)));}
    @Test void startRequiresCsrf()throws Exception{
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).param("idempotencyKey",UUID.randomUUID().toString())).andExpect(status().isForbidden());verifyNoInteractions(sync);}
    @Test void viewerCannotInspectOrCancel()throws Exception{
        when(members.current(AmazonVariationTrial.TENANT,"viewer")).thenReturn(new MemberAccessService.Access("VIEWER",true,Set.of(AmazonVariationTrial.CONNECTION)));
        mvc.perform(get(URL).session(session).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post(URL+"/cancel").session(session).with(user("viewer").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());verifyNoInteractions(trial);}
    @Test void anotherSelectedStoreRejectedEvenForPlatformAdmin()throws Exception{
        session.setAttribute(AccountSelectionController.STORE_ID,UUID.randomUUID());
        mvc.perform(post(URL+"/start").session(session).with(user("platform").roles("PLATFORM_ADMIN")).with(csrf()).param("idempotencyKey",UUID.randomUUID().toString())).andExpect(status().isForbidden());verifyNoInteractions(sync);}
    @Test void revokedStoreRejected()throws Exception{
        when(members.current(AmazonVariationTrial.TENANT,"operator")).thenReturn(new MemberAccessService.Access("OPERATOR",true,Set.of()));
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).with(csrf()).param("idempotencyKey",UUID.randomUUID().toString())).andExpect(status().isForbidden());verifyNoInteractions(sync);}
    @Test void operatorCanStartAndCancel()throws Exception{
        UUID key=UUID.randomUUID(),job=UUID.randomUUID();when(sync.startTrial(key)).thenReturn(job);when(trial.inspect(true)).thenReturn(Map.of("state","CANCELLED"));
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).with(csrf()).param("idempotencyKey",key.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(job.toString()));
        mvc.perform(post(URL+"/cancel").session(session).with(user("operator").roles("OPERATOR")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));verify(sync).startTrial(key);verify(trial).inspect(true);}
    @Test void disabledStartReportsConflict()throws Exception{
        when(sync.startTrial(any())).thenThrow(new IllegalStateException("disabled"));
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).with(csrf()).param("idempotencyKey",UUID.randomUUID().toString())).andExpect(status().isConflict());}
    @Test void cancelRequiresCsrfAndNoAcquisitionCall()throws Exception{
        mvc.perform(post(URL+"/cancel").session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());verifyNoInteractions(trial,sync);
        when(sync.startTrial(any())).thenThrow(new IllegalStateException("Acquisition is off"));when(trial.inspect(true)).thenReturn(Map.of("state","CANCELLED"));
        mvc.perform(post(URL+"/cancel").session(session).with(user("operator").roles("OPERATOR")).with(csrf())).andExpect(status().isOk());verify(trial).inspect(true);verifyNoInteractions(sync);
    }
    @Test void wrongAccountAndRevokedMembershipCannotOperate()throws Exception{
        session.setAttribute(AccountSelectionController.TENANT_ID,UUID.randomUUID());
        mvc.perform(get(URL).session(session).with(user("platform").roles("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
        session.setAttribute(AccountSelectionController.TENANT_ID,AmazonVariationTrial.TENANT);when(members.current(AmazonVariationTrial.TENANT,"operator")).thenReturn(null);
        mvc.perform(post(URL+"/cancel").session(session).with(user("operator").roles("OPERATOR")).with(csrf())).andExpect(status().isForbidden());verifyNoInteractions(trial,sync);
    }
    @Test void operatorPageHasStableServerOwnedKeyAndDoesNotInspectOrStart()throws Exception{
        var first=mvc.perform(get(URL+"/operator").session(session).with(user("operator").roles("OPERATOR")))
            .andExpect(status().isOk()).andExpect(view().name("variation-trial-operator"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Start approved KARACA trial"))).andReturn();
        Object key=first.getModelAndView().getModel().get("trialKey");
        org.assertj.core.api.Assertions.assertThat(key).isInstanceOf(UUID.class);
        mvc.perform(get(URL+"/operator").session(session).with(user("operator").roles("OPERATOR")))
            .andExpect(status().isOk()).andExpect(model().attribute("trialKey",key))
            .andExpect(model().attribute("seedSku",AmazonVariationTrial.SEED));
        verifyNoInteractions(trial,sync);
    }
    @Test void pageRequiresAuthenticationAndExactScopeEvenForAdmin()throws Exception{
        mvc.perform(get(URL+"/operator")).andExpect(status().is3xxRedirection());
        session.setAttribute(AccountSelectionController.TENANT_ID,UUID.randomUUID());
        mvc.perform(get(URL+"/operator").session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get(URL+"/operator").session(session).with(user("platform").roles("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(trial,sync);
    }
    @Test void pageRejectsViewerAndRevokedStore()throws Exception{
        when(members.current(AmazonVariationTrial.TENANT,"viewer")).thenReturn(new MemberAccessService.Access("VIEWER",true,Set.of(AmazonVariationTrial.CONNECTION)));
        mvc.perform(get(URL+"/operator").session(session).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        when(members.current(AmazonVariationTrial.TENANT,"operator")).thenReturn(new MemberAccessService.Access("OPERATOR",true,Set.of()));
        mvc.perform(get(URL+"/operator").session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());verifyNoInteractions(trial,sync);
    }
    @Test void repeatedSameKeyRetainsExistingStartContract()throws Exception{
        UUID key=UUID.randomUUID(),job=UUID.randomUUID();when(sync.startTrial(key)).thenReturn(job);
        for(int i=0;i<2;i++)mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR")).with(csrf()).param("idempotencyKey",key.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(job.toString()));
        verify(sync,times(2)).startTrial(key);verifyNoInteractions(trial);
    }
    @Test void csrfTokenRenderedByTheRealPageIsAcceptedByTheExistingStart()throws Exception{
        var result=mvc.perform(get(URL+"/operator").session(session).with(user("operator").roles("OPERATOR"))).andExpect(status().isOk()).andReturn();
        var token=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(token.find()).isTrue();
        UUID key=(UUID)result.getModelAndView().getModel().get("trialKey"),job=UUID.randomUUID();when(sync.startTrial(key)).thenReturn(job);
        mvc.perform(post(URL+"/start").session(session).with(user("operator").roles("OPERATOR"))
            .param("idempotencyKey",key.toString()).param("_csrf",token.group(1)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(job.toString()));
        verify(sync).startTrial(key);
    }

}
