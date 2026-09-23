package com.nextaicommerce.platform.receiving;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.catalog.CatalogRepository;
class ReceivingWorkflowControllerTest {
    @Test void quickLocationChecksCurrentAccountAndReturnsChoices(){
        var catalog=mock(CatalogRepository.class);var access=mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var controller=new ReceivingWorkflowController(mock(ReceivingWorkflowRepository.class),catalog);controller.configureAccess(access);
        UUID tenant=UUID.randomUUID(),location=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);
        var auth=new UsernamePasswordAuthenticationToken("qa@example.test","unused",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER")));
        assertThat(controller.addLocation("OVERFLOW-A","Overflow",auth,session).getStatusCode().value()).isEqualTo(403);verifyNoInteractions(catalog);
        when(access.canOperateAccount(tenant,auth.getName())).thenReturn(true);when(catalog.addLocation(tenant,"OVERFLOW-A","Overflow")).thenReturn(location);when(catalog.listLocations(tenant)).thenReturn(List.of());
        assertThat(controller.addLocation("OVERFLOW-A","Overflow",auth,session).getStatusCode().value()).isEqualTo(200);
        verify(catalog).addLocation(tenant,"OVERFLOW-A","Overflow");
    }
    @Test void completedDocumentsCannotEnterMultiDocumentReceiving(){
        for(boolean closed:List.of(true,false)){
            var repository=mock(ReceivingWorkflowRepository.class);
            var controller=new ReceivingWorkflowController(repository,mock(CatalogRepository.class));
            var tenant=UUID.randomUUID();var first=UUID.randomUUID();var second=UUID.randomUUID();
            var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);
            var completed=new ReceivingWorkflowRepository.Document(first,UUID.randomUUID(),"Vendor","INVOICE","123","invoice.csv",
                java.time.LocalDate.now(),java.time.Instant.now(),"USD",java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO,closed,false,true,null,UUID.randomUUID());
            when(repository.documents(tenant,List.of(first,second))).thenReturn(List.of(completed,completed));
            var redirect=new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
            var result=controller.work(List.of(first,second),new UsernamePasswordAuthenticationToken("qa@example.test","n/a"),session,new org.springframework.ui.ExtendedModelMap(),redirect);
            assertThat(result).isEqualTo("redirect:/app/receiving");
            assertThat(redirect.getFlashAttributes().get("catalogError").toString()).contains("closed or fully received");
            verify(repository,never()).lines(any(),any());
        }
    }
    @Test void receivePayloadDoesNotRequireCloseOnlyFields(){
        var json=tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var command=json.readValue("{\"requestId\":\""+UUID.randomUUID()+"\",\"quantity\":2,\"disposition\":\"SELLABLE\"}",ReceivingWorkflowController.Command.class);
        assertThat(command.creditExpected()).isNull();
        assertThat(command.quantity()).isEqualByComparingTo("2");
    }
    @Test void selectionIsBoundedAndDeduplicated(){
        UUID id=UUID.randomUUID();
        assertThat(ReceivingWorkflowController.selection(List.of(id,id))).containsExactly(id);
        assertThatThrownBy(()->ReceivingWorkflowController.selection(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ReceivingWorkflowController.selection(Collections.nCopies(13,id))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedCommandsAndMissingAccountNeverTouchInventory(){
        var work=mock(ReceivingWorkflowRepository.class);var controller=new ReceivingWorkflowController(work,mock(CatalogRepository.class));
        var session=new MockHttpSession();var auth=new UsernamePasswordAuthenticationToken("qa@example.test","n/a");
        var c=new ReceivingWorkflowController.Command(UUID.randomUUID(),null,null,null,null,null,null,null,null,false,null,null);
        assertThat(controller.change("receive",UUID.randomUUID(),c,auth,session).getStatusCode().value()).isEqualTo(400);
        session.setAttribute("selectedTenantId",UUID.randomUUID());
        assertThat(controller.change("erase",UUID.randomUUID(),c,auth,session).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(work);
    }
}
