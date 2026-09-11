package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.AccountSelectionController;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class HuddleWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log=LoggerFactory.getLogger(HuddleWebSocketHandler.class);
    private static final Set<String> CHAT_ROLES=Set.of("ROLE_PLATFORM_ADMIN","ROLE_OWNER","ROLE_ADMIN","ROLE_OPERATOR");
    private record ClientMessage(String type,UUID huddleId,List<UUID> participantIds,String subjectType,String subjectKey,
            String subjectLabel,String parentUrl,String contextSnapshot,String body,Boolean closeConversation) {}
    private record Client(UUID tenantId,HuddleService.Participant person,boolean canHuddle,WebSocketSession session) {}

    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final HuddleService huddles;
    private final CollaborationRepository collaboration;
    private final ObjectMapper json;

    public HuddleWebSocketHandler(HuddleService huddles,CollaborationRepository collaboration,ObjectMapper json){
        this.huddles=huddles;this.collaboration=collaboration;this.json=json;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{
        UUID tenantId=tenant(session);String email=session.getPrincipal()==null?null:session.getPrincipal().getName();
        if(tenantId==null||email==null){session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Choose an account before starting a huddle."));return;}
        var member=collaboration.currentMember(tenantId,email);
        if(member==null){session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Your account is not available for huddles."));return;}
        boolean canHuddle=session.getPrincipal() instanceof Authentication authentication&&authentication.getAuthorities().stream()
            .anyMatch(authority->CHAT_ROLES.contains(authority.getAuthority()));
        var person=new HuddleService.Participant(member.id(),member.name(),member.email());
        var client=new Client(tenantId,person,canHuddle,session);clients.put(session.getId(),client);
        send(client,Map.of("type","WELCOME","self",person,"canHuddle",canHuddle,"online",online(tenantId),
            "huddles",canHuddle?huddles.forParticipant(tenantId,person.id()):List.of()));
        broadcastPresence(tenantId);
    }

    @Override protected void handleTextMessage(WebSocketSession session,TextMessage text){
        Client client=clients.get(session.getId());if(client==null)return;
        try{
            ClientMessage message=json.readValue(text.getPayload(),ClientMessage.class);String type=message.type()==null?"":message.type().toUpperCase(Locale.ROOT);
            if("PING".equals(type)){send(client,Map.of("type","PONG","at",Instant.now()));return;}
            if(!client.canHuddle())throw new IllegalArgumentException("Your role can view collaboration but cannot start a live huddle.");
            switch(type){
                case "CREATE"->create(client,message);
                case "JOIN"->broadcast(client.tenantId(),"HUDDLE_UPDATED",huddles.join(client.tenantId(),message.huddleId(),client.person().id()));
                case "MESSAGE"->broadcast(client.tenantId(),"HUDDLE_UPDATED",huddles.message(client.tenantId(),message.huddleId(),client.person().id(),message.body()));
                case "LEAVE"->leave(client,message.huddleId());
                case "END"->broadcast(client.tenantId(),"HUDDLE_ENDED",huddles.end(client.tenantId(),message.huddleId(),client.person().id()));
                case "SAVE"->{var saved=huddles.save(client.tenantId(),message.huddleId(),client.person().id(),Boolean.TRUE.equals(message.closeConversation()));
                    broadcast(client.tenantId(),"HUDDLE_SAVED",saved.huddle(),Map.of("reviewId",saved.reviewId(),"closeConversation",Boolean.TRUE.equals(message.closeConversation())));}
                default->throw new IllegalArgumentException("That huddle action is not supported.");
            }
        }catch(IllegalArgumentException ex){sendError(client,ex.getMessage());}
        catch(Exception ex){log.warn("Quick huddle message failed for {}: {}",client.person().email(),ex.getMessage());sendError(client,"The live huddle could not complete that action.");}
    }

    private void create(Client client,ClientMessage request){
        List<UUID> requested=request.participantIds()==null?List.of():request.participantIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID,HuddleService.Participant> available=new LinkedHashMap<>();online(client.tenantId()).forEach(person->available.put(person.id(),person));
        var invited=new ArrayList<HuddleService.Participant>();
        for(UUID id:requested){var person=available.get(id);if(person==null)throw new IllegalArgumentException("One selected teammate is no longer online.");invited.add(person);}
        var huddle=huddles.create(client.tenantId(),client.person(),request.subjectType(),request.subjectKey(),request.subjectLabel(),
            request.parentUrl(),request.contextSnapshot(),invited);
        int delivered=broadcast(client.tenantId(),"HUDDLE_STARTED",huddle);
        log.info("Quick huddle [{}] started — {} participant(s), {} active browser session(s) notified.",shortId(huddle.id()),huddle.participants().size(),delivered);
    }

    private void leave(Client client,UUID huddleId){
        var before=huddles.find(client.tenantId(),huddleId,client.person().id());if(before==null)throw new IllegalArgumentException("This huddle is no longer available.");
        var recipients=before.participants().stream().map(HuddleService.Participant::id).toList();var after=huddles.leave(client.tenantId(),huddleId,client.person().id());
        broadcastTo(recipients,client.tenantId(),after.status().equals("ENDED")?"HUDDLE_ENDED":"HUDDLE_UPDATED",after,Map.of());
    }

    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){
        Client removed=clients.remove(session.getId());if(removed!=null)broadcastPresence(removed.tenantId());
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable exception)throws Exception{
        log.debug("Quick huddle connection ended: {}",exception.getMessage());if(session.isOpen())session.close(CloseStatus.SERVER_ERROR);
    }

    @Scheduled(fixedDelay=60_000)
    public void expireIdleHuddles(){huddles.expire(Instant.now()).forEach(expired->broadcast(expired.tenantId(),"HUDDLE_ENDED",expired.huddle(),Map.of("reason","This huddle expired after 45 minutes without activity.")));}

    private List<HuddleService.Participant> online(UUID tenantId){
        Map<UUID,HuddleService.Participant> unique=new LinkedHashMap<>();clients.values().stream()
            .filter(client->client.tenantId().equals(tenantId)&&client.canHuddle()&&client.session().isOpen())
            .sorted((left,right)->left.person().name().compareToIgnoreCase(right.person().name()))
            .forEach(client->unique.putIfAbsent(client.person().id(),client.person()));return List.copyOf(unique.values());
    }
    private void broadcastPresence(UUID tenantId){
        var event=Map.of("type","PRESENCE","online",online(tenantId));clients.values().stream().filter(client->client.tenantId().equals(tenantId)).forEach(client->send(client,event));
    }
    private int broadcast(UUID tenantId,String type,HuddleService.HuddleView huddle){return broadcast(tenantId,type,huddle,Map.of());}
    private int broadcast(UUID tenantId,String type,HuddleService.HuddleView huddle,Map<String,Object> extra){
        return broadcastTo(huddle.participants().stream().map(HuddleService.Participant::id).toList(),tenantId,type,huddle,extra);
    }
    private int broadcastTo(List<UUID> participantIds,UUID tenantId,String type,HuddleService.HuddleView huddle,Map<String,Object> extra){
        Map<String,Object> event=new LinkedHashMap<>();event.put("type",type);event.put("huddle",huddle);event.putAll(extra);
        var recipients=clients.values().stream().filter(client->client.tenantId().equals(tenantId)&&participantIds.contains(client.person().id())&&client.session().isOpen()).toList();
        recipients.forEach(client->send(client,event));return recipients.size();
    }
    private void sendError(Client client,String message){send(client,Map.of("type","ERROR","message",message==null?"The huddle action could not be completed.":message));}
    private void send(Client client,Object payload){
        if(!client.session().isOpen())return;try{String body=json.writeValueAsString(payload);synchronized(client.session()){client.session().sendMessage(new TextMessage(body));}}
        catch(IOException ex){log.debug("Quick huddle delivery ended for {}",client.person().email());}
    }
    private static UUID tenant(WebSocketSession session){
        Object value=session.getAttributes().get(AccountSelectionController.TENANT_ID);if(value instanceof UUID uuid)return uuid;
        try{return value==null?null:UUID.fromString(value.toString());}catch(IllegalArgumentException ex){return null;}
    }
    private static String shortId(UUID id){return id==null?"unknown":id.toString().substring(0,8);}
}
