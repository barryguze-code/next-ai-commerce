package com.nextaicommerce.platform.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.invitation.InvitationWorkflowService;
class InvitationExistingAccessTest {
 private final WorkspaceAccessRepository accounts=mock(WorkspaceAccessRepository.class);
 private final InvitationWorkflowService workflow=mock(InvitationWorkflowService.class);
 private final InvitationController controller=new InvitationController(workflow,accounts);
 private final UUID account=UUID.randomUUID(),store=UUID.randomUUID();
 private final UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("admin@example.test","unused");
 @Test void forbiddenLookupCannotReadMembers(){assertThatThrownBy(()->controller.existingAccess(account,"person@example.test",auth)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verify(accounts,never()).listMembers(any());verifyNoInteractions(workflow);}
 @Test void lookupUsesOnlyAuthorizedAccountAndReturnsNoPersonalDirectory(){when(accounts.canAdministerAccount(account,auth.getName(),false)).thenReturn(true);when(accounts.listMembers(account)).thenReturn(List.of(new WorkspaceAccessRepository.MemberView(UUID.randomUUID(),"Person","person@example.test","VIEWER","ACTIVE",1,List.of(store),false)));var response=controller.existingAccess(account," PERSON@example.test ",auth);assertThat(response.getBody()).isEqualTo(Map.of("storeIds",List.of(store)));assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(controller.existingAccess(account,"unknown@example.test",auth).getBody()).isEqualTo(Map.of("storeIds",List.of()));verifyNoInteractions(workflow);}
 @Test void inactiveMemberDoesNotAssertActiveGrant(){when(accounts.canAdministerAccount(account,auth.getName(),false)).thenReturn(true);when(accounts.listMembers(account)).thenReturn(List.of(new WorkspaceAccessRepository.MemberView(UUID.randomUUID(),"Person","person@example.test","VIEWER","INACTIVE",1,List.of(store),false)));assertThat(controller.existingAccess(account,"person@example.test",auth).getBody()).isEqualTo(Map.of("storeIds",List.of()));}
}
