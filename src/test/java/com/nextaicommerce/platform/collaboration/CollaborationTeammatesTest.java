package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

class CollaborationTeammatesTest {
    @Test void directoryUsesOnlyActorsSharedAccountsAndDeduplicatesPeople() {
        var repository=mock(CollaborationRepository.class);
        var first=UUID.randomUUID();var second=UUID.randomUUID();
        var jack=new CollaborationRepository.Member(UUID.randomUUID(),"Jack","jack@example.com","jack");
        var alex=new CollaborationRepository.Member(UUID.randomUUID(),"Alex","alex@example.com","alex");
        when(repository.huddleTenants("me@example.com")).thenReturn(Set.of(first,second));
        when(repository.members(first)).thenReturn(List.of(jack,alex));
        when(repository.members(second)).thenReturn(List.of(jack));
        var controller=new CollaborationController(repository,mock(CollaborationMentionService.class),new ObjectMapper());
        var response=controller.teammates(new UsernamePasswordAuthenticationToken("me@example.com","unused"));
        assertThat(response.getBody()).containsExactly(alex,jack);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(repository).huddleTenants("me@example.com");verify(repository).members(first);verify(repository).members(second);
        verifyNoMoreInteractions(repository);
    }
    @Test void noAcceptedSharedAccountsMeansNoDirectory() {
        var repository=mock(CollaborationRepository.class);
        when(repository.huddleTenants("me@example.com")).thenReturn(Set.of());
        var controller=new CollaborationController(repository,mock(CollaborationMentionService.class),new ObjectMapper());
        assertThat(controller.teammates(new UsernamePasswordAuthenticationToken("me@example.com","unused")).getBody()).isEmpty();
        verify(repository).huddleTenants("me@example.com");verifyNoMoreInteractions(repository);
    }
}
