package com.nextaicommerce.platform.collaboration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Short-lived collaboration held only in this application process. Nothing in a huddle reaches
 * PostgreSQL unless a participant explicitly saves it to the normal collaboration history.
 */
@Service
public class HuddleService {
    private static final Logger log=LoggerFactory.getLogger(HuddleService.class);
    static final Duration IDLE_TTL=Duration.ofMinutes(45);
    static final Duration ENDED_RETENTION=Duration.ofMinutes(5);
    static final int MAX_PARTICIPANTS=3;
    static final int MAX_MESSAGES=200;
    static final int MAX_MESSAGE_LENGTH=2_000;
    static final int MAX_ACTIVE_PER_USER=4;
    static final int MAX_ACTIVE_PER_TENANT=250;

    public record Participant(UUID id,String name,String email) {}
    public record LiveMessage(UUID id,UUID senderId,String senderName,String senderEmail,String body,Instant createdAt) {}
    public record HuddleView(UUID id,String subjectType,String subjectKey,String subjectLabel,String parentUrl,
            String contextSnapshot,Participant startedBy,List<Participant> participants,Set<UUID> joinedParticipantIds,List<LiveMessage> messages,
            Instant createdAt,Instant expiresAt,String status,UUID savedReviewId) {}
    public record SaveResult(HuddleView huddle,UUID reviewId) {}
    public record ExpiredHuddle(UUID tenantId,HuddleView huddle) {}

    private static final class HuddleState {
        final UUID id=UUID.randomUUID();
        final UUID tenantId;
        final String subjectType;
        final String subjectKey;
        final String subjectLabel;
        final String parentUrl;
        final String contextSnapshot;
        final Participant startedBy;
        final Map<UUID,Participant> participants=new LinkedHashMap<>();
        final Set<UUID> joinedParticipantIds=new LinkedHashSet<>();
        final List<LiveMessage> messages=new ArrayList<>();
        final Instant createdAt=Instant.now();
        Instant expiresAt=createdAt.plus(IDLE_TTL);
        Instant endedAt;
        UUID savedReviewId;
        boolean saving;

        HuddleState(UUID tenantId,String subjectType,String subjectKey,String subjectLabel,String parentUrl,
                String contextSnapshot,Participant startedBy,Collection<Participant> invited){
            this.tenantId=tenantId;this.subjectType=subjectType;this.subjectKey=subjectKey;
            this.subjectLabel=subjectLabel;this.parentUrl=parentUrl;this.contextSnapshot=contextSnapshot;this.startedBy=startedBy;
            participants.put(startedBy.id(),startedBy);joinedParticipantIds.add(startedBy.id());invited.forEach(person->participants.put(person.id(),person));
        }
    }

    private final Map<UUID,HuddleState> huddles=new ConcurrentHashMap<>();
    private final CollaborationRepository collaboration;
    private final CollaborationNotificationWorker notifications;
    private final ObjectMapper json;

    public HuddleService(CollaborationRepository collaboration,CollaborationNotificationWorker notifications,ObjectMapper json){
        this.collaboration=collaboration;this.notifications=notifications;this.json=json;
    }

    public HuddleView create(UUID tenantId,Participant creator,String subjectType,String subjectKey,String subjectLabel,
            String parentUrl,String contextSnapshot,List<Participant> invited){
        if(invited==null||invited.isEmpty())throw new IllegalArgumentException("Choose at least one online teammate.");
        var unique=new LinkedHashMap<UUID,Participant>();
        invited.forEach(person->{if(person!=null&&!person.id().equals(creator.id()))unique.put(person.id(),person);});
        if(unique.isEmpty()||unique.size()>=MAX_PARTICIPANTS)throw new IllegalArgumentException("Invite one or two teammates to a huddle.");
        String safeType=clean(subjectType,40,"RECORD").toUpperCase();String safeKey=clean(subjectKey,240,"GENERAL");
        for(HuddleState existing:huddles.values())synchronized(existing){
            if(existing.tenantId.equals(tenantId)&&existing.endedAt==null&&existing.subjectType.equals(safeType)
                    &&existing.subjectKey.equals(safeKey)&&existing.participants.containsKey(creator.id())){
                long additional=unique.keySet().stream().filter(id->!existing.participants.containsKey(id)).count();
                if(existing.participants.size()+additional>MAX_PARTICIPANTS)throw new IllegalArgumentException("Invite up to two teammates to a huddle.");
                unique.values().forEach(person->existing.participants.putIfAbsent(person.id(),person));
                existing.expiresAt=Instant.now().plus(IDLE_TTL);return view(existing);
            }
        }
        long tenantActive=huddles.values().stream().filter(huddle->{synchronized(huddle){return huddle.tenantId.equals(tenantId)&&huddle.endedAt==null;}}).count();
        if(tenantActive>=MAX_ACTIVE_PER_TENANT)throw new IllegalArgumentException("This account has reached its temporary huddle limit. Finish an active huddle and try again.");
        long active=huddles.values().stream().filter(huddle->{synchronized(huddle){return huddle.tenantId.equals(tenantId)
            &&huddle.endedAt==null&&huddle.participants.containsKey(creator.id());}}).count();
        if(active>=MAX_ACTIVE_PER_USER)throw new IllegalArgumentException("Finish one of your active huddles before starting another.");
        String snapshot=validSnapshot(contextSnapshot);String safeParent=safeParentUrl(parentUrl);
        var state=new HuddleState(tenantId,safeType,safeKey,clean(subjectLabel,300,"Team workspace"),safeParent,snapshot,creator,unique.values());
        huddles.put(state.id,state);return view(state);
    }

    public HuddleView message(UUID tenantId,UUID huddleId,UUID senderId,String body){
        HuddleState state=required(tenantId,huddleId);
        synchronized(state){
            requireActiveParticipant(state,senderId);if(!state.joinedParticipantIds.contains(senderId))throw new IllegalArgumentException("Join this huddle before sending a message.");String clean=clean(body,MAX_MESSAGE_LENGTH,null);
            if(clean==null||clean.isBlank())throw new IllegalArgumentException("Write a message first.");
            if(state.messages.size()>=MAX_MESSAGES)throw new IllegalArgumentException("This huddle reached its live-message limit. Save it and continue in collaboration.");
            Participant sender=state.participants.get(senderId);
            state.messages.add(new LiveMessage(UUID.randomUUID(),sender.id(),sender.name(),sender.email(),clean,Instant.now()));
            state.expiresAt=Instant.now().plus(IDLE_TTL);return view(state);
        }
    }

    public HuddleView join(UUID tenantId,UUID huddleId,UUID participantId){
        HuddleState state=required(tenantId,huddleId);
        synchronized(state){requireActiveParticipant(state,participantId);state.joinedParticipantIds.add(participantId);state.expiresAt=Instant.now().plus(IDLE_TTL);return view(state);}
    }

    public HuddleView leave(UUID tenantId,UUID huddleId,UUID participantId){
        HuddleState state=required(tenantId,huddleId);
        synchronized(state){
            requireActiveParticipant(state,participantId);state.participants.remove(participantId);state.joinedParticipantIds.remove(participantId);
            if(state.participants.size()<2){state.endedAt=Instant.now();state.expiresAt=state.endedAt;}
            return view(state);
        }
    }

    public HuddleView end(UUID tenantId,UUID huddleId,UUID participantId){
        HuddleState state=required(tenantId,huddleId);
        synchronized(state){requireActiveParticipant(state,participantId);state.endedAt=Instant.now();state.expiresAt=state.endedAt;return view(state);}
    }

    public SaveResult save(UUID tenantId,UUID huddleId,UUID participantId,boolean closeConversation){
        HuddleState state=required(tenantId,huddleId);
        synchronized(state){
            if(!state.participants.containsKey(participantId))throw new IllegalArgumentException("This huddle is not available to your user.");
            if(state.savedReviewId!=null)return new SaveResult(view(state),state.savedReviewId);
            if(state.saving)throw new IllegalArgumentException("This huddle is already being saved.");
            state.saving=true;
            try{
                var transcript=state.messages.stream().map(message->new CollaborationRepository.HuddleTranscriptMessage(
                    message.senderId(),message.senderName(),message.senderEmail(),message.body(),message.createdAt())).toList();
                var creator=new CollaborationRepository.Member(state.startedBy.id(),state.startedBy.name(),state.startedBy.email(),null);
                var posted=collaboration.saveHuddle(tenantId,state.subjectType,state.subjectKey,state.subjectLabel,
                    state.contextSnapshot,state.parentUrl,creator,transcript,closeConversation);
                state.savedReviewId=posted.reviewId();state.endedAt=Instant.now();state.expiresAt=state.endedAt;
                var notify=new java.util.LinkedHashSet<>(state.participants.keySet());notify.remove(participantId);
                try{
                    if(collaboration.queueNotifications(tenantId,posted.reviewId(),posted.messageId(),notify,closeConversation?"COMPLETED":"MENTION")>0)
                        notifications.deliverAsync(tenantId);
                }catch(RuntimeException notificationFailure){log.warn("Saved quick huddle {} but could not queue its email notification: {}",state.id,notificationFailure.getMessage());}
                return new SaveResult(view(state),posted.reviewId());
            }finally{state.saving=false;}
        }
    }

    public HuddleView find(UUID tenantId,UUID huddleId,UUID participantId){
        HuddleState state=huddles.get(huddleId);if(state==null||!state.tenantId.equals(tenantId))return null;
        synchronized(state){return state.participants.containsKey(participantId)?view(state):null;}
    }

    public List<HuddleView> forParticipant(UUID tenantId,UUID participantId){
        return huddles.values().stream().map(state->{synchronized(state){return state.tenantId.equals(tenantId)
            &&state.endedAt==null&&state.participants.containsKey(participantId)?view(state):null;}})
            .filter(java.util.Objects::nonNull).sorted((left,right)->right.createdAt().compareTo(left.createdAt())).toList();
    }

    public List<ExpiredHuddle> expire(Instant now){
        var expired=new ArrayList<ExpiredHuddle>();
        huddles.forEach((id,state)->{synchronized(state){
            if(state.endedAt==null&&!state.expiresAt.isAfter(now)){state.endedAt=now;expired.add(new ExpiredHuddle(state.tenantId,view(state)));}
            else if(state.endedAt!=null&&state.endedAt.plus(ENDED_RETENTION).isBefore(now))huddles.remove(id,state);
        }});return expired;
    }

    private HuddleState required(UUID tenantId,UUID huddleId){
        HuddleState state=huddles.get(huddleId);if(state==null||!state.tenantId.equals(tenantId))throw new IllegalArgumentException("This huddle is no longer available.");return state;
    }
    private static void requireActiveParticipant(HuddleState state,UUID participantId){
        if(!state.participants.containsKey(participantId))throw new IllegalArgumentException("This huddle is not available to your user.");
        if(state.endedAt!=null)throw new IllegalArgumentException("This huddle has ended.");
    }
    private HuddleView view(HuddleState state){
        String status=state.savedReviewId!=null?"SAVED":state.endedAt!=null?"ENDED":"ACTIVE";
        return new HuddleView(state.id,state.subjectType,state.subjectKey,state.subjectLabel,state.parentUrl,state.contextSnapshot,
            state.startedBy,List.copyOf(state.participants.values()),Set.copyOf(state.joinedParticipantIds),List.copyOf(state.messages),state.createdAt,state.expiresAt,status,state.savedReviewId);
    }
    private String validSnapshot(String value){
        if(value==null||value.isBlank())return "{}";if(value.length()>16_000)throw new IllegalArgumentException("The record snapshot is too large.");
        try{var node=json.readTree(value);if(node==null||!node.isObject())throw new IllegalArgumentException("The record snapshot is invalid.");return json.writeValueAsString(node);}
        catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("The record snapshot is invalid.");}
    }
    private static String safeParentUrl(String value){return value!=null&&value.startsWith("/app")&&!value.startsWith("//")?value:null;}
    private static String clean(String value,int max,String fallback){String result=value==null?fallback:value.trim();if(result==null)return null;if(result.isBlank())result=fallback;if(result!=null&&result.length()>max)throw new IllegalArgumentException("Keep the huddle details concise.");return result;}
}
