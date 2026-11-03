package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.invitation.PlatformMailer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.*;

/** Uses the platform mailer only after commit; local mail-disabled previews never send. */
@Component
public class CollaborationTaskNotificationListener {
    private final JdbcTemplate jdbc;private final CollaborationTaskQueueRepository queue;private final PlatformMailer mailer;
    private final TransactionTemplate transaction;private final boolean enabled;private final String publicUrl;
    public CollaborationTaskNotificationListener(JdbcTemplate jdbc,CollaborationTaskQueueRepository queue,PlatformMailer mailer,
        PlatformTransactionManager manager,@Value("${app.mail.enabled:false}") boolean enabled,@Value("${app.public-url:http://localhost:8080}") String publicUrl){
        this.jdbc=jdbc;this.queue=queue;this.mailer=mailer;this.transaction=new TransactionTemplate(manager);this.enabled=enabled;this.publicUrl=publicUrl.replaceAll("/+$","");
    }
    @Async @TransactionalEventListener
    public void notify(CollaborationTaskQueueRepository.TaskNotification event){
        if(!enabled)return;
        try{
            var recipients=transaction.execute(s->{
                UUID actor=queue.actor(event.parent(),event.actor());queue.visible(event.parent(),event.task(),actor,0);
                if("ASSIGNED".equals(event.kind())&&jdbc.queryForObject("SELECT count(*) FROM collaboration_task_participants WHERE parent_id=? AND task_id=? AND assigned",Integer.class,event.parent(),event.task())<2)return List.<CollaborationRepository.PendingMention>of();
                var result=new ArrayList<CollaborationRepository.PendingMention>();
                for(UUID person:event.people())result.addAll(jdbc.query("""
                    SELECT u.display_name,u.email,p.name,t.title FROM collaboration_task_participants a
                    JOIN parent_tenant_memberships m ON m.parent_id=a.parent_id AND m.user_id=a.user_id AND m.active
                    JOIN app_users u ON u.id=a.user_id AND u.status='ACTIVE'
                    JOIN parent_tenants p ON p.id=a.parent_id JOIN collaboration_tasks t ON t.parent_id=a.parent_id AND t.id=a.task_id
                    WHERE a.parent_id=? AND a.task_id=? AND a.user_id=? AND (?<>'ASSIGNED' OR a.assigned)
                    """,(rs,n)->new CollaborationRepository.PendingMention(UUID.randomUUID(),event.task(),rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),event.actor(),"COMPLETED".equals(event.kind())?"Task completed by "+event.actor()+". Open the conversation to review its history.":"Open the task to review the latest details.",event.kind()),event.parent(),event.task(),person,event.kind()));
                return result;
            });
            for(var person:recipients){
                try{mailer.sendHtml(person.recipientEmail(),CollaborationEmailContent.subject(person),CollaborationEmailContent.html(person,publicUrl+"/app/collaboration/board?parent="+event.parent()+"&task="+event.task()));}
                catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Task notification delivery failed for task {}; continuing remaining recipients",event.task());}
            }
        }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Task notification was not delivered for task {}",event.task());}
    }
}
