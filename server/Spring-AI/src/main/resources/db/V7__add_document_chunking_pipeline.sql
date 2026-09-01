ALTER TABLE file ADD CONSTRAINT uk_file_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE file_processing (
    file_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    pipeline_state SMALLINT NOT NULL DEFAULT 0 CHECK (pipeline_state BETWEEN 0 AND 7),
    failed_from_state SMALLINT CHECK (failed_from_state BETWEEN 0 AND 6),
    progress SMALLINT NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    source_hash VARCHAR(64),
    strategy_code VARCHAR(64),
    planner_version VARCHAR(64),
    policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    context_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    lock_version INTEGER NOT NULL DEFAULT 0,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id, tenant_id) REFERENCES file(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    content TEXT NOT NULL CHECK (length(btrim(content)) > 0),
    overlap_content TEXT,
    overlap_source_chunk_id BIGINT REFERENCES document_chunk(id) ON DELETE SET NULL,
    overlap_token_count INTEGER NOT NULL DEFAULT 0 CHECK (overlap_token_count >= 0),
    index_content TEXT,
    section_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_locator JSONB NOT NULL DEFAULT '{}'::jsonb,
    token_count INTEGER NOT NULL CHECK (token_count >= 0),
    content_hash VARCHAR(64) NOT NULL,
    boundary_reason JSONB NOT NULL DEFAULT '{}'::jsonb,
    status SMALLINT NOT NULL DEFAULT 0 CHECK (status BETWEEN 0 AND 2),
    is_modified BOOLEAN NOT NULL DEFAULT FALSE,
    last_error TEXT,
    lock_version INTEGER NOT NULL DEFAULT 0,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id, tenant_id) REFERENCES file(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (file_id, position)
);

CREATE INDEX idx_file_processing_scope_state
    ON file_processing(tenant_id, knowledge_id, pipeline_state);
CREATE INDEX idx_document_chunk_scope_status
    ON document_chunk(tenant_id, knowledge_id, file_id, status);

CREATE TRIGGER trigger_update_file_processing_timestamp BEFORE UPDATE ON file_processing
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
CREATE TRIGGER trigger_update_document_chunk_timestamp BEFORE UPDATE ON document_chunk
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
