package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.invitation.PlatformMailer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CollaborationNotificationWorker {
    private static final Logger log=LoggerFactory.getLogger(CollaborationNotificationWorker.class);
    private final CollaborationRepository repository;
    private final PlatformMailer mailer;
    private final boolean mailEnabled;
    private final String publicUrl;

    CollaborationNotificationWorker(CollaborationRepository repository, PlatformMailer mailer,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${app.public-url:http://localhost:8080}") String publicUrl) {
        this.repository=repository;
        this.mailer=mailer;
        this.mailEnabled=mailEnabled;
        this.publicUrl=publicUrl.replaceAll("/+$","");
    }

    @Async
    public void deliverAsync(UUID tenantId) { deliverTenant(tenantId); }

    @Scheduled(fixedDelayString="${app.collaboration.notification-retry-ms:60000}",
        initialDelayString="${app.collaboration.notification-initial-delay-ms:30000}")
    public void retryPending() {
        if(!mailEnabled) return;
        for(UUID tenantId:repository.activeTenantIds()) deliverTenant(tenantId);
    }

    void deliverTenant(UUID tenantId) {
        for(var mention:repository.claimPendingMentions(tenantId,20)) {
            if(!mailEnabled) {
                repository.mentionFailed(tenantId,mention.id(),"Email delivery is not configured.",false);
                continue;
            }
            try {
                String url=publicUrl+"/app/collaboration?view="+
                    ("COMPLETED".equals(mention.notificationKind())?"CLOSED":"MENTIONS")+"&threadId="+mention.reviewId();
                mailer.sendHtml(mention.recipientEmail(),CollaborationEmailContent.subject(mention),
                    CollaborationEmailContent.html(mention,url));
                repository.mentionSent(tenantId,mention.id());
                log.info("Collaboration email sent — {} — {} — conversation about {}",mention.notificationKind(),mention.recipientEmail(),mention.subjectLabel());
            } catch(Exception exception) {
                repository.mentionFailed(tenantId,mention.id(),exception.getMessage(),true);
                log.warn("Collaboration mention email will retry — {} — {}",mention.recipientEmail(),exception.getMessage());
            }
        }
    }
}
