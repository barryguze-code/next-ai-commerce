package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class PersonalCollaborationActions {
    private final PersonalCollaborationQueue personal;
    private final CollaborationTaskQueueRepository tasks;
    private final CollaborationRepository reviews;
    private final CollaborationMentionService mentions;
    private final WorkspaceAccessRepository accounts;
    PersonalCollaborationActions(PersonalCollaborationQueue personal, CollaborationTaskQueueRepository tasks,
            CollaborationRepository reviews, CollaborationMentionService mentions, WorkspaceAccessRepository accounts) {
        this.personal=personal; this.tasks=tasks; this.reviews=reviews; this.mentions=mentions; this.accounts=accounts;
    }
    private PersonalCollaborationQueue.Card editable(String email,boolean staff,java.util.UUID id) {
        var card=personal.authorized(email,staff,new PersonalCollaborationQueue.Key("REVIEW",id));
        if(!accounts.canOperateAccount(card.account(),email))throw new AccessDeniedException("Editing is unavailable for this account.");
        return card;
    }
    @Transactional
    public CollaborationRepository.Review due(String email,boolean staff,java.util.UUID id,java.time.LocalDate date,String zone) {
        var card=editable(email,staff,id);
        return reviews.setDueDate(card.account(),id,email,date,zone);
    }
    @Transactional
    public CollaborationRepository.Review assign(String email,boolean staff,java.util.UUID id,java.util.Set<java.util.UUID> people) {
        var card=editable(email,staff,id);
        reviews.assign(card.account(),id,email,people,true);
        return reviews.review(card.account(),id,email);
    }

    @Transactional
    public void create(String email,boolean staff,java.util.UUID account,java.util.UUID store,String title,String body,
            java.util.Set<java.util.UUID> people,java.time.LocalDate date,String zone) {
        var option=accounts.listAccountOptions(email,staff).stream().filter(a->a.id().equals(account)).findFirst()
            .orElseThrow(()->new AccessDeniedException("Choose an accessible account."));
        if(!accounts.canOperateAccount(account,email))throw new AccessDeniedException("Task creation is unavailable for this account.");
        if(store!=null&&option.stores().stream().noneMatch(s->s.id().equals(store)))throw new AccessDeniedException("Choose an accessible store.");
        if(title==null||title.isBlank()||title.length()>240||body.length()>4000)throw new IllegalArgumentException("Enter a title up to 240 characters and details up to 4,000 characters.");
        java.time.ZoneId.of(zone);
        var posted=reviews.createStoreTask(account,store,title.trim(),body.trim(),email,people,date,zone);
        mentions.queue(account,posted,body,email);
    }

    @Transactional
    public void reply(String email,boolean staff,java.util.UUID id,String body,String type,String status,
            List<CollaborationRepository.AttachmentUpload> files) {
        var card=editable(email,staff,id);
        if(body.length()>4000)throw new IllegalArgumentException("Keep the message under 4,000 characters.");
        if(status!=null&&!status.equals("CLOSED"))throw new IllegalArgumentException("Choose a valid status.");
        if(body.isBlank()&&files.isEmpty()&&status==null)return;
        var posted=reviews.reply(card.account(),id,email,body.isBlank()?null:body.trim(),type,status,files);
        if("CLOSED".equals(status))mentions.queueCompletion(card.account(),posted,email);
        else if("TEAM_CHAT".equals(type))mentions.queueReply(card.account(),posted,body,email);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void change(String email,boolean staff,PersonalCollaborationQueue.Key key,boolean close) {
        var card=personal.authorized(email,staff,key);
        if(card.closed()==close) return;
        if(card.parent()!=null){
            tasks.actor(card.parent(),email);
            tasks.state(card.parent(),key.id(),email,close?"DONE":"OPEN",tasks.lock(card.parent()));
        } else {
            if(!accounts.canOperateAccount(card.account(),email)) throw new AccessDeniedException("Account editing access required.");
            if(close){
                var posted=reviews.reply(card.account(),key.id(),email,null,"TEAM_CHAT","CLOSED",List.of());
                mentions.queueCompletion(card.account(),posted,email);
            } else reviews.reopen(card.account(),key.id(),email);
        }
    }
}
