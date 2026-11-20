package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import com.nextaicommerce.platform.web.*;
import tools.jackson.databind.ObjectMapper;

class MarketplaceSkuCsvControllerTest {
    final MarketplaceSkuCsvExport export=mock(MarketplaceSkuCsvExport.class);
    final WorkspaceAccessRepository workspace=mock(WorkspaceAccessRepository.class);
    final MarketplaceSkuCsvController controller=new MarketplaceSkuCsvController(export,workspace,new ObjectMapper());
    final UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();final MockHttpSession session=new MockHttpSession();
    final UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("viewer@example.test","",List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
    MarketplaceSkuCsvControllerTest(){session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.STORE_ID,connection);}
    List<WorkspaceAccessRepository.AccountOption> allowed(){return List.of(new WorkspaceAccessRepository.AccountOption(tenant,"Test","T",null,List.of(new WorkspaceAccessRepository.ConnectionView(connection,tenant,"Test","Store","AMAZON",null,"US",null,null,"ACTIVE",null,false))));}
    Map<String,String> params(){return Map.of("columns","product-details,profit","columnFilters","{}","q","Tea");}
    @Test void authorizedViewerReceivesCompleteNoStoreArtifact(){
        when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(allowed());when(export.generate(eq(tenant),eq(connection),any())).thenReturn(new MarketplaceSkuCsvExport.Artifact(new byte[]{1,2,3},1,Instant.parse("2026-10-19T12:00:00Z")));
        var response=controller.export(params(),session,auth);assertThat(response.getHeaders().getFirst("X-CSV-Rows")).isEqualTo("1");assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");verify(workspace,times(2)).listAccountOptions(auth.getName(),false);
    }
    @Test void unauthorizedStoreNeverInvokesGenerator(){when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(List.of());assertThatThrownBy(()->controller.export(params(),session,auth)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(export);}
    @Test void contextChangeDiscardsCompletedArtifact(){
        when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(allowed());when(export.generate(any(),any(),any())).thenAnswer(i->{session.setAttribute(AccountSelectionController.STORE_ID,UUID.randomUUID());return new MarketplaceSkuCsvExport.Artifact(new byte[3],1,Instant.now());});assertThatThrownBy(()->controller.export(params(),session,auth)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("changed");
    }
    @Test void revokedAccessDiscardsArtifact(){when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(allowed(),List.of());when(export.generate(any(),any(),any())).thenReturn(new MarketplaceSkuCsvExport.Artifact(new byte[3],1,Instant.now()));assertThatThrownBy(()->controller.export(params(),session,auth)).isInstanceOf(ResponseStatusException.class);}
    @Test void databaseErrorHasNoSqlDetailsOrPartialResponse(){when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(allowed());when(export.generate(any(),any(),any())).thenThrow(new IllegalStateException("secret SQL password"));assertThatThrownBy(()->controller.export(params(),session,auth)).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("secret").hasMessageContaining("No complete download");}
    @Test void httpErrorContractProvidesTruthfulSafeActionableJson()throws Exception{
        when(workspace.listAccountOptions(auth.getName(),false)).thenReturn(allowed());when(export.generate(any(),any(),any())).thenThrow(new IllegalArgumentException("More than 10,000 listings. Narrow search or server filters."));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/app/marketplace-skus/export.csv").session(session).principal(auth).param("columns","product-details"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail").value("More than 10,000 listings. Narrow search or server filters."));
    }
}
