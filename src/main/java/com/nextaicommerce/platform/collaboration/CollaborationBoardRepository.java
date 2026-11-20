package com.nextaicommerce.platform.collaboration;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Bounded parent-owned board reads. A filtered page never defines queue ordering. */
@Repository
public class CollaborationBoardRepository {
    public record Card(UUID id,String title,String state,Integer position,UUID accountId,UUID storeId,
            LocalDate dueDate,String timeZone,Instant closedAt,String assignees,boolean restrictedContext) {}
    public record Page(List<Card> cards,boolean more,long revision,Instant asOf,Map<String,Long> groups) {
        public Page(List<Card> cards,boolean more,long revision,Instant asOf){this(cards,more,revision,asOf,Map.of());}
    }
    public record FileInfo(UUID id,String name,long size){}
    public record Note(UUID id,String author,String body,Instant createdAt,List<FileInfo> files) {}
    private final JdbcTemplate jdbc;
    private final CollaborationTaskQueueRepository queue;
    public CollaborationBoardRepository(JdbcTemplate jdbc,CollaborationTaskQueueRepository queue){this.jdbc=jdbc;this.queue=queue;}
    private static final String AUDIENCE=" EXISTS(SELECT 1 FROM collaboration_task_participants audience WHERE audience.parent_id=t.parent_id AND audience.task_id=t.id AND audience.user_id=?) ";
    @Transactional(readOnly=true)
    public String taskTitle(UUID parent, UUID task, String email) {
        UUID actor = queue.actor(parent, email);
        var titles = jdbc.queryForList("SELECT t.title FROM collaboration_tasks t WHERE t.parent_id=? AND t.id=? AND " + AUDIENCE,
                String.class, parent, task, actor);
        if (titles.isEmpty()) throw new org.springframework.security.access.AccessDeniedException("Task unavailable.");
        return titles.getFirst();
    }
    @Transactional(readOnly=true)
    public Page page(UUID parent,String email,String state,boolean mine,UUID account,UUID store,String query,int size,
            int afterPosition,Instant beforeClosed,UUID beforeId,Instant asOf,String doneGroup,String zone){
        UUID actor=queue.actor(parent,email);
        if(!Set.of("OPEN","IN_PROGRESS","DONE").contains(state))throw new IllegalArgumentException("Choose a task column.");
        size=Math.max(1,Math.min(50,size));Instant snapshot=asOf==null?Instant.now():asOf;
        if(snapshot.isAfter(Instant.now().plusSeconds(5)))throw new IllegalArgumentException("Refresh the board.");
        StringBuilder where=new StringBuilder("t.parent_id=? AND "+AUDIENCE+" AND t.state=?");
        List<Object> args=new ArrayList<>(List.of(parent,actor,state));
        if(mine){where.append(" AND EXISTS(SELECT 1 FROM collaboration_task_participants a WHERE a.parent_id=t.parent_id AND a.task_id=t.id AND a.user_id=? AND a.assigned)");args.add(actor);}
        if(account!=null){where.append(" AND (t.account_id IS NULL OR t.account_id=?)");args.add(account);}
        if(store!=null){where.append(" AND (t.store_id IS NULL OR t.store_id=?)");args.add(store);}
        if(query!=null&&!query.isBlank()){where.append(" AND position(lower(?) in lower(t.title))>0");args.add(query.trim());}
        Map<String,Long> groups=new LinkedHashMap<>();
        if("DONE".equals(state)){
            where.append(" AND t.closed_at<?");args.add(snapshot);
            if(doneGroup==null && beforeClosed==null){
                var boundaries=new StringBuilder("CASE ");
                var parameters=new ArrayList<Object>();
                for(var window:CollaborationCompletionWindow.at(snapshot,ZoneId.of(zone))){
                    if(window.from()==null)continue;
                    boundaries.append("WHEN closed_at>=? THEN '").append(window.key()).append("' ");
                    parameters.add(java.sql.Timestamp.from(window.from()));
                }
                boundaries.append("ELSE 'OLDER' END");
                parameters.addAll(args);
                parameters.replaceAll(v->v instanceof Instant i?java.sql.Timestamp.from(i):v);
                jdbc.query("SELECT "+boundaries+",count(*) FROM (SELECT t.closed_at FROM collaboration_tasks t WHERE "+where+
                    " ORDER BY t.closed_at DESC,t.id DESC OFFSET 5) remaining GROUP BY 1",
                    (org.springframework.jdbc.core.RowCallbackHandler)rs->groups.put(rs.getString(1),rs.getLong(2)),parameters.toArray());
            }
            if(doneGroup!=null&&!doneGroup.isBlank()){
                var window=CollaborationCompletionWindow.at(snapshot,ZoneId.of(zone)).stream().filter(w->w.key().equals(doneGroup)).findFirst().orElse(null);
                if(window==null)return new Page(List.of(),false,revision(parent),snapshot);
                // Latest five are excluded across every older bucket, under the same audience and filters.
                String base=where.toString();List<Object> baseArgs=new ArrayList<>(args);
                where.append(" AND t.id NOT IN(SELECT t.id FROM collaboration_tasks t WHERE ").append(base).append(" ORDER BY t.closed_at DESC,t.id DESC LIMIT 5)");args.addAll(baseArgs);
                where.append(" AND t.closed_at<?");args.add(window.until());
                if(window.from()!=null){where.append(" AND t.closed_at>=?");args.add(window.from());}
            }
            if(beforeClosed!=null&&beforeId!=null){where.append(" AND (t.closed_at,t.id)<(?,?)");args.add(beforeClosed);args.add(beforeId);}
        }else{where.append(" AND t.queue_position>?");args.add(Math.max(0,afterPosition));}
        args.add(size+1);
        args.replaceAll(value->value instanceof Instant instant?java.sql.Timestamp.from(instant):value);
        var rows=jdbc.query("""
            SELECT t.id,t.title,t.state,t.queue_position,t.account_id,t.store_id,t.due_date,t.due_time_zone,t.closed_at,
              (SELECT string_agg(u.display_name,', ' ORDER BY u.display_name) FROM collaboration_task_participants p JOIN app_users u ON u.id=p.user_id
               WHERE p.parent_id=t.parent_id AND p.task_id=t.id AND p.assigned) assignees
            FROM collaboration_tasks t WHERE
            """+where+("DONE".equals(state)?" ORDER BY t.closed_at DESC,t.id DESC":" ORDER BY t.queue_position,t.id")+" LIMIT ?",
            (rs,n)->new Card(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),(Integer)rs.getObject(4),rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),rs.getObject(7,LocalDate.class),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant(),rs.getString(10),false),args.toArray());
        return new Page(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),rows.size()>size,revision(parent),snapshot,groups);
    }
    private long revision(UUID parent){var rows=jdbc.queryForList("SELECT revision FROM collaboration_queue_state WHERE parent_id=?",Long.class,parent);return rows.isEmpty()?0:rows.getFirst();}
    @Transactional(readOnly=true)
    public List<Note> messages(UUID parent,UUID task,String email,Instant before,UUID beforeId){
        UUID user=queue.actor(parent,email);queue.visible(parent,task,user,0);
        var notes=jdbc.query("SELECT m.id,u.display_name,m.body,m.created_at FROM collaboration_task_messages m JOIN app_users u ON u.id=m.author_id WHERE m.parent_id=? AND m.task_id=?"
            +(before!=null&&beforeId!=null?" AND (m.created_at,m.id)<(?,?)":"")+" ORDER BY m.created_at DESC,m.id DESC LIMIT 50",
            (rs,n)->new Note(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant(),List.of()),
            before!=null&&beforeId!=null?new Object[]{parent,task,java.sql.Timestamp.from(before),beforeId}:new Object[]{parent,task});
        if(notes.isEmpty())return notes;
        Map<UUID,List<FileInfo>> files=new HashMap<>();List<Object> args=new ArrayList<>(List.of(parent,task));args.addAll(notes.stream().map(Note::id).toList());
        jdbc.query("SELECT id,message_id,file_name,octet_length(content) FROM collaboration_task_files WHERE parent_id=? AND task_id=? AND message_id IN ("+String.join(",",Collections.nCopies(notes.size(),"?"))+") ORDER BY created_at,id",rs->{while(rs.next())files.computeIfAbsent(rs.getObject(2,UUID.class),ignored->new ArrayList<>()).add(new FileInfo(rs.getObject(1,UUID.class),rs.getString(3),rs.getLong(4)));return null;},args.toArray());
        return notes.stream().map(n->new Note(n.id(),n.author(),n.body(),n.createdAt(),files.getOrDefault(n.id(),List.of()))).toList();
    }
    @Transactional(readOnly=true)
    public CollaborationRepository.AttachmentData file(UUID parent,UUID task,UUID file,String email){
        UUID user=queue.actor(parent,email);queue.visible(parent,task,user,0);
        var rows=jdbc.query("SELECT file_name,content_type,content FROM collaboration_task_files WHERE parent_id=? AND task_id=? AND id=?",(rs,n)->new CollaborationRepository.AttachmentData(rs.getString(1),rs.getString(2),rs.getBytes(3)),parent,task,file);return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional
    public void reply(UUID parent,UUID task,String email,String body){
        reply(parent,task,email,body,List.of());
    }
    @Transactional
    public void reply(UUID parent,UUID task,String email,String body,List<CollaborationRepository.AttachmentUpload> files){
        if(body==null||(body.isBlank()&&files.isEmpty())||body.length()>4000)throw new IllegalArgumentException("Write up to 4,000 characters or attach a file.");
        UUID user=queue.actor(parent,email);queue.visible(parent,task,user,0);
        UUID message=UUID.randomUUID();jdbc.update("INSERT INTO collaboration_task_messages(id,parent_id,task_id,author_id,body) VALUES (?,?,?,?,?)",message,parent,task,user,body.isBlank()?"Shared an attachment.":body.trim());
        for(var file:files)jdbc.update("INSERT INTO collaboration_task_files(parent_id,task_id,message_id,file_name,content_type,content) VALUES (?,?,?,?,?,?)",parent,task,message,file.fileName(),file.contentType(),file.bytes());
        var mentioned=queue.mentionAudience(parent,task,user,email,body);queue.notify(parent,task,email,mentioned,"MENTION");
        jdbc.update("UPDATE collaboration_tasks SET updated_at=now() WHERE parent_id=? AND id=?",parent,task);
    }
}
