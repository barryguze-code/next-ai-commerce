-- A1 foundation only: no backfill, jobs, inventory changes or operational consumers.
ALTER TABLE marketplace_connections ADD CONSTRAINT marketplace_connections_variation_scope_key
    UNIQUE (tenant_id,id,marketplace_identifier);
ALTER TABLE amazon_source_documents ADD CONSTRAINT amazon_source_documents_variation_scope_key
    UNIQUE (tenant_id,marketplace_connection_id,id);

CREATE TABLE amazon_variation_classifications (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    marketplace_id VARCHAR(30) NOT NULL CHECK (marketplace_id<>''),
    seller_sku VARCHAR(240) COLLATE "C" NOT NULL CHECK (seller_sku<>''),
    observed_asin VARCHAR(20),
    observed_role VARCHAR(16) CHECK (observed_role IN ('PARENT','CHILD','STANDALONE')),
    own_complete BOOLEAN NOT NULL DEFAULT false,
    amazon_status JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(amazon_status)='array'),
    attributes JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(attributes)='object'),
    source_document_id UUID,
    evidence_revision BIGINT NOT NULL DEFAULT 0 CHECK (evidence_revision>=0),
    observed_at TIMESTAMPTZ,
    amazon_updated_at TIMESTAMPTZ,
    parser_version INTEGER NOT NULL DEFAULT 1 CHECK (parser_version>0),
    verified_role VARCHAR(16) CHECK (verified_role IN ('PARENT','CHILD','STANDALONE')),
    verified_revision BIGINT,
    verified_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    reciprocal_complete BOOLEAN NOT NULL DEFAULT false,
    members_complete BOOLEAN NOT NULL DEFAULT false,
    classification VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (classification IN ('PARENT','CHILD','STANDALONE','UNKNOWN','CONFLICT')),
    reason_code VARCHAR(80) NOT NULL DEFAULT 'MISSING_EVIDENCE',
    diagnostics JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(diagnostics)='object'),
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count>=0),
    fetch_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED',
    last_http_status INTEGER CHECK (last_http_status BETWEEN 100 AND 599),
    last_error_code VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,marketplace_connection_id,marketplace_id,seller_sku),
    FOREIGN KEY (tenant_id,marketplace_connection_id,marketplace_id)
        REFERENCES marketplace_connections(tenant_id,id,marketplace_identifier) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,marketplace_connection_id,source_document_id)
        REFERENCES amazon_source_documents(tenant_id,marketplace_connection_id,id),
    CHECK ((source_document_id IS NULL AND observed_at IS NULL AND evidence_revision=0)
        OR (source_document_id IS NOT NULL AND observed_at IS NOT NULL AND evidence_revision>0)),
    CHECK (num_nonnulls(verified_role,verified_revision,verified_at,valid_until) IN (0,4)),
    CHECK ((verified_role IS NULL AND verified_revision IS NULL AND verified_at IS NULL AND valid_until IS NULL)
        OR (verified_role IS NOT NULL AND verified_revision>0 AND verified_revision<=evidence_revision
            AND verified_at IS NOT NULL AND valid_until IS NOT NULL AND observed_at IS NOT NULL
            AND verified_at>=observed_at AND valid_until>verified_at)),
    CHECK (classification NOT IN ('PARENT','CHILD','STANDALONE') OR
        (verified_role IS NOT NULL AND classification=verified_role AND own_complete
         AND reciprocal_complete AND members_complete))
);

CREATE TABLE amazon_variation_claims (
    tenant_id UUID NOT NULL,
    marketplace_connection_id UUID NOT NULL,
    marketplace_id VARCHAR(30) NOT NULL,
    subject_sku VARCHAR(240) COLLATE "C" NOT NULL,
    related_sku VARCHAR(240) COLLATE "C" NOT NULL,
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('PARENT_OF','CHILD_OF')),
    subject_revision BIGINT NOT NULL CHECK (subject_revision>0),
    source_document_id UUID NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    variation_theme JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(variation_theme)='object'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,marketplace_connection_id,marketplace_id,subject_sku,related_sku,direction),
    CHECK (subject_sku<>related_sku),
    FOREIGN KEY (tenant_id,marketplace_connection_id,marketplace_id,subject_sku)
        REFERENCES amazon_variation_classifications ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,marketplace_connection_id,marketplace_id,related_sku)
        REFERENCES amazon_variation_classifications ON DELETE CASCADE,
    FOREIGN KEY (tenant_id,marketplace_connection_id,source_document_id)
        REFERENCES amazon_source_documents(tenant_id,marketplace_connection_id,id)
);
CREATE INDEX amazon_variation_due_idx ON amazon_variation_classifications(tenant_id,next_attempt_at);
CREATE INDEX amazon_variation_role_idx ON amazon_variation_classifications
    (tenant_id,marketplace_connection_id,marketplace_id,classification,valid_until);
CREATE INDEX amazon_variation_source_idx ON amazon_variation_classifications
    (tenant_id,marketplace_connection_id,source_document_id,evidence_revision);
CREATE INDEX amazon_variation_reverse_idx ON amazon_variation_claims
    (tenant_id,marketplace_connection_id,marketplace_id,related_sku);
CREATE INDEX amazon_variation_claim_source_idx ON amazon_variation_claims
    (tenant_id,marketplace_connection_id,source_document_id,subject_revision);
ALTER TABLE amazon_variation_classifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE amazon_variation_classifications FORCE ROW LEVEL SECURITY;
ALTER TABLE amazon_variation_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE amazon_variation_claims FORCE ROW LEVEL SECURITY;
CREATE POLICY amazon_variation_classifications_isolation ON amazon_variation_classifications
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY amazon_variation_claims_isolation ON amazon_variation_claims
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
