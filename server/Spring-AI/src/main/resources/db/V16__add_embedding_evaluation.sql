CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE embedding_eval_entity (
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('model', 'snapshot', 'dataset')),
    public_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    knowledge_id BIGINT,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, kind, public_id, revision),
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE,
    CHECK ((kind = 'model' AND knowledge_id IS NULL) OR (kind <> 'model' AND knowledge_id IS NOT NULL))
);
CREATE INDEX idx_embedding_eval_entity_scope ON embedding_eval_entity(tenant_id, knowledge_id, kind, created_at DESC);

CREATE FUNCTION prevent_embedding_eval_version_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'evaluation versions are immutable; append a new revision';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER embedding_eval_entity_immutable BEFORE UPDATE ON embedding_eval_entity
    FOR EACH ROW EXECUTE FUNCTION prevent_embedding_eval_version_update();

CREATE TABLE embedding_eval_run (
    public_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    knowledge_id BIGINT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX idx_embedding_eval_run_scope ON embedding_eval_run(tenant_id, knowledge_id, created_at DESC);

CREATE TABLE embedding_eval_build (
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    knowledge_id BIGINT NOT NULL,
    build_key VARCHAR(64) NOT NULL,
    snapshot_id UUID NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    expected_count INTEGER NOT NULL CHECK (expected_count > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('BUILDING','READY','FAILED')),
    model JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, build_key),
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE embedding_eval_vector (
    tenant_id BIGINT NOT NULL,
    build_key VARCHAR(64) NOT NULL,
    chunk_id UUID NOT NULL,
    file_id BIGINT NOT NULL,
    embedding vector NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, build_key, chunk_id),
    FOREIGN KEY (tenant_id, build_key) REFERENCES embedding_eval_build(tenant_id, build_key) ON DELETE CASCADE
);
