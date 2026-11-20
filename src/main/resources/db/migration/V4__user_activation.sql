CREATE TABLE user_activation_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'USED', 'REVOKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at),
    CHECK ((status = 'USED') = (used_at IS NOT NULL))
);

CREATE INDEX user_activation_tokens_user_idx
    ON user_activation_tokens (user_id, created_at DESC);
