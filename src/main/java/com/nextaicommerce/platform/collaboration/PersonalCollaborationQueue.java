package com.nextaicommerce.platform.collaboration;

import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PersonalCollaborationQueue {
    record Key(String type, UUID id) {}
    record Card(Key key, String title, UUID account, String context, UUID parent, String state, UUID store, String subjectType,
                @com.fasterxml.jackson.annotation.JsonIgnore UUID alias, @com.fasterxml.jackson.annotation.JsonIgnore Timestamp reopened, @com.fasterxml.jackson.annotation.JsonIgnore Timestamp created,UUID contextAccount,UUID contextStore,boolean sourceRestricted,boolean assignedToMe) {
        Card(Key key,String title,UUID account,String context,UUID parent,String state,UUID store,String subjectType,UUID alias,Timestamp reopened,Timestamp created){
            this(key,title,account,context,parent,state,store,subjectType,alias,reopened,created,account,store,false,false);
        }
        boolean closed() { return state.equals("DONE") || state.equals("CLOSED"); }
    }
    record Row(Card card, Integer position) {}
    record Page(List<Row> rows, long revision) {}
    record Saved(Key key, Timestamp reopened) {}
    private final JdbcTemplate db;
    private final ParentTenantRepository parents;
    private final WorkspaceAccessRepository access;
    PersonalCollaborationQueue(JdbcTemplate db, ParentTenantRepository parents, WorkspaceAccessRepository access) {
        this.db=db; this.parents=parents; this.access=access;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Page load(String email, boolean staff, Key moving, Key target, Long expected) {
        return load(email,staff,moving,target,expected,false);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Page load(String email, boolean staff, Key moving, Key target, Long expected,boolean after) {
        db.queryForObject("select set_config('app.user_email',?,true)",String.class,email);
        UUID user=db.queryForObject("select id from app_users where lower(email)=lower(?) and status='ACTIVE'",UUID.class,email);
        db.update("insert into collaboration_personal_queues(user_id) values (?) on conflict do nothing",user);
        long revision=db.queryForObject("select revision from collaboration_personal_queues where user_id=? for update",Long.class,user);
        var cards=sources(user,email,staff);
        var saved=db.query("select source_type,source_id,observed_reopen from collaboration_personal_queue_entries where user_id=? order by position",
                (r,n)->new Saved(new Key(r.getString(1),r.getObject(2,UUID.class)),r.getTimestamp(3)),user);
        var ordered=new LinkedHashMap<Key,Card>();
        var aliases=new HashMap<Key,Card>();
        for(Card c:cards.values()) if(c.alias()!=null) aliases.putIfAbsent(new Key("REVIEW",c.alias()),c);
        aliases.keySet().forEach(cards::remove);
        for(Saved s:saved) {
            Card c=cards.getOrDefault(s.key(),aliases.get(s.key()));
            if(c!=null && !c.closed()) ordered.putIfAbsent(c.key(),c);
        }
        var observed=new HashMap<Key,Timestamp>();
        saved.forEach(s->observed.put(s.key(),s.reopened()));
        var active=new ArrayList<Card>();
        cards.values().stream().filter(c->!c.closed() && c.reopened()!=null && !c.reopened().equals(observed.get(c.key())))
                .sorted(Comparator.comparing(Card::reopened).reversed().thenComparing(c->c.key().id())).forEach(c->{active.add(c); ordered.remove(c.key());});
        var seen=new HashSet<Key>(); active.forEach(c->seen.add(c.key()));
        active.addAll(ordered.values()); seen.addAll(ordered.keySet());
        cards.values().stream().filter(c->!c.closed() && !seen.contains(c.key())).sorted(Comparator.comparing(Card::created)
                .thenComparing(c->c.key().id())).forEach(active::add);
        boolean changed=!saved.equals(active.stream().map(c->new Saved(c.key(),c.reopened())).toList());
        if(moving!=null) {
            if(changed || !Objects.equals(expected,revision)) throw new CollaborationTaskQueueRepository.QueueChanged();
            Card from=cards.get(moving), to=cards.get(target);
            if(!active.contains(from)||!active.contains(to)) throw new CollaborationTaskQueueRepository.QueueChanged();
            if(!moving.equals(target)){
                active.remove(from);
                active.add(active.indexOf(to)+(after?1:0),from);
                changed=true;
            }
        }
        if(changed) {
            db.update("delete from collaboration_personal_queue_entries where user_id=?",user);
            int[] position={0};
            db.batchUpdate("insert into collaboration_personal_queue_entries(user_id,task_id,review_id,position,observed_reopen) values (?,?,?,?,?)",
                    active,250,(p,c)->{p.setObject(1,user);boolean task=c.key().type().equals("TASK");
                        p.setObject(2,task?c.key().id():null);p.setObject(3,task?null:c.key().id());
                        p.setLong(4,++position[0]);p.setTimestamp(5,c.reopened());});
            db.update("update collaboration_personal_queues set revision=revision+1 where user_id=?",user); revision++;
        }
        var rows=new ArrayList<Row>();
        for(Card c:active) rows.add(new Row(c,rows.size()+1));
        cards.values().stream().filter(Card::closed).sorted(Comparator.comparing(Card::created).reversed()).forEach(c->rows.add(new Row(c,null)));
        return new Page(rows,revision);
    }


    @Transactional(readOnly=true)
    public Page search(Page page,String email,String query) {
        return search(page,email,query,false);
    }
    private Page search(Page page,String email,String query,boolean privateOnly) {
        String term=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        if(term.length()>240)throw new IllegalArgumentException("Search is limited to 240 characters.");
        if(term.isEmpty())return page;
        db.queryForObject("select set_config('app.user_email',?,true)",String.class,email);
        var matched=new ArrayList<Row>();
        for(var row:page.rows()){
            var card=row.card();
            if(card.title().toLowerCase(Locale.ROOT).contains(term)){matched.add(row);continue;}
            boolean found;
            if(card.parent()!=null){
                db.queryForObject("select set_config('app.parent_id',?,true)",String.class,card.parent().toString());
                found=Boolean.TRUE.equals(db.queryForObject("""
                    select exists(select 1 from collaboration_task_messages where parent_id=? and task_id=?
                    and strpos(lower(body),?)>0)
                    """,Boolean.class,card.parent(),card.key().id(),term));
            } else {
                db.queryForObject("select set_config('app.tenant_id',?,true)",String.class,card.account().toString());
                found=Boolean.TRUE.equals(db.queryForObject("""
                    select exists(select 1 from collaboration_messages where tenant_id=? and review_id=?
                    and ((not ? and message_type='TEAM_CHAT') or (? and message_type='PRIVATE_NOTE' and lower(author_email)=lower(?))) and strpos(lower(body),?)>0)
                    """,Boolean.class,card.account(),card.key().id(),privateOnly,privateOnly,email,term));
            }
            if(found)matched.add(row);
        }
        return new Page(matched,page.revision());
    }

    @Transactional(readOnly=true)
    public Card authorized(String email,boolean staff,Key key) {
        db.queryForObject("select set_config('app.user_email',?,true)",String.class,email);
        UUID user=db.queryForObject("select id from app_users where lower(email)=lower(?) and status='ACTIVE'",UUID.class,email);
        Card card=sources(user,email,staff,"ALL",key).get(key);
        if(card==null)throw new org.springframework.security.access.AccessDeniedException("Conversation unavailable.");
        return card;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Page view(String email,boolean staff,String view,String query) {
        if(!Set.of("ASSIGNED","ACTIVE","PRIVATE","CLOSED").contains(view))throw new IllegalArgumentException("Choose a conversation view.");
        if(view.equals("ASSIGNED")) {
            Page personal=load(email,staff,null,null,null);
            return search(new Page(personal.rows().stream().filter(r->!r.card().closed()).toList(),personal.revision()),email,query);
        }
        // Unranked views neither reconcile nor lock a user's independent priority queue.
        db.queryForObject("select set_config('app.user_email',?,true)",String.class,email);
        UUID user=db.queryForObject("select id from app_users where lower(email)=lower(?) and status='ACTIVE'",UUID.class,email);
        var visible=sources(user,email,staff,view);
        visible.values().stream().map(Card::alias).filter(Objects::nonNull).toList()
            .forEach(id->visible.remove(new Key("REVIEW",id)));
        var rows=visible.values().stream()
            .filter(c->c.closed()==view.equals("CLOSED"))
            .sorted(Comparator.comparing(Card::created).reversed()).map(c->new Row(c,null)).toList();
        return search(new Page(rows,0),email,query,view.equals("PRIVATE"));
    }

    /** Exact parent/task audience only; source-account access is deliberately not required. */
    @Transactional(readOnly=true)
    public Map<String,List<CollaborationPerson>> taskPeopleByCard(Page page,String email) {
        var result=new LinkedHashMap<String,List<CollaborationPerson>>();
        var authorizedParents=new HashSet<UUID>();parents.available(email).forEach(p->authorizedParents.add(p.id()));
        var batches=new LinkedHashMap<UUID,List<UUID>>();
        for(var row:page.rows())if(row.card().key().type().equals("TASK")&&authorizedParents.contains(row.card().parent()))
            batches.computeIfAbsent(row.card().parent(),id->new ArrayList<>()).add(row.card().key().id());
        db.queryForObject("select set_config('app.user_email',?,true)",String.class,email);
        for(var batch:batches.entrySet()){
            db.queryForObject("select set_config('app.parent_id',?,true)",String.class,batch.getKey().toString());
            var ids=batch.getValue();
            for(int offset=0;offset<ids.size();offset+=200){
                var part=ids.subList(offset,Math.min(offset+200,ids.size()));
                String marks=String.join(",",Collections.nCopies(part.size(),"?"));
                var args=new ArrayList<Object>();args.add(batch.getKey());args.addAll(part);args.add(email);
                db.query("""
                    SELECT audience.task_id,member.id,member.display_name,audience.assigned
                    FROM collaboration_task_participants audience JOIN app_users member ON member.id=audience.user_id
                    WHERE audience.parent_id=? AND audience.task_id IN (%s)
                      AND EXISTS(SELECT 1 FROM collaboration_tasks task
                        JOIN collaboration_task_participants viewer ON viewer.parent_id=task.parent_id AND viewer.task_id=task.id
                        JOIN app_users actor ON actor.id=viewer.user_id
                        WHERE task.parent_id=audience.parent_id AND task.id=audience.task_id
                          AND lower(actor.email)=lower(?) AND actor.status='ACTIVE')
                    ORDER BY audience.task_id,lower(member.display_name),member.id
                    """.formatted(marks),(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                        String key="TASK"+rs.getObject(1,UUID.class);
                        result.computeIfAbsent(key,id->new ArrayList<>()).add(new CollaborationPerson(rs.getObject(2,UUID.class),rs.getString(3),rs.getBoolean(4),true));
                    },args.toArray());
            }
        }
        return result;
    }

    private Map<Key,Card> sources(UUID user,String email,boolean staff) {
        return sources(user,email,staff,"ASSIGNED");
    }
    private Map<Key,Card> sources(UUID user,String email,boolean staff,String view) {
        return sources(user,email,staff,view,null);
    }
    private Map<Key,Card> sources(UUID user,String email,boolean staff,String view,Key key) {
        // Same permission plan as CollaborationRepository; never change database/global JIT.
        db.queryForObject("SELECT set_config('jit','off',true)",String.class);
        var cards=new LinkedHashMap<Key,Card>();
        var accounts=access.listAccountOptions(email,staff);
        var names=new HashMap<UUID,String>(); var stores=new HashSet<UUID>();
        for(var a:accounts){names.put(a.id(),a.name());a.stores().forEach(s->stores.add(s.id()));}
        for(var parent:(view.equals("PRIVATE")||(key!=null&&!key.type().equals("TASK")))?List.<ParentTenantRepository.Parent>of():parents.available(email)){
            db.queryForObject("select set_config('app.parent_id',?,true)",String.class,parent.id().toString());
            db.query("""
              select t.id,t.title,t.account_id,t.store_id,t.state,t.source_review_id,t.personal_reopened_at,
                     t.created_at,coalesce(t.subject_type,'PLATFORM'),origin.display_name,p.assigned from collaboration_tasks t LEFT JOIN tenants origin ON origin.id=t.account_id join collaboration_task_participants p
              on p.parent_id=t.parent_id and p.task_id=t.id where t.parent_id=? and p.user_id=? and (p.assigned or ?<>'ASSIGNED')
              and (?::uuid is null or t.id=?)
              order by t.created_at,t.id
              """,r->{UUID a=r.getObject(3,UUID.class),s=r.getObject(4,UUID.class);
                boolean visible=names.containsKey(a)&&(s==null||stores.contains(s));
                var k=new Key("TASK",r.getObject(1,UUID.class));cards.put(k,new Card(k,r.getString(2),visible?a:null,
                   a==null?parent.name():visible?names.get(a):r.getString(10),parent.id(),r.getString(5),visible?s:null,r.getString(9),
                   r.getObject(6,UUID.class),r.getTimestamp(7),r.getTimestamp(8),a,s,a!=null&&!visible,r.getBoolean(11)));},parent.id(),user,view,key==null?null:key.id(),key==null?null:key.id());
        }
        for(var a:(key!=null&&!key.type().equals("REVIEW"))?List.<WorkspaceAccessRepository.AccountOption>of():accounts){
            db.queryForObject("select set_config('app.tenant_id',?,true)",String.class,a.id().toString());
            db.query("""
                select review.id,review.title,review.status,review.personal_reopened_at,review.created_at,review.subject_type,coalesce(review.assigned_to=?,false) OR EXISTS(SELECT 1 FROM collaboration_assignments mine WHERE mine.tenant_id=review.tenant_id AND mine.review_id=review.id AND mine.user_id=?)
                from collaboration_reviews review where review.tenant_id=? and (?::uuid is null or review.id=?)
                and (?='ALL' or ?='ASSIGNED' or (review.status='CLOSED')=(?='CLOSED')) and (?<>'ASSIGNED' or review.assigned_to=? or exists
                (select 1 from collaboration_assignments ca where ca.tenant_id=review.tenant_id and ca.review_id=review.id and ca.user_id=?))
                and (?<>'PRIVATE' or exists(select 1 from collaboration_messages mine where mine.tenant_id=review.tenant_id
                    and mine.review_id=review.id and mine.message_type='PRIVATE_NOTE' and lower(mine.author_email)=lower(?)))
                and (
                """+CollaborationRepository.VISIBLE_THREAD+")",r->{var k=new Key("REVIEW",r.getObject(1,UUID.class));
                    cards.put(k,new Card(k,r.getString(2),a.id(),a.name(),null,r.getString(3),null,r.getString(6),null,r.getTimestamp(4),r.getTimestamp(5),a.id(),null,false,r.getBoolean(7)));},user,user,a.id(),key==null?null:key.id(),key==null?null:key.id(),view,view,view,view,user,user,view,email);
        }
        return cards;
    }
}
