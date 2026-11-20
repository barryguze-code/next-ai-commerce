ALTER TABLE collaboration_mentions
    ADD COLUMN notification_kind VARCHAR(20) NOT NULL DEFAULT 'MENTION'
        CHECK (notification_kind IN ('MENTION','COMPLETED'));

ALTER TABLE collaboration_mentions
    DROP CONSTRAINT collaboration_mentions_message_id_mentioned_user_id_key;

ALTER TABLE collaboration_mentions
    ADD CONSTRAINT collaboration_mentions_message_user_kind_key
        UNIQUE (message_id, mentioned_user_id, notification_kind);
