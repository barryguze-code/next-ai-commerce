package com.nextaicommerce.platform.collaboration;

import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DiscussionTaskConversion {
    record Link(UUID parent, UUID task) {}
    private final JdbcTemplate jdbc;
    private final CollaborationRepository discussions;
    private final ParentTenantRepository parents;
    private final CollaborationTaskQueueRepository queue;

    DiscussionTaskConversion(JdbcTemplate jdbc, CollaborationRepository discussions, ParentTenantRepository parents,
                             CollaborationTaskQueueRepository queue) {
        this.jdbc = jdbc;
        this.discussions = discussions;
        this.parents = parents;
        this.queue = queue;
    }

    @Transactional
    public Link convert(UUID account, UUID source, String email) {
        var review = discussions.review(account, source, email);
        if (review == null || review.messageCount() == review.privateNoteCount())
            throw new IllegalArgumentException("Choose a shared discussion.");
        var parent = parents.available(email).stream().map(p -> p.id())
                .filter(id -> parents.accounts(id, email).contains(account)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Set up this account’s parent and membership first."));
        UUID actor = queue.actor(parent, email);
        jdbc.queryForObject("SELECT id FROM collaboration_reviews WHERE tenant_id=? AND id=? FOR UPDATE",
                UUID.class, account, source);
        var existing = jdbc.queryForList("SELECT id FROM collaboration_tasks WHERE parent_id=? AND account_id=? AND source_review_id=? AND created_by=?",
                UUID.class, parent, account, source, actor);
        if (!existing.isEmpty()) return new Link(parent, existing.getFirst());
        var task = queue.create(parent, account, review.storeId(), "Discussion follow-up", "", email, Set.of(actor)).reviewId();
        jdbc.update("UPDATE collaboration_tasks SET source_review_id=?,subject_type=?,subject_key=? WHERE parent_id=? AND id=?",
                source, review.subjectType(), review.subjectKey(), parent, task);
        // Generate fresh IDs so separate authorized follow-ups can retain their own immutable copies.
        jdbc.update("""
                WITH shared AS MATERIALIZED (
                  SELECT id,gen_random_uuid() AS copy,sender_id,body,created_at FROM collaboration_messages
                  WHERE tenant_id=? AND review_id=? AND message_type='TEAM_CHAT'
                ), copied AS (
                  INSERT INTO collaboration_task_messages(id,parent_id,task_id,author_id,body,created_at)
                  SELECT copy,?,?,sender_id,body,created_at FROM shared RETURNING id
                )
                INSERT INTO collaboration_task_files(parent_id,task_id,message_id,file_name,content_type,content,created_at)
                SELECT ?,?,c.id,a.file_name,a.content_type,a.content,a.created_at
                FROM collaboration_attachments a JOIN shared m ON m.id=a.message_id JOIN copied c ON c.id=m.copy
                WHERE a.tenant_id=? AND a.review_id=?
                """, account, source, parent, task, parent, task, account, source);
        return new Link(parent, task);
    }
}
