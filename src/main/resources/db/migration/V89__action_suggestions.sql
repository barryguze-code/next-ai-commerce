-- Central product feedback: submission is authenticated, review is platform-admin only.
CREATE TABLE action_suggestions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 submitted_by text NOT NULL,
 page_path varchar(250) NOT NULL,
 menu_context varchar(250) NOT NULL,
 title varchar(160) NOT NULL,
 explanation varchar(2000) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','UNDER_REVIEW','PLANNED','DEPLOYED','DECLINED')),
 review_note varchar(2000) NOT NULL DEFAULT '',
 reviewed_by text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX action_suggestions_review ON action_suggestions(status,created_at DESC);
