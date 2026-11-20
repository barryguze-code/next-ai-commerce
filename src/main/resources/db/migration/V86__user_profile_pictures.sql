CREATE TABLE user_profile_pictures (
    user_id uuid PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
    content_type text NOT NULL CHECK (content_type IN ('image/png','image/jpeg')),
    image_bytes bytea NOT NULL CHECK (octet_length(image_bytes) <= 5000000),
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Preserve existing portraits where visible to the migration role; newest wins.
INSERT INTO user_profile_pictures(user_id,content_type,image_bytes,updated_by,updated_at)
SELECT DISTINCT ON (entity_id) entity_id,content_type,image_bytes,updated_by,updated_at
FROM record_picture_overrides
WHERE entity_type='USER' AND entity_id IN (SELECT id FROM app_users)
ORDER BY entity_id,updated_at DESC;
