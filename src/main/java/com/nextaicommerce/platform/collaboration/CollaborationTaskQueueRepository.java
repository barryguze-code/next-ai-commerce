package com.nextaicommerce.platform.collaboration;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The parent's task queue is serialized here, never reconstructed from a filtered page. */
@Repository
public class CollaborationTaskQueueRepository {
    public record Position(UUID reviewId, String state, Integer position, long revision) {}
    public static final class QueueChanged extends RuntimeException {
        public QueueChanged() { super("The task order changed. Refresh the board and try again."); }
    }
    private final JdbcTemplate jdbc;
    private final org.springframework.context.ApplicationEventPublisher events;
    public record TaskNotification(UUID parent,UUID task,String actor,Set<UUID> people,String kind){}
    public CollaborationTaskQueueRepository(JdbcTemplate jdbc) { this(jdbc,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public CollaborationTaskQueueRepository(JdbcTemplate jdbc,org.springframework.context.ApplicationEventPublisher events) { this.jdbc=jdbc;this.events=events; }
    void notify(UUID parent,UUID task,String actor,Set<UUID> people,String kind){if(events!=null&&!people.isEmpty())events.publishEvent(new TaskNotification(parent,task,actor,Set.copyOf(people),kind));}

    UUID actor(UUID tenant,String email) {
        jdbc.queryForObject("SELECT set_config('app.parent_id',?,true)",String.class,tenant.toString());
        jdbc.queryForObject("SELECT set_config('app.user_email',?,true)",String.class,email);
        var users=jdbc.query("""
            SELECT u.id FROM app_users u JOIN parent_tenant_memberships m ON m.user_id=u.id
            WHERE lower(u.email)=lower(?) AND u.status='ACTIVE' AND m.parent_id=? AND m.active
            """,(rs,n)->rs.getObject(1,UUID.class),email,tenant);
        if(users.size()!=1)throw denied();
        return users.getFirst();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("This task is not available."); }
    long lock(UUID tenant) {
        jdbc.update("INSERT INTO collaboration_queue_state(parent_id) VALUES (?) ON CONFLICT DO NOTHING",tenant);
        return jdbc.queryForObject("SELECT revision FROM collaboration_queue_state WHERE parent_id=? FOR UPDATE",Long.class,tenant);
    }
    Position visible(UUID tenant,UUID review,UUID user,long revision) {
        var result=jdbc.query("""
            SELECT r.state,r.queue_position FROM collaboration_tasks r
            WHERE r.parent_id=? AND r.id=? AND r.state IS NOT NULL
            AND EXISTS(SELECT 1 FROM collaboration_task_participants p
              WHERE p.parent_id=r.parent_id AND p.task_id=r.id AND p.user_id=?)
            """,(rs,n)->new Position(review,rs.getString(1),(Integer)rs.getObject(2),revision),tenant,review,user);
        if(result.isEmpty())throw denied();
        return result.getFirst();
    }
    private int size(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM collaboration_tasks WHERE parent_id=? AND queue_position IS NOT NULL",Integer.class,tenant);
    }
    private void queueMutation(boolean enabled){jdbc.queryForObject("SELECT set_config('app.collaboration_queue_mutation',?,true)",String.class,Boolean.toString(enabled));}
    List<CollaborationRepository.Member> members(UUID parent){
        var rows=jdbc.query("SELECT u.id,u.display_name,u.email FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.parent_id=? AND m.active AND u.status='ACTIVE' ORDER BY u.id LIMIT 500",
            (rs,n)->new CollaborationRepository.Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),null),parent);
        var counts=new java.util.HashMap<String,Integer>();rows.forEach(m->counts.merge(CollaborationRepository.baseHandle(m),1,Integer::sum));
        return rows.stream().map(m->{String h=CollaborationRepository.baseHandle(m);if(counts.get(h)>1)h+="."+m.id().toString().substring(0,6);return new CollaborationRepository.Member(m.id(),m.name(),m.email(),h);}).toList();
    }
    Set<UUID> mentionAudience(UUID parent,UUID task,UUID actor,String email,String body){
        var mentioned=CollaborationMentionService.mentionedMemberIds(body,members(parent),email);
        for(UUID member:mentioned)jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,added_by) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",parent,task,member,actor);
        return mentioned;
    }
    private long changed(UUID tenant,UUID review,UUID actor,String kind,String details) {
        jdbc.update("INSERT INTO collaboration_task_events(parent_id,task_id,actor_id,event_kind,details) VALUES (?,?,?,?,CAST(? AS jsonb))",tenant,review,actor,kind,details);
        return jdbc.queryForObject("UPDATE collaboration_queue_state SET revision=revision+1 WHERE parent_id=? RETURNING revision",Long.class,tenant);
    }

    /** Create parent-owned tasks; the optional account never defines task audience. */
    @Transactional
    public Position create(UUID tenant,UUID account,UUID store,String title,String body,String email,Set<UUID> assignees) {
        return create(tenant,account,store,title,body,email,assignees,null,"America/Los_Angeles");
    }
    @Transactional
    public Position create(UUID tenant,UUID account,UUID store,String title,String body,String email,Set<UUID> assignees,java.time.LocalDate date,String zone) {
        java.time.ZoneId.of(zone);
        if(title==null||title.isBlank()||title.length()>240||body==null||body.length()>4000)
            throw new IllegalArgumentException("Enter a title and a description of up to 4,000 characters.");
        UUID user=actor(tenant,email);lock(tenant);
        var mentions=CollaborationMentionService.mentionedMemberIds(body,members(tenant),email);
        var assigned = new LinkedHashSet<>(assignees);
        if (assigned.isEmpty()) assigned.add(user);
        var participants=new LinkedHashSet<UUID>();participants.add(user);participants.addAll(assigned);participants.addAll(mentions);
        for(UUID member:participants)if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id
            WHERE m.parent_id=? AND m.user_id=? AND m.active AND u.status='ACTIVE')
            """,Boolean.class,tenant,member)))throw new IllegalArgumentException("Choose active members of this parent tenant.");
        if(account!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parent_tenant_accounts WHERE parent_id=? AND account_id=?)",Boolean.class,tenant,account)))throw denied();
        if(store!=null&&account==null)throw denied();
        queueMutation(true);
        UUID review=UUID.randomUUID();int position=size(tenant)+1;
        jdbc.update("INSERT INTO collaboration_tasks(id,parent_id,account_id,store_id,title,created_by,queue_position) VALUES (?,?,?,?,?,?,?)",review,tenant,account,store,title.trim(),user,position);
        if(date!=null)jdbc.update("UPDATE collaboration_tasks SET due_date=?,due_time_zone=? WHERE parent_id=? AND id=?",date,zone,tenant,review);
        for(UUID member:participants)jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,assigned,added_by) VALUES (?,?,?,?,?)",tenant,review,member,assigned.contains(member),user);
        if(!body.isBlank())jdbc.update("INSERT INTO collaboration_task_messages(parent_id,task_id,author_id,body) VALUES (?,?,?,?)",tenant,review,user,body);
        long revision=changed(tenant,review,user,"CREATED","{}");
        queueMutation(false);if(assigned.size()>1)notify(tenant,review,email,assigned,"ASSIGNED");return new Position(review,"OPEN",position,revision);
    }

    @Transactional
    public void assign(UUID parent,UUID task,String email,Set<UUID> requested,boolean removeSelf){
        UUID user=actor(parent,email);lock(parent);visible(parent,task,user,0);
        jdbc.queryForObject("SELECT id FROM collaboration_tasks WHERE parent_id=? AND id=? FOR UPDATE",UUID.class,parent,task);
        var before=new LinkedHashSet<>(jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE parent_id=? AND task_id=? AND assigned",UUID.class,parent,task));
        var after=new LinkedHashSet<>(removeSelf?before:requested);if(removeSelf)after.remove(user);
        if(after.isEmpty())after.add(jdbc.queryForObject("SELECT created_by FROM collaboration_tasks WHERE parent_id=? AND id=?",UUID.class,parent,task));
        var eligible=members(parent).stream().map(CollaborationRepository.Member::id).collect(java.util.stream.Collectors.toSet());
        if(!eligible.containsAll(after))throw new IllegalArgumentException("Choose active members of this parent tenant.");
        if(before.equals(after))return;
        jdbc.update("UPDATE collaboration_task_participants SET assigned=false WHERE parent_id=? AND task_id=?",parent,task);
        for(UUID member:after)jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,assigned,added_by) VALUES (?,?,?,true,?) ON CONFLICT(parent_id,task_id,user_id) DO UPDATE SET assigned=true",parent,task,member,user);
        changed(parent,task,user,"ASSIGNMENT","{\"before\":"+ids(before)+",\"after\":"+ids(after)+"}");
        if(after.size()>1){var added=new LinkedHashSet<>(after);added.removeAll(before);notify(parent,task,email,added,"ASSIGNED");}
    }
    private String ids(Set<UUID> people){return "["+people.stream().sorted().map(id->"\""+id+"\"").collect(java.util.stream.Collectors.joining(","))+"]";}
    @Transactional(readOnly=true)
    public List<UUID> assignments(UUID parent,UUID task,String email){UUID user=actor(parent,email);visible(parent,task,user,0);return jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE parent_id=? AND task_id=? AND assigned ORDER BY user_id",UUID.class,parent,task);}
    @Transactional
    public void due(UUID parent,UUID task,String email,java.time.LocalDate date,String zone){
        java.time.ZoneId.of(zone);UUID user=actor(parent,email);visible(parent,task,user,0);
        jdbc.update("UPDATE collaboration_tasks SET due_date=?,due_time_zone=?,updated_at=now() WHERE parent_id=? AND id=?",date,date==null?null:zone,parent,task);
    }

    @Transactional
    public Position move(UUID tenant,UUID review,String email,int target,long expectedRevision) {
        UUID user=actor(tenant,email);long revision=lock(tenant);
        Position current=visible(tenant,review,user,revision); // Deny before exposing a revision conflict.
        if(revision!=expectedRevision)throw new QueueChanged();
        if(current.position()==null)throw new IllegalArgumentException("Reopen this task before moving it.");
        queueMutation(true);
        if(target<1||target>size(tenant))throw new IllegalArgumentException("Choose a position in the active task queue.");
        int previous=current.position();if(previous==target){queueMutation(false);return current;}
        if(target<previous)jdbc.update("UPDATE collaboration_tasks SET queue_position=queue_position+1 WHERE parent_id=? AND queue_position>=? AND queue_position<?",tenant,target,previous);
        else jdbc.update("UPDATE collaboration_tasks SET queue_position=queue_position-1 WHERE parent_id=? AND queue_position>? AND queue_position<=?",tenant,previous,target);
        jdbc.update("UPDATE collaboration_tasks SET queue_position=?,updated_at=now() WHERE parent_id=? AND id=?",target,tenant,review);
        revision=changed(tenant,review,user,"POSITION","{\"from\":"+previous+",\"to\":"+target+"}");
        queueMutation(false);return new Position(review,current.state(),target,revision);
    }

    @Transactional
    public Position state(UUID tenant,UUID review,String email,String state,long expectedRevision) {
        if(!Set.of("OPEN","IN_PROGRESS","DONE").contains(state))throw new IllegalArgumentException("Choose Open, In progress or Done.");
        UUID user=actor(tenant,email);long revision=lock(tenant);
        Position current=visible(tenant,review,user,revision);
        if(revision!=expectedRevision)throw new QueueChanged();
        if(current.state().equals(state))return current;
        queueMutation(true);
        Integer position=current.position();
        if("DONE".equals(state)) {
            jdbc.update("UPDATE collaboration_tasks SET state='DONE',queue_position=NULL,closed_at=now(),updated_at=now() WHERE parent_id=? AND id=?",tenant,review);
            jdbc.update("UPDATE collaboration_tasks SET queue_position=queue_position-1 WHERE parent_id=? AND queue_position>?",tenant,position);
            position=null;
        } else {
            if(position==null) {
                jdbc.update("UPDATE collaboration_tasks SET queue_position=queue_position+1 WHERE parent_id=? AND queue_position IS NOT NULL",tenant);
                position=1;
            }
            jdbc.update("UPDATE collaboration_tasks SET state=?,queue_position=?,closed_at=NULL,updated_at=now() WHERE parent_id=? AND id=?",state,position,tenant,review);
        }
        revision=changed(tenant,review,user,"STATE","{\"from\":\""+current.state()+"\",\"to\":\""+state+"\"}");
        queueMutation(false);
        if("DONE".equals(state))notify(tenant,review,email,new LinkedHashSet<>(jdbc.queryForList(
            "SELECT user_id FROM collaboration_task_participants WHERE parent_id=? AND task_id=?",UUID.class,tenant,review)),"COMPLETED");
        return new Position(review,state,position,revision);
    }
}
