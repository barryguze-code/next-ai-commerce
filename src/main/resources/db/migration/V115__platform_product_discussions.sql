-- Platform administrators share canonical-product discussions. Existing tenant tables/policies are unchanged.
CREATE TABLE platform_product_discussions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 global_product_id uuid NOT NULL REFERENCES global_catalog_products(id),
 title varchar(240) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','CLOSED')),
 created_by uuid NOT NULL REFERENCES app_users(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 closed_by uuid REFERENCES app_users(id), closed_at timestamptz,
 CHECK((status='ACTIVE' AND closed_by IS NULL AND closed_at IS NULL) OR (status='CLOSED' AND closed_by IS NOT NULL AND closed_at IS NOT NULL))
);
CREATE INDEX platform_product_discussions_product ON platform_product_discussions(global_product_id,updated_at DESC,id);
CREATE TABLE platform_product_discussion_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 discussion_id uuid NOT NULL REFERENCES platform_product_discussions(id),
 author_id uuid NOT NULL REFERENCES app_users(id),
 body text NOT NULL CHECK(char_length(body) BETWEEN 1 AND 4000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX platform_product_discussion_messages_thread ON platform_product_discussion_messages(discussion_id,created_at,id);
CREATE TABLE platform_product_discussion_attachments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 message_id uuid NOT NULL REFERENCES platform_product_discussion_messages(id),
 file_name varchar(255) NOT NULL,content_type varchar(160) NOT NULL,
 file_bytes bytea NOT NULL CHECK(octet_length(file_bytes) BETWEEN 1 AND 5000000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX platform_product_discussion_attachments_message ON platform_product_discussion_attachments(message_id,id);
CREATE FUNCTION platform_product_discussion_authorized() RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog,public AS $$
 SELECT EXISTS(SELECT 1 FROM public.app_users u JOIN public.platform_administrators a ON a.user_id=u.id AND a.active
   WHERE u.status='ACTIVE' AND lower(u.email)=lower(nullif(current_setting('app.user_email',true),'')))
$$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['platform_product_discussions','platform_product_discussion_messages','platform_product_discussion_attachments'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY platform_admin_read ON %I FOR SELECT USING(platform_product_discussion_authorized())',tab);
  EXECUTE format('CREATE POLICY platform_admin_append ON %I FOR INSERT WITH CHECK(platform_product_discussion_authorized())',tab);
 END LOOP;
END $$;
CREATE POLICY platform_admin_update ON platform_product_discussions FOR UPDATE
 USING(platform_product_discussion_authorized()) WITH CHECK(platform_product_discussion_authorized());
-- No delete policy, no mutation of existing private conversations, no outbound notification jobs.
