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
    private static final Set<String> CHAT_ROLES=Set.of("ROLE_PLATFORM_ADMIN","ROLE_OWNER","ROLE_ADMIN","ROLE_OPERATOR","ROLE_VIEWER");
    private record ClientMessage(String type,UUID huddleId,List<UUID> participantIds,String subjectType,String subjectKey,
            String subjectLabel,String parentUrl,String contextSnapshot,String body,Boolean closeConversation) {}
    private record Client(UUID tenantId,HuddleService.Participant person,boolean canHuddle,WebSocketSession session) {}

    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> access=new ConcurrentHashMap<>();
    private final Map<String,Instant> heartbeat=new ConcurrentHashMap<>();
    private final Map<String,List<HuddleService.Participant>> lastPresence=new ConcurrentHashMap<>();
    private final HuddleService huddles;
    private final CollaborationRepository collaboration;
    private final ObjectMapper json;

    public HuddleWebSocketHandler(HuddleService huddles,CollaborationRepository collaboration,ObjectMapper json){
        this.huddles=huddles;this.collaboration=collaboration;this.json=json;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{
        UUID tenantId=tenant(session);String email=session.getPrincipal()==null?null:session.getPrincipal().getName();
        if(tenantId==null||email==null){session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Choose an account before starting a huddle."));return;}
        var permitted=collaboration.huddleTenants(email);
        if(!permitted.contains(tenantId)){session.close(CloseStatus.POLICY_VIOLATION);return;}
        var member=collaboration.currentMember(tenantId,email);
        if(member==null){session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Your account is not available for huddles."));return;}
        boolean canHuddle=session.getPrincipal() instanceof Authentication authentication&&authentication.getAuthorities().stream()
            .anyMatch(authority->CHAT_ROLES.contains(authority.getAuthority()));
        var person=new HuddleService.Participant(member.id(),member.name(),member.email());
        var client=new Client(tenantId,person,canHuddle,session);clients.put(session.getId(),client);access.put(session.getId(),permitted);heartbeat.put(session.getId(),Instant.now());
        send(client,Map.of("type","WELCOME","self",person,"canHuddle",canHuddle,"online",onlineFor(client),
            "huddles",canHuddle?permitted.stream().flatMap(id->huddles.forParticipant(id,person.id()).stream()).toList():List.of()));
        broadcastAllPresence();
    }

    @Override protected void handleTextMessage(WebSocketSession session,TextMessage text){
        Client client=clients.get(session.getId());if(client==null)return;
        try{
            ClientMessage message=json.readValue(text.getPayload(),ClientMessage.class);String type=message.type()==null?"":message.type().toUpperCase(Locale.ROOT);
            heartbeat.put(session.getId(),Instant.now());
            access.put(session.getId(),collaboration.huddleTenants(client.person().email()));
            if(!access.get(session.getId()).contains(client.tenantId())){session.close(CloseStatus.POLICY_VIOLATION);return;}
            if("PING".equals(type)){send(client,Map.of("type","PONG","at",Instant.now()));broadcastAllPresence();return;}
            UUID scope=message.huddleId()==null?client.tenantId():huddles.tenantForParticipant(message.huddleId(),client.person().id());
            if(scope==null||!access.get(session.getId()).contains(scope))throw new IllegalArgumentException("This huddle is not available to your account.");
            Client actor=new Client(scope,client.person(),client.canHuddle(),client.session());
            if(!client.canHuddle())throw new IllegalArgumentException("Your role can view collaboration but cannot start a live huddle.");
            switch(type){
                case "PEOPLE"->send(client,Map.of("type","ROOM_PEOPLE","huddleId",message.huddleId(),"online",online(scope)));
                case "CREATE"->create(actor,message);
                case "INVITE"->{var people=availablePeople(actor,message);broadcast(scope,"HUDDLE_UPDATED",huddles.invite(scope,message.huddleId(),actor.person().id(),people));}
                case "JOIN"->broadcast(scope,"HUDDLE_UPDATED",huddles.join(scope,message.huddleId(),client.person().id()));
                case "MESSAGE"->broadcast(scope,"HUDDLE_UPDATED",huddles.message(scope,message.huddleId(),client.person().id(),message.body()));
                case "LEAVE"->leave(actor,message.huddleId());
                case "END"->broadcast(scope,"HUDDLE_ENDED",huddles.end(scope,message.huddleId(),client.person().id()));
                case "SAVE"->{var saved=huddles.save(scope,message.huddleId(),client.person().id(),Boolean.TRUE.equals(message.closeConversation()));
                    broadcast(scope,"HUDDLE_SAVED",saved.huddle(),Map.of("reviewId",saved.reviewId(),"closeConversation",Boolean.TRUE.equals(message.closeConversation())));}
                default->throw new IllegalArgumentException("That huddle action is not supported.");
            }
        }catch(IllegalArgumentException ex){sendError(client,ex.getMessage());}
        catch(Exception ex){log.warn("Quick huddle message failed for {}: {}",client.person().email(),ex.getMessage());sendError(client,"The live huddle could not complete that action.");}
    }

    private List<HuddleService.Participant> availablePeople(Client client,ClientMessage request){
        List<UUID> requested=request.participantIds()==null?List.of():request.participantIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID,HuddleService.Participant> available=new LinkedHashMap<>();online(client.tenantId()).forEach(person->available.put(person.id(),person));
        var invited=new ArrayList<HuddleService.Participant>();
        for(UUID id:requested){var person=available.get(id);if(person==null||!collaboration.huddleTenants(person.email()).contains(client.tenantId()))throw new IllegalArgumentException("One selected teammate is no longer online.");invited.add(person);}
        return invited;
    }
    private void create(Client client,ClientMessage request){
        if("PLATFORM".equals(request.subjectType())&&request.subjectKey()!=null&&request.subjectKey().startsWith("DIRECT:")){
            var common=new java.util.TreeSet<UUID>(collaboration.huddleTenants(client.person().email()));
            for(UUID id:request.participantIds()==null?List.<UUID>of():request.participantIds()){
                var person=onlineFor(client).stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("This teammate is no longer online."));
                common.retainAll(collaboration.huddleTenants(person.email()));
            }
            if(common.isEmpty())throw new IllegalArgumentException("Choose teammates with access to a shared account.");
            UUID shared=common.contains(client.tenantId())?client.tenantId():common.first();
            client=new Client(shared,client.person(),client.canHuddle(),client.session());
        }
        var invited=availablePeople(client,request);
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
        Client removed=clients.remove(session.getId());access.remove(session.getId());heartbeat.remove(session.getId());lastPresence.remove(session.getId());if(removed!=null)broadcastAllPresence();
    }
    @org.springframework.context.event.EventListener
    public void sessionDestroyed(org.springframework.security.web.session.HttpSessionDestroyedEvent event){
        clients.values().stream().filter(c->event.getSession().getId().equals(c.session().getAttributes().get(
            org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME))).toList()
            .forEach(c->{try{c.session().close(CloseStatus.NORMAL);}catch(IOException ignored){}afterConnectionClosed(c.session(),CloseStatus.NORMAL);});
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable exception)throws Exception{
        log.debug("Quick huddle connection ended: {}",exception.getMessage());if(session.isOpen())session.close(CloseStatus.SERVER_ERROR);
    }

    @Scheduled(fixedDelay=15_000)
    public void expireIdleHuddles(){
        clients.values().stream().filter(c->!fresh(c)).toList().forEach(c->{try{c.session().close(CloseStatus.SESSION_NOT_RELIABLE);}catch(IOException ignored){}afterConnectionClosed(c.session(),CloseStatus.SESSION_NOT_RELIABLE);});
        huddles.expire(Instant.now()).forEach(expired->broadcast(expired.tenantId(),"HUDDLE_ENDED",expired.huddle(),Map.of("reason","This huddle expired after 45 minutes without activity.")));}

    private List<HuddleService.Participant> online(UUID tenantId){
        Map<UUID,HuddleService.Participant> unique=new LinkedHashMap<>();clients.values().stream()
            .filter(client->access.getOrDefault(client.session().getId(),Set.of()).contains(tenantId)&&client.canHuddle()&&fresh(client))
            .sorted((left,right)->left.person().name().compareToIgnoreCase(right.person().name()))
            .forEach(client->unique.putIfAbsent(client.person().id(),client.person()));return List.copyOf(unique.values());
    }
    private boolean fresh(Client client){return client.session().isOpen()&&heartbeat.getOrDefault(client.session().getId(),Instant.MIN).plusSeconds(90).isAfter(Instant.now());}
    private List<HuddleService.Participant> onlineFor(Client viewer){
        var permitted=access.getOrDefault(viewer.session().getId(),Set.of());
        Map<UUID,HuddleService.Participant> unique=new LinkedHashMap<>();
        clients.values().stream().filter(c->fresh(c)&&!java.util.Collections.disjoint(permitted,access.getOrDefault(c.session().getId(),Set.of())))
            .sorted((a,b)->a.person().name().compareToIgnoreCase(b.person().name())).forEach(c->unique.putIfAbsent(c.person().id(),c.person()));
        return List.copyOf(unique.values());
    }
    private void broadcastAllPresence(){clients.values().stream().map(Client::tenantId).distinct().forEach(this::broadcastPresence);}
    private void broadcastPresence(UUID tenantId){
        clients.values().stream().filter(client->client.tenantId().equals(tenantId)).forEach(client->{
            var online=onlineFor(client);
            if(!online.equals(lastPresence.put(client.session().getId(),online)))send(client,Map.of("type","PRESENCE","online",online));
        });
    }
    public void publishFile(UUID tenantId,HuddleService.HuddleView huddle){broadcast(tenantId,"HUDDLE_UPDATED",huddle);}
    private int broadcast(UUID tenantId,String type,HuddleService.HuddleView huddle){return broadcast(tenantId,type,huddle,Map.of());}
    private int broadcast(UUID tenantId,String type,HuddleService.HuddleView huddle,Map<String,Object> extra){
        return broadcastTo(huddle.participants().stream().map(HuddleService.Participant::id).toList(),tenantId,type,huddle,extra);
    }
    private int broadcastTo(List<UUID> participantIds,UUID tenantId,String type,HuddleService.HuddleView huddle,Map<String,Object> extra){
        Map<String,Object> event=new LinkedHashMap<>();event.put("type",type);event.put("tenantId",tenantId);event.put("huddle",huddle);event.putAll(extra);
        // Validate each user once per delivery, even with several tabs open.
        var permissions=new java.util.HashMap<String,Set<UUID>>();
        var recipients=clients.values().stream().filter(client->participantIds.contains(client.person().id())&&fresh(client)
            &&permissions.computeIfAbsent(client.person().email(),collaboration::huddleTenants).contains(tenantId)).toList();
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
