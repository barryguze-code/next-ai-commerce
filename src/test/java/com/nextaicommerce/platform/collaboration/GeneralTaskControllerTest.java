package com.nextaicommerce.platform.collaboration;

import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.web.AccountSelectionController;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneralTaskControllerTest {
    private final CollaborationRepository repository=mock(CollaborationRepository.class);
    private final CollaborationMentionService mentions=mock(CollaborationMentionService.class);
    private final CollaborationController controller=new CollaborationController(repository,mentions,new ObjectMapper());
    private final UUID tenant=UUID.randomUUID(),person=UUID.randomUUID(),review=UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken user=new UsernamePasswordAuthenticationToken("creator@example.com","unused");
    private MockHttpSession session(){var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,tenant);return session;}

    @Test void createsAccountTaskWithMultipleAssigneesAndLocalDueDate(){
        UUID second=UUID.randomUUID();var people=Set.of(person,second);var posted=new CollaborationRepository.PostedMessage(review,UUID.randomUUID());
        when(repository.createStoreTask(tenant,null,"Follow up","Details",user.getName(),people,LocalDate.parse("2026-10-02"),"America/Los_Angeles")).thenReturn(posted);
        var response=controller.createGeneralTask(" Follow up "," Details ",people,"2026-10-02","America/Los_Angeles",session(),user);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().toString()).contains("threadId="+review);
        verify(repository).createStoreTask(tenant,null,"Follow up","Details",user.getName(),people,LocalDate.parse("2026-10-02"),"America/Los_Angeles");
        assertThat(CollaborationRepository.deadline(LocalDate.parse("2026-10-02"),"America/Los_Angeles")).isEqualTo(Instant.parse("2026-10-03T07:00:00Z"));
    }
    @Test void rejectsInvalidInputBeforeSaving(){
        assertThat(controller.createGeneralTask(" ","Details",null,null,"UTC",session(),user).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.createGeneralTask("Title","Details",null,"not-a-date","UTC",session(),user).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.createGeneralTask("Title","x".repeat(4001),null,null,"UTC",session(),user).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(repository);
    }
    @Test void allowsUnassignedWithoutDueDate(){
        when(repository.createStoreTask(tenant,null,"Title","Details",user.getName(),Set.of(),null,"UTC")).thenReturn(new CollaborationRepository.PostedMessage(review,UUID.randomUUID()));
        assertThat(controller.createGeneralTask("Title","Details",null,"","UTC",session(),user).getStatusCode().value()).isEqualTo(200);
        verify(repository).createStoreTask(tenant,null,"Title","Details",user.getName(),Set.of(),null,"UTC");
    }
}
