package com.nextaicommerce.platform.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CollaborationRepository {
    public record Member(UUID id,String name,String email,String handle) {}
    public record Review(UUID id,String subjectType,String subjectKey,String subjectLabel,String marketplace,
            String actionKind,String title,String status,String requester,String assigneeName,String assigneeEmail,
            Instant dueAt,Instant createdAt,Instant updatedAt,Instant closedAt,int messageCount,int privateNoteCount,
            String participants,String contextSnapshot,String parentUrl,boolean mentionedMe,
            java.time.LocalDate dueDate,String dueTimeZone,UUID storeId,int unreadMessageCount) {
        public Review(UUID id,String subjectType,String subjectKey,String subjectLabel,String marketplace,String actionKind,String title,String status,String requester,String assigneeName,String assigneeEmail,Instant dueAt,Instant createdAt,Instant updatedAt,Instant closedAt,int messageCount,int privateNoteCount,String participants,String contextSnapshot,String parentUrl,boolean mentionedMe){
            this(id,subjectType,subjectKey,subjectLabel,marketplace,actionKind,title,status,requester,assigneeName,assigneeEmail,dueAt,createdAt,updatedAt,closedAt,messageCount,privateNoteCount,participants,contextSnapshot,parentUrl,mentionedMe,null,null,null,0);
        }
        public boolean overdue(){return "ACTIVE".equals(status)&&dueAt!=null&&!dueAt.isAfter(Instant.now());}
        public boolean assignedTo(String email){return assigneeEmail!=null&&java.util.Arrays.stream(assigneeEmail.split(",")).anyMatch(e->e.equalsIgnoreCase(email));}
    }
    public record Attachment(UUID id,String fileName,String contentType,long sizeBytes,Instant createdAt) {}
    public record AttachmentData(String fileName,String contentType,byte[] bytes) {}
    public record AttachmentUpload(String fileName,String contentType,byte[] bytes) {}
    public record Message(UUID id,UUID senderId,String senderName,String authorEmail,String messageType,String body,
            Instant createdAt,List<Attachment> attachments) {}
    public record SubjectSummary(UUID reviewId,int activeCount,int activeMessageCount,int totalCount,
            int closedCount,Instant latestActivityAt,int originCount,int relatedCount,int mineCount,int urgentUnreadMentionCount,int unreadMessageCount,int directMessageCount,int relatedMessageCount) {}
    public record PostedMessage(UUID reviewId,UUID messageId) {}
    public record HuddleTranscriptMessage(UUID senderId,String senderName,String senderEmail,String body,Instant createdAt) {}
    public record PendingMention(UUID id,UUID reviewId,String recipientName,String recipientEmail,String accountName,
            String subjectLabel,String authorEmail,String body,String notificationKind) {}

    private static final String VIEWER="nullif(current_setting('app.user_email',true),'')";
    private static final String UNREAD_MESSAGES="""
        (SELECT count(*) FROM collaboration_messages unread_message
         WHERE unread_message.tenant_id=review.tenant_id AND unread_message.review_id=review.id
           AND unread_message.message_type='TEAM_CHAT' AND lower(unread_message.author_email)<>lower(%s)
           AND NOT EXISTS (SELECT 1 FROM collaboration_read_receipts receipt JOIN app_users reader ON reader.id=receipt.user_id
             WHERE receipt.tenant_id=review.tenant_id AND receipt.message_id=unread_message.id AND lower(reader.email)=lower(%s)))
        """.formatted(VIEWER,VIEWER);
    private static final String VISIBLE_THREAD="""
        EXISTS (SELECT 1 FROM collaboration_messages visible_message
                WHERE visible_message.review_id=review.id AND visible_message.message_type='TEAM_CHAT')
        OR EXISTS (SELECT 1 FROM collaboration_messages private_message
                   WHERE private_message.review_id=review.id AND private_message.message_type='PRIVATE_NOTE'
                     AND lower(private_message.author_email)=lower(%s))
        """.formatted(VIEWER);
    private static final String REVIEW_COLUMNS="""
        review.id,review.subject_type,review.subject_key,review.subject_label,review.marketplace,
        review.action_kind,review.title,review.status,review.requested_by,
        coalesce((SELECT string_agg(u.display_name,', ' ORDER BY u.display_name) FROM collaboration_assignments a JOIN app_users u ON u.id=a.user_id WHERE a.tenant_id=review.tenant_id AND a.review_id=review.id),assignee.display_name),
        coalesce((SELECT string_agg(u.email,',' ORDER BY u.email) FROM collaboration_assignments a JOIN app_users u ON u.id=a.user_id WHERE a.tenant_id=review.tenant_id AND a.review_id=review.id),assignee.email),
        review.due_at,review.created_at,review.updated_at,review.closed_at,
        (SELECT count(*) FROM collaboration_messages visible_count WHERE visible_count.review_id=review.id AND
          (visible_count.message_type='TEAM_CHAT' OR (visible_count.message_type='PRIVATE_NOTE' AND lower(visible_count.author_email)=lower(%s)))),
        (SELECT count(*) FROM collaboration_messages private_count WHERE private_count.review_id=review.id
          AND private_count.message_type='PRIVATE_NOTE' AND lower(private_count.author_email)=lower(%s)),
        coalesce((SELECT string_agg(participant.name,', ' ORDER BY participant.name) FROM (
          SELECT DISTINCT coalesce(author.display_name,message.author_email) AS name
          FROM collaboration_messages message LEFT JOIN app_users author ON author.id=message.sender_id
          WHERE message.review_id=review.id AND message.message_type='TEAM_CHAT'
          UNION
          SELECT DISTINCT mentioned.display_name AS name FROM collaboration_mentions mention
          JOIN collaboration_messages mentioned_message ON mentioned_message.id=mention.message_id AND mentioned_message.message_type='TEAM_CHAT'
          JOIN app_users mentioned ON mentioned.id=mention.mentioned_user_id WHERE mention.review_id=review.id
        ) participant),review.requested_by),
        review.context_snapshot::text,review.parent_url,
        EXISTS (SELECT 1 FROM collaboration_mentions mine JOIN app_users me ON me.id=mine.mentioned_user_id
                WHERE mine.review_id=review.id AND mine.notification_kind='MENTION' AND lower(me.email)=lower(%s)),
        review.due_date,review.due_time_zone,review.marketplace_connection_id
        """.formatted(VIEWER,VIEWER,VIEWER)+","+UNREAD_MESSAGES;

    private final JdbcTemplate jdbc;
    public CollaborationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    /** Accepted memberships, not the account currently selected in another browser tab. */
    @Transactional(readOnly=true)
    public java.util.Set<UUID> huddleTenants(String email){
        var result=new java.util.LinkedHashSet<UUID>();
        var tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE'",(rs,n)->rs.getObject(1,UUID.class));
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users u JOIN platform_administrators p ON p.user_id=u.id AND p.active WHERE lower(u.email)=lower(?) AND u.status='ACTIVE')",Boolean.class,email)))return java.util.Set.copyOf(tenants);
        for(UUID tenant:tenants){
            setContext(tenant,email);
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM app_users u WHERE lower(u.email)=lower(?) AND u.status='ACTIVE'
                  AND (EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=u.id AND p.active)
                    OR EXISTS(SELECT 1 FROM tenant_memberships m WHERE m.tenant_id=? AND m.user_id=u.id
                      AND m.role IN ('OWNER','ADMIN','OPERATOR','VIEWER'))))
                """,Boolean.class,email,tenant)))result.add(tenant);
        }
        return java.util.Set.copyOf(result);
    }

    @Transactional(readOnly=true)
    public List<Member> members(UUID tenantId){
        setContext(tenantId,null);
        var rows=jdbc.query("""
            SELECT user_account.id,user_account.display_name,user_account.email FROM app_users user_account
            WHERE user_account.status='ACTIVE' AND (EXISTS(SELECT 1 FROM tenant_memberships membership WHERE membership.tenant_id=? AND membership.user_id=user_account.id) OR EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=user_account.id AND p.active))
            ORDER BY lower(user_account.display_name),lower(user_account.email),user_account.id
            """,(rs,row)->new Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),null),tenantId);
        var counts=new java.util.HashMap<String,Integer>();rows.forEach(member->counts.merge(baseHandle(member),1,Integer::sum));
        return rows.stream().map(member->{String handle=baseHandle(member);if(counts.getOrDefault(handle,0)>1)handle+="."+member.id().toString().substring(0,6);return new Member(member.id(),member.name(),member.email(),handle);}).toList();
    }

    @Transactional(readOnly=true)
    public Member currentMember(UUID tenantId,String email){
        setContext(tenantId,email);
        var rows=jdbc.query("""
            SELECT user_account.id,user_account.display_name,user_account.email
            FROM app_users user_account
            WHERE lower(user_account.email)=lower(?) AND user_account.status='ACTIVE'
            LIMIT 1
            """,(rs,row)->new Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),baseHandle(
                new Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),null))),email);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(readOnly=true)
    public List<Review> reviews(UUID tenantId,String view,String subjectType,String subjectKey,String viewerEmail){
        setContext(tenantId,viewerEmail);
        String normalized=view==null?"ACTIVE":view.toUpperCase(Locale.ROOT);
        String condition=switch(normalized){
            case "CLOSED"->" AND review.status='CLOSED'";
            case "MENTIONS"->" AND review.status='ACTIVE' AND EXISTS (SELECT 1 FROM collaboration_mentions filter_mention JOIN app_users filter_user ON filter_user.id=filter_mention.mentioned_user_id WHERE filter_mention.review_id=review.id AND filter_mention.notification_kind='MENTION' AND lower(filter_user.email)=lower("+VIEWER+"))";
            case "PRIVATE"->" AND review.status='ACTIVE' AND EXISTS (SELECT 1 FROM collaboration_messages filter_private WHERE filter_private.review_id=review.id AND filter_private.message_type='PRIVATE_NOTE' AND lower(filter_private.author_email)=lower("+VIEWER+"))";
            case "ALL"->"";
            default->" AND review.status='ACTIVE'";
        };
        List<Object> values=new ArrayList<>();values.add(tenantId);
        if(subjectType!=null&&!subjectType.isBlank()){condition+=" AND review.subject_type=?";values.add(subjectType.toUpperCase(Locale.ROOT));}
        if(subjectKey!=null&&!subjectKey.isBlank()){condition+=" AND review.subject_key=?";values.add(subjectKey);}
        return jdbc.query("SELECT "+REVIEW_COLUMNS+" FROM collaboration_reviews review LEFT JOIN app_users assignee ON assignee.id=review.assigned_to WHERE review.tenant_id=? AND ("+VISIBLE_THREAD+")"+condition+" ORDER BY CASE WHEN review.status='CLOSED' THEN 1 ELSE 0 END,review.updated_at DESC LIMIT 250",
            (rs,row)->review(rs),values.toArray());
    }

    // Related records reference the same conversation, retaining its original subject and URL.
    // Every join stays tenant- and store-scoped; private-note visibility is applied by callers.
    private static String subjectMatch(String type,String key) {
        return "(review.subject_type="+type+" AND review.subject_key="+key+") OR "
            +"(review.subject_type='ORDER' AND EXISTS (SELECT 1 FROM amazon_order_items linked_item "
            +"LEFT JOIN marketplace_sku_mappings linked_map ON linked_map.tenant_id=linked_item.tenant_id "
            +"AND linked_map.marketplace_connection_id=linked_item.marketplace_connection_id "
            +"AND linked_map.marketplace_sku=linked_item.seller_sku AND linked_map.status='ACTIVE' "
            +"LEFT JOIN marketplace_sku_mapping_components linked_component ON linked_component.tenant_id=linked_map.tenant_id "
            +"AND linked_component.marketplace_sku_mapping_id=linked_map.id "
            +"WHERE linked_item.tenant_id=review.tenant_id AND linked_item.amazon_order_id=review.subject_key "
            +"AND (("+type+"='MARKETPLACE_SKU' AND linked_item.seller_sku="+key+") "
            +"OR ("+type+"='CATALOG' AND linked_component.account_catalog_item_id::text="+key+") "
            +"OR ("+type+"='INVENTORY' AND linked_component.account_catalog_item_id::text=split_part("+key+",'|',1))))) OR "
            +"("+type+"='ORDER' AND review.subject_type='MARKETPLACE_SKU' AND EXISTS ("
            +"SELECT 1 FROM amazon_order_items inherited_item WHERE inherited_item.tenant_id=review.tenant_id "
            +"AND inherited_item.amazon_order_id="+key+" AND inherited_item.seller_sku=review.subject_key "
            +"AND (review.marketplace_connection_id IS NULL OR review.marketplace_connection_id=inherited_item.marketplace_connection_id)))";
    }

    @Transactional(readOnly=true)
    public List<Review> subjectReviews(UUID tenantId,String subjectType,String subjectKey,boolean includeClosed,String viewerEmail){
        setContext(tenantId,viewerEmail);
        return jdbc.query("WITH requested AS (SELECT ?::text AS kind,?::text AS key) SELECT "+REVIEW_COLUMNS
            +" FROM collaboration_reviews review CROSS JOIN requested LEFT JOIN app_users assignee ON assignee.id=review.assigned_to"
            +" WHERE review.tenant_id=? AND ("+subjectMatch("requested.kind","requested.key")+") AND ("+VISIBLE_THREAD+") "
            +(includeClosed?"":"AND review.status='ACTIVE'")+" ORDER BY review.updated_at DESC LIMIT 50",
            (rs,row)->review(rs),subjectType.toUpperCase(Locale.ROOT),subjectKey,tenantId);
    }

    @Transactional(readOnly=true)
    public Review review(UUID tenantId,UUID reviewId,String viewerEmail){
        setContext(tenantId,viewerEmail);
        var rows=jdbc.query("SELECT "+REVIEW_COLUMNS+" FROM collaboration_reviews review LEFT JOIN app_users assignee ON assignee.id=review.assigned_to WHERE review.tenant_id=? AND review.id=? AND ("+VISIBLE_THREAD+")",
            (rs,row)->review(rs),tenantId,reviewId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(readOnly=true)
    public Map<String,SubjectSummary> openSubjectSummaries(UUID tenantId,String subjectType,List<String> subjectKeys,String viewerEmail){
        if(subjectKeys.isEmpty())return Map.of();
        setContext(tenantId,viewerEmail);
        List<String> unique=subjectKeys.stream().distinct().toList();
        String placeholders=String.join(",",Collections.nCopies(unique.size(),"(?)"));
        List<Object> values=new ArrayList<>();values.addAll(unique);values.add(subjectType.toUpperCase(Locale.ROOT));values.add(tenantId);
        Map<String,SubjectSummary> result=new LinkedHashMap<>();
        String sql="WITH requested(key) AS (VALUES "+placeholders+"), kind AS (SELECT ?::text AS value), visible AS ("
            +" SELECT requested.key,review.id,review.status,review.updated_at,"
            +" (review.subject_type=kind.value AND review.subject_key=requested.key) AS origin,"
            // Historical mentions and overdue dates are not proof of an unread @mention.
            +" (EXISTS(SELECT 1 FROM collaboration_assignments a JOIN app_users assigned_user ON assigned_user.id=a.user_id WHERE a.tenant_id=review.tenant_id AND a.review_id=review.id AND lower(assigned_user.email)=lower("+VIEWER+")) OR lower(assignee.email)=lower("+VIEWER+")) AS mine,"
            +" (SELECT count(DISTINCT mention.message_id) FROM collaboration_mentions mention JOIN app_users reader ON reader.id=mention.mentioned_user_id "
            +" JOIN collaboration_messages mentioned_message ON mentioned_message.id=mention.message_id AND mentioned_message.tenant_id=mention.tenant_id "
            +" WHERE mention.tenant_id=review.tenant_id AND mention.review_id=review.id AND mention.notification_kind='MENTION' "
            +" AND mentioned_message.message_type='TEAM_CHAT' AND lower(reader.email)=lower("+VIEWER+") "
            +" AND NOT EXISTS (SELECT 1 FROM collaboration_read_receipts receipt WHERE receipt.tenant_id=mention.tenant_id AND receipt.user_id=reader.id AND receipt.message_id=mention.message_id)) AS unread_mentions,"
            +UNREAD_MESSAGES+" AS unread_messages,"
            +" (SELECT count(*) FROM collaboration_messages shared_message WHERE shared_message.tenant_id=review.tenant_id AND shared_message.review_id=review.id AND shared_message.message_type='TEAM_CHAT') AS shared_messages,"
            +" (SELECT count(*) FROM collaboration_messages message WHERE message.review_id=review.id AND "
            +" (message.message_type='TEAM_CHAT' OR (message.message_type='PRIVATE_NOTE' AND lower(message.author_email)=lower("+VIEWER+")))) AS messages"
            +" FROM requested CROSS JOIN kind JOIN collaboration_reviews review ON ("+subjectMatch("kind.value","requested.key")+")"
            +" LEFT JOIN app_users assignee ON assignee.id=review.assigned_to WHERE review.tenant_id=? AND ("+VISIBLE_THREAD+"))"
            +" SELECT key,(array_agg(id ORDER BY updated_at DESC) FILTER (WHERE status='ACTIVE'))[1],"
            +" count(*) FILTER (WHERE status='ACTIVE'),coalesce(sum(messages) FILTER (WHERE status='ACTIVE'),0),"
            +" count(*),count(*) FILTER (WHERE status='CLOSED'),max(updated_at),"
            +" count(*) FILTER (WHERE status='ACTIVE' AND origin),count(*) FILTER (WHERE status='ACTIVE' AND NOT origin),"
            +" count(*) FILTER (WHERE status='ACTIVE' AND mine),coalesce(sum(unread_mentions) FILTER (WHERE status='ACTIVE'),0),coalesce(sum(unread_messages) FILTER (WHERE status='ACTIVE' AND origin),0),"
            +" coalesce(sum(shared_messages) FILTER (WHERE status='ACTIVE' AND origin),0),coalesce(sum(shared_messages) FILTER (WHERE status='ACTIVE' AND NOT origin),0) FROM visible GROUP BY key";
        jdbc.query(sql,(org.springframework.jdbc.core.ResultSetExtractor<Void>)rs->{
            while(rs.next())result.put(rs.getString(1),new SubjectSummary(rs.getObject(2,UUID.class),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),instant(rs,7),rs.getInt(8),rs.getInt(9),rs.getInt(10),rs.getInt(11),rs.getInt(12),rs.getInt(13),rs.getInt(14)));return null;
        },values.toArray());
        return result;
    }

    @Transactional(readOnly=true)
    public List<UUID> unreadMentionMessages(UUID tenantId,UUID reviewId,String viewerEmail){
        setContext(tenantId,viewerEmail);
        return jdbc.queryForList("""
            SELECT DISTINCT mention.message_id FROM collaboration_mentions mention
            JOIN app_users reader ON reader.id=mention.mentioned_user_id
            JOIN collaboration_messages message ON message.id=mention.message_id AND message.tenant_id=mention.tenant_id
            WHERE mention.tenant_id=? AND mention.review_id=? AND lower(reader.email)=lower(?)
              AND mention.notification_kind='MENTION' AND message.message_type='TEAM_CHAT'
              AND NOT EXISTS (SELECT 1 FROM collaboration_read_receipts receipt WHERE receipt.tenant_id=mention.tenant_id
                  AND receipt.user_id=reader.id AND receipt.message_id=mention.message_id)
            """,UUID.class,tenantId,reviewId,viewerEmail);
    }

    @Transactional
    public boolean acknowledgeMessages(UUID tenantId,UUID reviewId,String viewerEmail,List<UUID> messageIds){
        if(messageIds.size()>250)throw new IllegalArgumentException("Acknowledge up to 250 messages at a time.");
        setContext(tenantId,viewerEmail);
        if(review(tenantId,reviewId,viewerEmail)==null)return false;
        if(messageIds.isEmpty())return true;
        var ids=messageIds.stream().distinct().toList();
        jdbc.update("INSERT INTO collaboration_read_receipts(tenant_id,user_id,message_id) "
            +"SELECT message.tenant_id,reader.id,message.id FROM collaboration_messages message JOIN app_users reader ON lower(reader.email)=lower(?) "
            +"WHERE message.tenant_id=? AND message.review_id=? AND message.message_type='TEAM_CHAT' AND message.id IN ("
            +String.join(",",Collections.nCopies(ids.size(),"?"))+") ON CONFLICT DO NOTHING",
            receiptArguments(tenantId,viewerEmail,reviewId,ids));
        return true;
    }
    private Object[] receiptArguments(UUID tenantId,String viewerEmail,UUID reviewId,List<UUID> ids){
        var args=new ArrayList<Object>();args.add(viewerEmail);args.add(tenantId);args.add(reviewId);args.addAll(ids);return args.toArray();
    }

    @Transactional(readOnly=true)
    public List<Message> messages(UUID tenantId,UUID reviewId,String viewerEmail,String requestedType){
        setContext(tenantId,viewerEmail);String type=normalizeMessageType(requestedType);
        String privacy="PRIVATE_NOTE".equals(type)?" AND lower(message.author_email)=lower("+VIEWER+")":"";
        var base=jdbc.query("""
            SELECT message.id,message.sender_id,coalesce(sender.display_name,message.author_email),message.author_email,
              message.message_type,message.body,message.created_at
            FROM collaboration_messages message LEFT JOIN app_users sender ON sender.id=message.sender_id
            JOIN collaboration_reviews review ON review.id=message.review_id AND review.tenant_id=message.tenant_id
            WHERE message.tenant_id=? AND message.review_id=? AND message.message_type=? %s ORDER BY message.created_at
            """.formatted(privacy),(rs,row)->new Message(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),instant(rs,7),List.of()),tenantId,reviewId,type);
        if(base.isEmpty())return base;
        String placeholders=String.join(",",Collections.nCopies(base.size(),"?"));Map<UUID,List<Attachment>> byMessage=new java.util.HashMap<>();
        jdbc.query("SELECT id,message_id,file_name,content_type,size_bytes,created_at FROM collaboration_attachments WHERE tenant_id=? AND message_id IN ("+placeholders+") ORDER BY created_at",rs->{
            while(rs.next())byMessage.computeIfAbsent(rs.getObject(2,UUID.class),ignored->new ArrayList<>()).add(new Attachment(rs.getObject(1,UUID.class),rs.getString(3),rs.getString(4),rs.getLong(5),instant(rs,6)));return null;
        },join(tenantId,base.stream().map(Message::id).toList()));
        return base.stream().map(message->new Message(message.id(),message.senderId(),message.senderName(),message.authorEmail(),message.messageType(),message.body(),message.createdAt(),byMessage.getOrDefault(message.id(),List.of()))).toList();
    }

    @Transactional
    public PostedMessage create(UUID tenantId,String subjectType,String subjectKey,String subjectLabel,String marketplace,
            String actionKind,String title,String message,String messageType,String contextSnapshot,String parentUrl,
            String authorEmail,UUID assigneeId,Instant dueAt,List<AttachmentUpload> attachments){
        setContext(tenantId,authorEmail);String type=normalizeMessageType(messageType);
        UUID reviewId=jdbc.queryForObject("""
            INSERT INTO collaboration_reviews(tenant_id,subject_type,subject_key,subject_label,marketplace,action_kind,title,
              requested_by,created_by,assigned_to,due_at,context_snapshot,parent_url)
            VALUES (?,?,?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?) LIMIT 1),?,?,CAST(? AS jsonb),?) RETURNING id
            """,UUID.class,tenantId,subjectType.toUpperCase(Locale.ROOT),subjectKey,subjectLabel,marketplace,actionKind,title,
            authorEmail,authorEmail,assigneeId,dueAt==null?null:java.sql.Timestamp.from(dueAt),contextSnapshot,parentUrl);
        UUID messageId=jdbc.queryForObject("""
            INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body)
            VALUES (?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?) LIMIT 1),?,?) RETURNING id
            """,UUID.class,tenantId,reviewId,authorEmail,authorEmail,type,message.trim());
        saveAttachmentsInternal(tenantId,reviewId,messageId,authorEmail,attachments);return new PostedMessage(reviewId,messageId);
    }

    @Transactional
    public PostedMessage createGeneralTask(UUID tenantId,String title,String description,String authorEmail,Set<UUID> people,Instant dueAt){
        var posted=create(tenantId,"PLATFORM","GENERAL:"+UUID.randomUUID(),title,null,"TASK",title,description,
            "TEAM_CHAT","{}",null,authorEmail,null,dueAt,List.of());
        // Create and assign atomically: invalid/cross-account assignees must not leave an orphan task.
        assign(tenantId,posted.reviewId(),authorEmail,people,true);
        return posted;
    }

    @Transactional
    public PostedMessage createStoreTask(UUID tenantId,UUID storeId,String title,String description,String actor,Set<UUID> people,java.time.LocalDate date,String zone){
        setContext(tenantId,actor);
        if(storeId!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_connections WHERE tenant_id=? AND id=?)",Boolean.class,tenantId,storeId)))throw new IllegalArgumentException("Choose a store from this account.");
        var posted=createGeneralTask(tenantId,title,description.isBlank()?title:description,actor,people,deadline(date,zone));
        jdbc.update("UPDATE collaboration_reviews SET marketplace_connection_id=?,due_date=?,due_time_zone=? WHERE tenant_id=? AND id=?",storeId,date==null?null:java.sql.Date.valueOf(date),date==null?null:zone,tenantId,posted.reviewId());
        return posted;
    }

    public static Instant deadline(java.time.LocalDate date,String zone){return date==null?null:date.plusDays(1).atStartOfDay(java.time.ZoneId.of(zone)).toInstant();}

    @Transactional
    public PostedMessage createDatedConversation(UUID tenantId,String subjectType,String subjectKey,String subjectLabel,String marketplace,
            String actionKind,String title,String message,String messageType,String contextSnapshot,String parentUrl,
            String actor,UUID assignee,java.time.LocalDate date,String zone,List<AttachmentUpload> attachments){
        var posted=create(tenantId,subjectType,subjectKey,subjectLabel,marketplace,actionKind,title,message,messageType,contextSnapshot,parentUrl,actor,assignee,deadline(date,zone),attachments);
        if(date!=null)setDueDate(tenantId,posted.reviewId(),actor,date,zone);
        return posted;
    }

    @Transactional
    public Review setDueDate(UUID tenantId,UUID reviewId,String actor,java.time.LocalDate date,String zone){
        setContext(tenantId,actor);
        var current=review(tenantId,reviewId,actor);
        if(current==null)throw new IllegalArgumentException("This conversation is no longer available.");
        if(!"ACTIVE".equals(current.status()))throw new IllegalArgumentException("Reopen this conversation before changing its due date.");
        Instant due=deadline(date,zone);
        jdbc.update("UPDATE collaboration_reviews SET due_at=?,due_date=?,due_time_zone=?,updated_at=now() WHERE tenant_id=? AND id=?",due==null?null:java.sql.Timestamp.from(due),date==null?null:java.sql.Date.valueOf(date),date==null?null:zone,tenantId,reviewId);
        return review(tenantId,reviewId,actor);
    }

    /** Resolve existing artwork in one query, without fetching Amazon or decoding pictures. */
    @Transactional(readOnly=true)
    public Map<UUID,String> pictures(UUID tenantId,List<Review> reviews,String actor){
        if(reviews.isEmpty())return Map.of();setContext(tenantId,actor);
        String slots=String.join(",",Collections.nCopies(reviews.size(),"?"));
        Map<UUID,String> result=new LinkedHashMap<>();
        jdbc.query("""
            SELECT r.id,coalesce(
              CASE WHEN uploaded.account_catalog_item_id IS NOT NULL THEN '/app/catalog/products/'||item.id||'/image' END,
              artwork.image_url,nullif(r.context_snapshot->>'image',''))
            FROM collaboration_reviews r
            LEFT JOIN account_catalog_items item ON item.tenant_id=r.tenant_id
              AND item.id::text=CASE WHEN r.subject_type='INVENTORY' THEN split_part(r.subject_key,'|',1) WHEN r.subject_type='CATALOG' THEN r.subject_key END
            LEFT JOIN account_catalog_product_images uploaded ON uploaded.tenant_id=item.tenant_id AND uploaded.account_catalog_item_id=item.id
            LEFT JOIN LATERAL (
              SELECT listing.image_url FROM amazon_listings listing
              WHERE listing.tenant_id=r.tenant_id AND listing.image_url IS NOT NULL
                AND (r.marketplace_connection_id IS NULL OR listing.marketplace_connection_id=r.marketplace_connection_id)
                AND ((r.subject_type='MARKETPLACE_SKU' AND listing.seller_sku=r.subject_key)
                  OR (r.subject_type='ORDER' AND EXISTS(SELECT 1 FROM amazon_order_items oi WHERE oi.tenant_id=r.tenant_id AND oi.amazon_order_id=r.subject_key AND oi.marketplace_connection_id=listing.marketplace_connection_id AND oi.seller_sku=listing.seller_sku))
                  OR (item.id IS NOT NULL AND EXISTS(SELECT 1 FROM marketplace_sku_mappings m JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id WHERE m.tenant_id=r.tenant_id AND m.marketplace_connection_id=listing.marketplace_connection_id AND m.marketplace_sku=listing.seller_sku AND m.status='ACTIVE' AND c.account_catalog_item_id=item.id AND (SELECT count(*) FROM marketplace_sku_mapping_components all_c WHERE all_c.tenant_id=m.tenant_id AND all_c.marketplace_sku_mapping_id=m.id)=1)))
              ORDER BY listing.last_seen_at DESC LIMIT 1
            ) artwork ON true
            WHERE r.tenant_id=? AND r.id IN (
            """+slots+")",rs->{while(rs.next()){String url=rs.getString(2);if(url!=null&&(url.startsWith("/app/")||url.startsWith("https://")))result.put(rs.getObject(1,UUID.class),url);}return null;},join(tenantId,reviews.stream().map(Review::id).toList()));
        return result;
    }

    @Transactional
    public PostedMessage saveHuddle(UUID tenantId,String subjectType,String subjectKey,String subjectLabel,String contextSnapshot,
            String parentUrl,Member creator,List<HuddleTranscriptMessage> transcript,boolean closeConversation){
        setContext(tenantId,creator.email());
        List<HuddleTranscriptMessage> messages=transcript==null?List.of():transcript;
        Instant createdAt=messages.isEmpty()?Instant.now():messages.getFirst().createdAt();
        Instant updatedAt=messages.isEmpty()?createdAt:messages.getLast().createdAt();
        String status=closeConversation?"CLOSED":"ACTIVE";
        UUID reviewId=jdbc.queryForObject("""
            INSERT INTO collaboration_reviews(tenant_id,subject_type,subject_key,subject_label,action_kind,title,status,
              requested_by,created_by,context_snapshot,parent_url,created_at,updated_at,closed_at)
            VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?) RETURNING id
            """,UUID.class,tenantId,subjectType.toUpperCase(Locale.ROOT),subjectKey,subjectLabel,"QUICK_HUDDLE",
            "Quick huddle about "+subjectLabel,status,creator.email(),creator.id(),contextSnapshot,parentUrl,
            createdAt,updatedAt,closeConversation?updatedAt:null);
        UUID firstMessageId=null;
        if(messages.isEmpty()){
            firstMessageId=jdbc.queryForObject("""
                INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body,created_at)
                VALUES (?,?,?,?, 'TEAM_CHAT',?,?) RETURNING id
                """,UUID.class,tenantId,reviewId,creator.email(),creator.id(),
                "Quick huddle saved for follow-up before any live replies were received.",createdAt);
        }else for(HuddleTranscriptMessage message:messages){
            UUID messageId=jdbc.queryForObject("""
                INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body,created_at)
                VALUES (?,?,?,?, 'TEAM_CHAT',?,?) RETURNING id
                """,UUID.class,tenantId,reviewId,message.senderEmail(),message.senderId(),message.body(),message.createdAt());
            if(firstMessageId==null)firstMessageId=messageId;
        }
        return new PostedMessage(reviewId,firstMessageId);
    }

    @Transactional
    public PostedMessage reply(UUID tenantId,UUID reviewId,String authorEmail,String body,String messageType,String status,List<AttachmentUpload> attachments){
        setContext(tenantId,authorEmail);String type=normalizeMessageType(messageType);UUID messageId=null;
        if((body!=null&&!body.isBlank())||(attachments!=null&&!attachments.isEmpty())){
            String safeBody=body==null||body.isBlank()?"Shared an attachment.":body.trim();
            messageId=jdbc.query("""
                INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body)
                SELECT ?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?) LIMIT 1),?,?
                WHERE EXISTS (SELECT 1 FROM collaboration_reviews WHERE id=? AND tenant_id=? AND status='ACTIVE') RETURNING id
                """,(org.springframework.jdbc.core.ResultSetExtractor<UUID>)rs->rs.next()?rs.getObject(1,UUID.class):null,
                tenantId,reviewId,authorEmail,authorEmail,type,safeBody,reviewId,tenantId);
            if(messageId==null)throw new IllegalArgumentException("This conversation is already completed.");
            saveAttachmentsInternal(tenantId,reviewId,messageId,authorEmail,attachments);
        }
        if("CLOSED".equals(status)){
            UUID prior=messageId;
            messageId=jdbc.query("""
                INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body)
                SELECT ?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?) LIMIT 1),
                  CASE WHEN EXISTS (SELECT 1 FROM collaboration_messages team WHERE team.review_id=? AND team.message_type='TEAM_CHAT') THEN 'TEAM_CHAT' ELSE 'PRIVATE_NOTE' END,?
                WHERE EXISTS (SELECT 1 FROM collaboration_reviews WHERE id=? AND tenant_id=? AND status='ACTIVE') RETURNING id
                """,(org.springframework.jdbc.core.ResultSetExtractor<UUID>)rs->rs.next()?rs.getObject(1,UUID.class):prior,
                tenantId,reviewId,authorEmail,authorEmail,reviewId,"Conversation completed by "+authorEmail+".",reviewId,tenantId);
            jdbc.update("UPDATE collaboration_reviews SET status='CLOSED',closed_at=now(),updated_at=now() WHERE id=? AND tenant_id=? AND status='ACTIVE'",reviewId,tenantId);
        }else jdbc.update("UPDATE collaboration_reviews SET updated_at=now() WHERE id=? AND tenant_id=? AND status='ACTIVE'",reviewId,tenantId);
        return new PostedMessage(reviewId,messageId);
    }

    @Transactional
    public void reopen(UUID tenantId,UUID reviewId,String authorEmail){
        setContext(tenantId,authorEmail);
        int changed=jdbc.update("UPDATE collaboration_reviews SET status='ACTIVE',closed_at=NULL,updated_at=now() WHERE id=? AND tenant_id=? AND status='CLOSED'",reviewId,tenantId);
        if(changed==0)throw new IllegalArgumentException("This conversation is already active or no longer available.");
        jdbc.update("""
            INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body)
            SELECT ?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?) LIMIT 1),
              CASE WHEN EXISTS (SELECT 1 FROM collaboration_messages team WHERE team.review_id=? AND team.message_type='TEAM_CHAT') THEN 'TEAM_CHAT' ELSE 'PRIVATE_NOTE' END,?
            """,tenantId,reviewId,authorEmail,authorEmail,reviewId,"Conversation reopened by "+authorEmail+".");
    }

    @Transactional(readOnly=true)
    public AttachmentData attachment(UUID tenantId,UUID attachmentId,String viewerEmail){
        setContext(tenantId,viewerEmail);
        var rows=jdbc.query("""
            SELECT attachment.file_name,attachment.content_type,attachment.content
            FROM collaboration_attachments attachment
            JOIN collaboration_messages message ON message.id=attachment.message_id AND message.tenant_id=attachment.tenant_id
            WHERE attachment.tenant_id=? AND attachment.id=? AND
              (message.message_type='TEAM_CHAT' OR lower(message.author_email)=lower(?))
            """,(rs,row)->new AttachmentData(rs.getString(1),rs.getString(2),rs.getBytes(3)),tenantId,attachmentId,viewerEmail);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional
    public PostedMessage assign(UUID tenantId,UUID reviewId,String actor,Set<UUID> requested,boolean replace){
        setContext(tenantId,actor);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users u WHERE lower(u.email)=lower(?) AND u.status='ACTIVE' AND (EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=u.id AND p.active) OR EXISTS(SELECT 1 FROM tenant_memberships m WHERE m.user_id=u.id AND m.tenant_id=? AND m.role IN ('OWNER','ADMIN','OPERATOR'))))",Boolean.class,actor,tenantId)))throw new org.springframework.security.access.AccessDeniedException("Your role cannot assign tasks.");
        var review=review(tenantId,reviewId,actor);if(review==null||review.messageCount()==review.privateNoteCount())throw new IllegalArgumentException("Choose a team conversation.");
        jdbc.queryForObject("SELECT id FROM collaboration_reviews WHERE tenant_id=? AND id=? FOR UPDATE",UUID.class,tenantId,reviewId);
        var team=members(tenantId);var valid=team.stream().map(Member::id).collect(java.util.stream.Collectors.toSet());
        if(!valid.containsAll(requested))throw new IllegalArgumentException("Choose active teammates from this account.");
        var before=new LinkedHashSet<>(jdbc.queryForList("SELECT user_id FROM collaboration_assignments WHERE tenant_id=? AND review_id=?",UUID.class,tenantId,reviewId));
        UUID legacy=jdbc.queryForObject("SELECT assigned_to FROM collaboration_reviews WHERE tenant_id=? AND id=?",UUID.class,tenantId,reviewId);if(legacy!=null)before.add(legacy);
        var after=new LinkedHashSet<>(replace?requested:before);after.addAll(requested);
        if(before.equals(after))return null;
        jdbc.update("DELETE FROM collaboration_assignments WHERE tenant_id=? AND review_id=?",tenantId,reviewId);
        for(UUID id:after)jdbc.update("INSERT INTO collaboration_assignments(tenant_id,review_id,user_id,assigned_by) VALUES (?,?,?,?)",tenantId,reviewId,id,actor);
        jdbc.update("UPDATE collaboration_reviews SET assigned_to=NULL,updated_at=now() WHERE tenant_id=? AND id=?",tenantId,reviewId);
        var actorName=currentMember(tenantId,actor).name();
        String oldNames=team.stream().filter(m->before.contains(m.id())).map(Member::name).collect(java.util.stream.Collectors.joining(", "));
        String newNames=team.stream().filter(m->after.contains(m.id())).map(Member::name).collect(java.util.stream.Collectors.joining(", "));
        UUID message=jdbc.queryForObject("INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body) VALUES (?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)),'TEAM_CHAT',?) RETURNING id",UUID.class,tenantId,reviewId,actor,actor,actorName+" changed assignment from "+(oldNames.isEmpty()?"unassigned":oldNames)+" to "+(newNames.isEmpty()?"unassigned":newNames)+".");
        var added=new LinkedHashSet<>(after);added.removeAll(before);queueNotifications(tenantId,reviewId,message,added,"ASSIGNED");
        return new PostedMessage(reviewId,message);
    }

    @Transactional public int queueMentions(UUID tenantId,UUID reviewId,UUID messageId,Set<UUID> memberIds){return queueNotifications(tenantId,reviewId,messageId,memberIds,"MENTION");}
    @Transactional public int queueNotifications(UUID tenantId,UUID reviewId,UUID messageId,Set<UUID> memberIds,String kind){if(messageId==null||memberIds.isEmpty())return 0;setContext(tenantId,null);int queued=0;for(UUID memberId:memberIds)queued+=jdbc.update("""
        INSERT INTO collaboration_mentions(tenant_id,review_id,message_id,mentioned_user_id,notification_kind)
        SELECT ?,?,?,member.id,? FROM app_users member
        WHERE (EXISTS(SELECT 1 FROM tenant_memberships membership WHERE membership.tenant_id=? AND membership.user_id=member.id) OR EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=member.id AND p.active)) AND member.id=? AND member.status='ACTIVE'
        ON CONFLICT (message_id,mentioned_user_id,notification_kind) DO NOTHING
        """,tenantId,reviewId,messageId,kind,tenantId,memberId);return queued;}

    @Transactional(readOnly=true)
    public Set<UUID> participantMemberIds(UUID tenantId,UUID reviewId,String excludingEmail){setContext(tenantId,excludingEmail);return new LinkedHashSet<>(jdbc.query("""
        SELECT DISTINCT member.id FROM tenant_memberships membership JOIN app_users member ON member.id=membership.user_id
        WHERE membership.tenant_id=? AND member.status='ACTIVE' AND lower(member.email)<>lower(?)
          AND EXISTS (SELECT 1 FROM collaboration_messages team WHERE team.review_id=? AND team.message_type='TEAM_CHAT') AND (
            lower(member.email)=(SELECT lower(requested_by) FROM collaboration_reviews WHERE tenant_id=? AND id=?) OR
            EXISTS (SELECT 1 FROM collaboration_messages message WHERE message.tenant_id=? AND message.review_id=? AND message.message_type='TEAM_CHAT' AND lower(message.author_email)=lower(member.email)) OR
            EXISTS (SELECT 1 FROM collaboration_mentions mention JOIN collaboration_messages message ON message.id=mention.message_id AND message.message_type='TEAM_CHAT' WHERE mention.tenant_id=? AND mention.review_id=? AND mention.mentioned_user_id=member.id))
        """,(rs,row)->rs.getObject(1,UUID.class),tenantId,excludingEmail,reviewId,tenantId,reviewId,tenantId,reviewId,tenantId,reviewId));}

    @Transactional
    public List<PendingMention> claimPendingMentions(UUID tenantId,int limit){setContext(tenantId,null);return jdbc.query("""
        WITH candidates AS (SELECT mention.id FROM collaboration_mentions mention WHERE mention.tenant_id=? AND mention.attempt_count<4 AND
          ((mention.status IN ('PENDING','FAILED') AND mention.next_attempt_at<=now()) OR (mention.status='SENDING' AND mention.updated_at<now()-interval '15 minutes'))
          ORDER BY mention.created_at FOR UPDATE SKIP LOCKED LIMIT ?),
        claimed AS (UPDATE collaboration_mentions mention SET status='SENDING',attempt_count=attempt_count+1,updated_at=now() FROM candidates WHERE mention.id=candidates.id RETURNING mention.*)
        SELECT claimed.id,claimed.review_id,recipient.display_name,recipient.email,tenant.display_name,review.subject_label,message.author_email,message.body,claimed.notification_kind
        FROM claimed JOIN app_users recipient ON recipient.id=claimed.mentioned_user_id JOIN tenants tenant ON tenant.id=claimed.tenant_id
        JOIN collaboration_reviews review ON review.id=claimed.review_id JOIN collaboration_messages message ON message.id=claimed.message_id
        """,(rs,row)->new PendingMention(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),tenantId,limit);}
    @Transactional public void mentionSent(UUID tenantId,UUID mentionId){setContext(tenantId,null);jdbc.update("UPDATE collaboration_mentions SET status='SENT',sent_at=now(),last_error=NULL,updated_at=now() WHERE tenant_id=? AND id=?",tenantId,mentionId);}
    @Transactional public void mentionFailed(UUID tenantId,UUID mentionId,String error,boolean retry){setContext(tenantId,null);jdbc.update("UPDATE collaboration_mentions SET status=?,last_error=?,next_attempt_at=now()+(interval '1 minute'*greatest(1,attempt_count*attempt_count)),updated_at=now() WHERE tenant_id=? AND id=?",retry?"FAILED":"SKIPPED",cleanError(error),tenantId,mentionId);}
    @Transactional(readOnly=true) public List<UUID> activeTenantIds(){return jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id LIMIT 1000",(rs,row)->rs.getObject(1,UUID.class));}

    private void saveAttachmentsInternal(UUID tenantId,UUID reviewId,UUID messageId,String authorEmail,List<AttachmentUpload> uploads){if(uploads==null)return;for(var upload:uploads)jdbc.update("""
        INSERT INTO collaboration_attachments(tenant_id,review_id,message_id,uploaded_by,uploaded_by_email,file_name,content_type,size_bytes,content)
        VALUES (?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?) LIMIT 1),?,?,?,?,?)
        """,tenantId,reviewId,messageId,authorEmail,authorEmail,upload.fileName(),upload.contentType(),upload.bytes().length,upload.bytes());}
    private static Review review(java.sql.ResultSet rs)throws java.sql.SQLException{return new Review(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),instant(rs,12),instant(rs,13),instant(rs,14),instant(rs,15),rs.getInt(16),rs.getInt(17),rs.getString(18),rs.getString(19),rs.getString(20),rs.getBoolean(21),rs.getObject(22,java.time.LocalDate.class),rs.getString(23),rs.getObject(24,UUID.class),rs.getInt(25));}
    private static String normalizeMessageType(String value){return "PRIVATE_NOTE".equalsIgnoreCase(value)?"PRIVATE_NOTE":"TEAM_CHAT";}
    private static Object[] join(Object first,List<?> rest){var values=new ArrayList<>();values.add(first);values.addAll(rest);return values.toArray();}
    private static Object[] append(List<?> values,Object last){var result=new ArrayList<Object>(values);result.add(last);return result.toArray();}
    private static String baseHandle(Member member){String source=member.name()==null||member.name().isBlank()?member.email().split("@",2)[0]:member.name();String handle=source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+",".").replaceAll("(^\\.|\\.$)","");return handle.isBlank()?"teammate":handle;}
    private static String cleanError(String value){if(value==null)return "Email delivery failed.";String clean=value.replaceAll("[\\r\\n]+"," ");return clean.substring(0,Math.min(500,clean.length()));}
    private void setContext(UUID tenantId,String viewerEmail){jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",String.class,tenantId.toString());jdbc.queryForObject("SELECT set_config('app.user_email', ?, true)",String.class,viewerEmail==null?"":viewerEmail);}
    private static Instant instant(java.sql.ResultSet rs,int column)throws java.sql.SQLException{var value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
}
