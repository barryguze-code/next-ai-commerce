package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;

class PersonalCollaborationActionsTest {
    @Test void explicitTaskAccountAndStoreRequireTheirOwnAuthorityBeforeAnyWrite() {
        var personal=mock(PersonalCollaborationQueue.class);
        var queue=mock(CollaborationTaskQueueRepository.class);
        var reviews=mock(CollaborationRepository.class);
        var mentions=mock(CollaborationMentionService.class);
        var accounts=mock(WorkspaceAccessRepository.class);
        var actions=new PersonalCollaborationActions(personal,queue,reviews,mentions,accounts);
        UUID allowed=UUID.randomUUID(),other=UUID.randomUUID();
        when(accounts.listAccountOptions("operator",false)).thenReturn(List.of(
            new WorkspaceAccessRepository.AccountOption(allowed,"Allowed","A",null,List.of())));
        assertThatThrownBy(()->actions.create("operator",false,other,null,"Task","",Set.of(),null,"UTC"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->actions.create("operator",false,allowed,null,"Task","",Set.of(),null,"UTC"))
            .isInstanceOf(AccessDeniedException.class);
        when(accounts.canOperateAccount(allowed,"operator")).thenReturn(true);
        assertThatThrownBy(()->actions.create("operator",false,allowed,other,"Task","",Set.of(),null,"UTC"))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(reviews,mentions,queue);
    }
}
