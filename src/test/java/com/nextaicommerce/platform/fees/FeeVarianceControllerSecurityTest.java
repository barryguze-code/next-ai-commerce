package com.nextaicommerce.platform.fees;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.config.SecurityConfig;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
@WebMvcTest(controllers=FeeVarianceController.class,excludeFilters=@org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ANNOTATION,classes=org.springframework.web.bind.annotation.ControllerAdvice.class)) @Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(properties={"app.local-admin.email=synthetic@example.invalid","app.local-admin.password=synthetic"})
class FeeVarianceControllerSecurityTest {
 @Autowired MockMvc mvc;@MockitoBean AmazonFeeVarianceRepository fees;
 UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
 @BeforeEach void setup(){when(fees.visible()).thenReturn(true);when(fees.context(any(),any())).thenReturn(Map.of("display_name","Synthetic","marketplace_identifier","TEST"));when(fees.historyPage(any(),any(),anyString(),anyString(),anyInt(),any())).thenReturn(new AmazonFeeVarianceRepository.HistoryPage(List.of(),false,0,0));when(fees.approveAll(any(),any(),any(),any(),anyString())).thenReturn(List.of(UUID.randomUUID()));}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(){return get("/app/fee-variance").param("order","SYNTHETIC").sessionAttr(AccountSelectionController.TENANT_ID,tenant).sessionAttr(AccountSelectionController.STORE_ID,store);}
 @Test void viewerCanReadSelectedStoreEvidence()throws Exception{mvc.perform(request().with(user("reader").roles("VIEWER"))).andExpect(status().isOk());verify(fees).historyPage(tenant,store,"SYNTHETIC","",0,null);}
 @Test void readOnlyUiReturnsHonestEmptyHistoryForViewerAndOperator()throws Exception{
  when(fees.approvalsEnabled()).thenReturn(false);
  for(String role:List.of("VIEWER","OPERATOR"))mvc.perform(request().with(user("reader").roles(role))).andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty()).andExpect(jsonPath("$.canApprove").value(false)).andExpect(jsonPath("$.hasMore").value(false));
 }
 @Test void readOnlyUiRejectsForgedOwnerApprovalWithValidCsrf()throws Exception{
  when(fees.approveAll(any(),any(),any(),any(),anyString())).thenThrow(new IllegalArgumentException("Fee approval is not enabled"));
  mvc.perform(post("/app/fee-variance/approve").sessionAttr(AccountSelectionController.TENANT_ID,tenant).sessionAttr(AccountSelectionController.STORE_ID,store).with(user("owner").roles("OWNER")).with(csrf()).contentType("application/json").content("{\"items\":[],\"request\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isConflict());
 }
 @Test void missingScopeIsRejected()throws Exception{mvc.perform(get("/app/fee-variance").param("order","X").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());verify(fees,never()).historyPage(any(),any(),anyString(),anyString(),anyInt(),any());}
 @Test void operatorCannotApproveFinancialBasis()throws Exception{mvc.perform(post("/app/fee-variance/approve").with(user("operator").roles("OPERATOR")).with(csrf()).contentType("application/json").content("{\"items\":[],\"request\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isForbidden());verify(fees,never()).approveAll(any(),any(),any(),any(),anyString());}
 @Test void ownerStillNeedsCsrf()throws Exception{mvc.perform(post("/app/fee-variance/approve").with(user("owner").roles("OWNER")).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
 @Test void disabledUiDoesNotExposeEvidence()throws Exception{when(fees.visible()).thenReturn(false);mvc.perform(request().with(user("reader").roles("VIEWER"))).andExpect(status().isNotFound());verify(fees,never()).historyPage(any(),any(),anyString(),anyString(),anyInt(),any());}
}
