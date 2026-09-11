package com.nextaicommerce.platform.collaboration;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nextaicommerce.platform.invitation.PlatformMailer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollaborationNotificationWorkerTest {
    @Test
    void completedConversationEmailLinksToCompletedHistory() {
        var repository=mock(CollaborationRepository.class);
        var mailer=mock(PlatformMailer.class);
        UUID tenant=UUID.randomUUID(),notification=UUID.randomUUID();
        UUID review=UUID.randomUUID();
        var pending=new CollaborationRepository.PendingMention(notification,review,"Alex","alex@example.com","Ibcore",
            "Morning Round inventory","barry@example.com","Conversation completed by barry@example.com.","COMPLETED");
        when(repository.claimPendingMentions(tenant,20)).thenReturn(List.of(pending));
        var worker=new CollaborationNotificationWorker(repository,mailer,true,"https://commerce.example.test/");

        worker.deliverTenant(tenant);

        verify(mailer).sendHtml(org.mockito.ArgumentMatchers.eq("alex@example.com"),
            org.mockito.ArgumentMatchers.eq("Conversation completed · Morning Round inventory"),
            argThat(html->html.contains("/app/collaboration?view=CLOSED&amp;threadId="+review)));
        verify(repository).mentionSent(tenant,notification);
    }
}
