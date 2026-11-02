package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollaborationMentionServiceTest {
    @Test
    void resolvesSelectedAccountHandlesOnceAndDoesNotNotifyTheAuthor() {
        UUID barry=UUID.randomUUID(),alex=UUID.randomUUID();
        var members=List.of(
            new CollaborationRepository.Member(barry,"Barry Guze","barry@example.com","barry.guze"),
            new CollaborationRepository.Member(alex,"Alex Smith","alex@example.com","alex.smith"));

        assertThat(CollaborationMentionService.mentionedMemberIds(
            "@alex.smith please check this. @alex.smith again; @unknown no.",members,"barry@example.com"))
            .containsExactly(alex);
        assertThat(CollaborationMentionService.mentionedMemberIds(
            "I should not email myself @barry.guze",members,"BARRY@example.com")).isEmpty();
    }

    @Test void replyMentionsNotifyWithoutCreatingAssignments(){
        var repository=mock(CollaborationRepository.class);var notifications=mock(CollaborationNotificationWorker.class);
        var service=new CollaborationMentionService(repository,notifications);
        UUID account=UUID.randomUUID(),review=UUID.randomUUID(),message=UUID.randomUUID(),recipient=UUID.randomUUID();
        when(repository.members(account)).thenReturn(List.of(new CollaborationRepository.Member(recipient,"Teammate","other@example.test","teammate")));
        when(repository.queueMentions(account,review,message,java.util.Set.of(recipient))).thenReturn(1);
        service.queueReply(account,new CollaborationRepository.PostedMessage(review,message),"Please check @teammate","sender@example.test");
        verify(repository).queueMentions(account,review,message,java.util.Set.of(recipient));verify(notifications).deliverAsync(account);
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.never()).assign(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anySet(),org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void completingAConversationQueuesEmailForEveryOtherParticipant() {
        var repository=mock(CollaborationRepository.class);
        var notifications=mock(CollaborationNotificationWorker.class);
        var service=new CollaborationMentionService(repository,notifications);
        UUID tenant=UUID.randomUUID(),review=UUID.randomUUID(),message=UUID.randomUUID();
        var participants=java.util.Set.of(UUID.randomUUID(),UUID.randomUUID());
        when(repository.participantMemberIds(tenant,review,"closer@example.com")).thenReturn(participants);
        when(repository.queueNotifications(tenant,review,message,participants,"COMPLETED")).thenReturn(2);

        service.queueCompletion(tenant,new CollaborationRepository.PostedMessage(review,message),"closer@example.com");

        verify(repository).queueNotifications(tenant,review,message,participants,"COMPLETED");
        verify(notifications).deliverAsync(tenant);
    }
}
