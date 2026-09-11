package com.nextaicommerce.platform.collaboration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CollaborationMentionService {
    private static final Logger log=LoggerFactory.getLogger(CollaborationMentionService.class);
    private static final Pattern MENTION=Pattern.compile("(?<![\\p{Alnum}_.-])@([A-Za-z0-9][A-Za-z0-9._-]{0,79})");
    private final CollaborationRepository repository;
    private final CollaborationNotificationWorker notifications;

    CollaborationMentionService(CollaborationRepository repository, CollaborationNotificationWorker notifications) {
        this.repository=repository;
        this.notifications=notifications;
    }

    public void queue(UUID tenantId, CollaborationRepository.PostedMessage posted, String body, String authorEmail) {
        if(posted==null||posted.messageId()==null||body==null||body.isBlank()) return;
        try {
            Set<UUID> mentioned=mentionedMemberIds(body,repository.members(tenantId),authorEmail);
            if(repository.queueMentions(tenantId,posted.reviewId(),posted.messageId(),mentioned)>0) notifications.deliverAsync(tenantId);
        } catch(Exception exception) {
            log.error("Collaboration mention could not be queued — conversation {}. The message remains saved.",posted.reviewId(),exception);
        }
    }

    public void queueCompletion(UUID tenantId, CollaborationRepository.PostedMessage posted, String authorEmail) {
        if(posted==null||posted.messageId()==null)return;
        try {
            Set<UUID> participants=repository.participantMemberIds(tenantId,posted.reviewId(),authorEmail);
            if(repository.queueNotifications(tenantId,posted.reviewId(),posted.messageId(),participants,"COMPLETED")>0) notifications.deliverAsync(tenantId);
        } catch(Exception exception) {
            log.error("Conversation completion notifications could not be queued — conversation {}. The completion remains saved.",posted.reviewId(),exception);
        }
    }

    static Set<UUID> mentionedMemberIds(String body, List<CollaborationRepository.Member> members, String authorEmail) {
        var byHandle=new java.util.HashMap<String,CollaborationRepository.Member>();
        for(var member:members) byHandle.put(member.handle().toLowerCase(Locale.ROOT),member);
        var result=new LinkedHashSet<UUID>();
        var matcher=MENTION.matcher(body==null?"":body);
        while(matcher.find()) {
            var member=byHandle.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if(member!=null&&!member.email().equalsIgnoreCase(authorEmail)) result.add(member.id());
        }
        return result;
    }
}
