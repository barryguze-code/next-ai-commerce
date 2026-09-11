package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.nextaicommerce.platform.web.AccountSelectionController;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class HuddleWebSocketHandlerTest {
    @Test
    void startingAHuddleDeliversTheSameRoomToBothBrowserSessions() throws Exception {
        UUID tenant=UUID.randomUUID(),barryId=UUID.randomUUID(),ibcoreId=UUID.randomUUID();
        var repository=mock(CollaborationRepository.class);
        var notifications=mock(CollaborationNotificationWorker.class);
        var mapper=new ObjectMapper();
        var service=new HuddleService(repository,notifications,mapper);
        var handler=new HuddleWebSocketHandler(service,repository,mapper);
        var barry=session("barry-session",tenant,"barry@example.com");
        var ibcore=session("ibcore-session",tenant,"ibcore@example.com");
        when(repository.currentMember(tenant,"barry@example.com")).thenReturn(new CollaborationRepository.Member(barryId,"Barry Guze","barry@example.com","barry"));
        when(repository.currentMember(tenant,"ibcore@example.com")).thenReturn(new CollaborationRepository.Member(ibcoreId,"Ibcore","ibcore@example.com","ibcore"));
        handler.afterConnectionEstablished(barry);
        handler.afterConnectionEstablished(ibcore);
        clearInvocations(barry,ibcore);

        handler.handleTextMessage(barry,new TextMessage("{\"type\":\"CREATE\",\"participantIds\":[\""+ibcoreId+"\"],\"subjectType\":\"INVENTORY\",\"subjectKey\":\"467936|MAIN\",\"subjectLabel\":\"Morning Round Cranberry\",\"parentUrl\":\"/app/inventory\",\"contextSnapshot\":\"{}\"}"));

        var barryMessage=ArgumentCaptor.forClass(TextMessage.class);
        var ibcoreMessage=ArgumentCaptor.forClass(TextMessage.class);
        verify(barry).sendMessage(barryMessage.capture());
        verify(ibcore).sendMessage(ibcoreMessage.capture());
        assertThat(barryMessage.getValue().getPayload()).contains("HUDDLE_STARTED",barryId.toString(),ibcoreId.toString());
        assertThat(ibcoreMessage.getValue().getPayload()).contains("HUDDLE_STARTED",barryId.toString(),ibcoreId.toString());
    }

    @Test
    void presenceAndInvitationsNeverCrossAccountBoundaries() throws Exception {
        UUID ibcoreTenant=UUID.randomUUID(),otherTenant=UUID.randomUUID(),barryId=UUID.randomUUID(),outsiderId=UUID.randomUUID();
        var repository=mock(CollaborationRepository.class);
        var mapper=new ObjectMapper();
        var handler=new HuddleWebSocketHandler(new HuddleService(repository,mock(CollaborationNotificationWorker.class),mapper),repository,mapper);
        var outsider=session("outside-session",otherTenant,"outside@example.com");
        var barry=session("barry-session",ibcoreTenant,"barry@example.com");
        when(repository.currentMember(otherTenant,"outside@example.com")).thenReturn(new CollaborationRepository.Member(outsiderId,"Outside User","outside@example.com","outside"));
        when(repository.currentMember(ibcoreTenant,"barry@example.com")).thenReturn(new CollaborationRepository.Member(barryId,"Barry Guze","barry@example.com","barry"));
        handler.afterConnectionEstablished(outsider);
        handler.afterConnectionEstablished(barry);

        var welcome=ArgumentCaptor.forClass(TextMessage.class);
        verify(barry,atLeastOnce()).sendMessage(welcome.capture());
        assertThat(welcome.getAllValues()).allSatisfy(message->assertThat(message.getPayload()).doesNotContain("Outside User","outside@example.com",outsiderId.toString()));
        clearInvocations(outsider,barry);

        handler.handleTextMessage(barry,new TextMessage("{\"type\":\"CREATE\",\"participantIds\":[\""+outsiderId+"\"],\"subjectType\":\"PLATFORM\",\"subjectKey\":\"GENERAL\",\"subjectLabel\":\"General team huddle\"}"));

        verify(outsider,never()).sendMessage(any(TextMessage.class));
        var rejected=ArgumentCaptor.forClass(TextMessage.class);
        verify(barry).sendMessage(rejected.capture());
        assertThat(rejected.getValue().getPayload()).contains("ERROR","no longer online");
    }

    private static WebSocketSession session(String sessionId,UUID tenant,String email){
        var session=mock(WebSocketSession.class);
        Principal principal=new UsernamePasswordAuthenticationToken(email,"n/a",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(session.getId()).thenReturn(sessionId);
        when(session.getPrincipal()).thenReturn(principal);
        when(session.getAttributes()).thenReturn(Map.of(AccountSelectionController.TENANT_ID,tenant));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
