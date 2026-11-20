package com.nextaicommerce.platform.collaboration;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable room snapshots and immutable file bytes; presence remains process-local. */
@Repository
public class HuddlePersistenceRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final CollaborationTaskQueueRepository queue;
    public record File(UUID id,String name,String type,byte[] bytes){}
    public HuddlePersistenceRepository(JdbcTemplate jdbc,ObjectMapper json,CollaborationTaskQueueRepository queue){this.jdbc=jdbc;this.json=json;this.queue=queue;}
    private void context(UUID account){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,account.toString());}
    @Transactional
    public void save(UUID account,HuddleService.HuddleView room){
        context(account);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM collaboration_huddle_rooms WHERE tenant_id=? AND id=?)",Boolean.class,account,room.id()))){
            jdbc.queryForObject("SELECT id FROM tenants WHERE id=? FOR UPDATE",UUID.class,account);
            if(jdbc.queryForObject("SELECT count(*) FROM collaboration_huddle_rooms WHERE tenant_id=? AND status='ACTIVE'",Integer.class,account)>=250)throw new IllegalArgumentException("End an active chat before starting another.");
        }
        jdbc.update("INSERT INTO collaboration_huddle_rooms(id,tenant_id,creator_id,payload,status,created_at) VALUES (?,?,?,CAST(? AS jsonb),?,?) ON CONFLICT(id) DO UPDATE SET payload=EXCLUDED.payload,status=EXCLUDED.status,updated_at=now() WHERE collaboration_huddle_rooms.tenant_id=EXCLUDED.tenant_id",
            room.id(),account,room.startedBy().id(),json.writeValueAsString(room),room.status(),java.sql.Timestamp.from(room.createdAt()));
    }
    @Transactional(readOnly=true)
    public HuddleService.HuddleView load(UUID account,UUID id){context(account);var rows=jdbc.queryForList("SELECT payload::text FROM collaboration_huddle_rooms WHERE tenant_id=? AND id=?",String.class,account,id);return rows.isEmpty()?null:json.readValue(rows.getFirst(),HuddleService.HuddleView.class);}
    @Transactional(readOnly=true)
    public List<HuddleService.HuddleView> active(UUID account,UUID user){
        context(account);return jdbc.queryForList("SELECT payload::text FROM collaboration_huddle_rooms WHERE tenant_id=? AND status='ACTIVE' AND EXISTS(SELECT 1 FROM jsonb_array_elements(payload->'participants') p WHERE p->>'id'=?) ORDER BY created_at DESC LIMIT 250",String.class,account,user.toString()).stream().map(s->json.readValue(s,HuddleService.HuddleView.class)).toList();
    }
    @Transactional
    public UUID file(UUID account,UUID room,String name,String type,byte[] bytes){
        context(account);jdbc.queryForObject("SELECT id FROM collaboration_huddle_rooms WHERE tenant_id=? AND id=? AND status='ACTIVE' FOR UPDATE",UUID.class,account,room);
        Long used=jdbc.queryForObject("SELECT coalesce(sum(octet_length(content)),0) FROM collaboration_huddle_files WHERE tenant_id=? AND room_id=?",Long.class,account,room);
        if(bytes.length>2*1024*1024||used+bytes.length>8*1024*1024)throw new IllegalArgumentException("Keep files under 2 MB each and 8 MB per chat.");
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO collaboration_huddle_files(id,tenant_id,room_id,file_name,content_type,content) VALUES (?,?,?,?,?,?)",id,account,room,name,type,bytes);return id;
    }
    @Transactional(readOnly=true)
    public File file(UUID account,UUID room,UUID id){context(account);var rows=jdbc.query("SELECT file_name,content_type,content FROM collaboration_huddle_files WHERE tenant_id=? AND room_id=? AND id=?",(rs,n)->new File(id,rs.getString(1),rs.getString(2),rs.getBytes(3)),account,room,id);return rows.isEmpty()?null:rows.getFirst();}
    @Transactional
    public UUID convert(UUID account,HuddleService.HuddleView room,UUID actor){
        return convert(account,room,actor,"Saved chat",null,"America/Los_Angeles");
    }
    @Transactional
    public UUID convert(UUID account,HuddleService.HuddleView room,UUID actor,String title,java.time.LocalDate dueDate,String zone){
        return convert(account,room,actor,title,dueDate,zone,null);
    }
    @Transactional
    public UUID convert(UUID account,HuddleService.HuddleView room,UUID actor,String title,java.time.LocalDate dueDate,String zone,Set<UUID> assigneeIds){
        if(title==null||title.isBlank()||title.length()>240)throw new IllegalArgumentException("Enter a task title of up to 240 characters.");
        java.time.ZoneId.of(zone);
        context(account);
        var saved=jdbc.query("SELECT saved_task_id FROM collaboration_huddle_rooms WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),account,room.id());
        if(saved.isEmpty())throw new IllegalArgumentException("This chat is unavailable.");if(saved.getFirst()!=null)return saved.getFirst();
        var person=room.participants().stream().filter(p->p.id().equals(actor)).findFirst().orElseThrow(()->new IllegalArgumentException("Join this chat before saving it."));
        // Discover only this actor's explicit parents, then select the account's actual association.
        jdbc.queryForObject("SELECT set_config('app.user_email',?,true)",String.class,person.email());
        var parents=jdbc.queryForList("SELECT m.parent_id FROM parent_tenant_memberships m WHERE m.user_id=? AND m.active",UUID.class,actor);
        UUID parent=null;
        for(UUID id:parents){jdbc.queryForObject("SELECT set_config('app.parent_id',?,true)",String.class,id.toString());if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parent_tenant_accounts WHERE parent_id=? AND account_id=?)",Boolean.class,id,account))){parent=id;break;}}
        if(parent==null)throw new IllegalArgumentException("Set up this account's parent tenant before saving a task. Your chat remains saved.");
        UUID selected=parent;
        Set<UUID> audience=new LinkedHashSet<>();room.participants().forEach(p->audience.add(p.id()));
        if(!room.startedBy().id().equals(actor))throw new IllegalArgumentException("Only the chat creator can save and end it for everyone.");
        // Validate the whole audience before inserting any shared task content.
        for(UUID member:audience)if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parent_tenant_memberships WHERE parent_id=? AND user_id=? AND active)",Boolean.class,selected,member)))throw new IllegalArgumentException("Invite the chat participants to this parent tenant first. Your chat remains saved.");
        Set<UUID> assigned=assigneeIds==null?Set.of(actor):Set.copyOf(assigneeIds);
        if(!audience.containsAll(assigned))throw new IllegalArgumentException("Choose assignees from this collaboration's participants.");
        var task=queue.create(selected,account,null,title,"",person.email(),assigned);
        jdbc.update("UPDATE collaboration_tasks SET due_date=?,due_time_zone=? WHERE parent_id=? AND id=?",dueDate,zone,selected,task.reviewId());
        for(UUID member:audience)jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,added_by) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",selected,task.reviewId(),member,actor);
        for(var message:room.messages())jdbc.update("INSERT INTO collaboration_task_messages(id,parent_id,task_id,author_id,body,created_at) VALUES (?,?,?,?,?,?)",message.id(),selected,task.reviewId(),message.senderId(),message.body().replaceAll("/app/huddles/"+room.id()+"/files/([a-f0-9-]{36})","/app/collaboration/board/tasks/"+task.reviewId()+"/files/$1?parent="+selected),java.sql.Timestamp.from(message.createdAt()));
        UUID attachmentMessage=UUID.randomUUID();
        int files=jdbc.queryForObject("SELECT count(*) FROM collaboration_huddle_files WHERE tenant_id=? AND room_id=?",Integer.class,account,room.id());
        if(files>0){
            jdbc.update("INSERT INTO collaboration_task_messages(id,parent_id,task_id,author_id,body) VALUES (?,?,?,?,'Files retained from the chat.')",attachmentMessage,selected,task.reviewId(),actor);
            jdbc.update("INSERT INTO collaboration_task_files(id,parent_id,task_id,message_id,file_name,content_type,content,created_at) SELECT id,?,?,?,file_name,content_type,content,created_at FROM collaboration_huddle_files WHERE tenant_id=? AND room_id=?",selected,task.reviewId(),attachmentMessage,account,room.id());
        }
        // Keep only the link identity. Do not copy snapshots or protected record labels into shared metadata.
        jdbc.update("UPDATE collaboration_tasks SET subject_type=?,subject_key=? WHERE parent_id=? AND id=?",room.subjectType(),room.subjectKey(),selected,task.reviewId());
        jdbc.update("UPDATE collaboration_huddle_rooms SET saved_parent_id=?,saved_task_id=?,status='SAVED',payload=jsonb_set(jsonb_set(payload,'{status}','\"SAVED\"'::jsonb),'{savedReviewId}',to_jsonb(?::text)),updated_at=now() WHERE tenant_id=? AND id=?",selected,task.reviewId(),task.reviewId().toString(),account,room.id());
        return task.reviewId();
    }
}
