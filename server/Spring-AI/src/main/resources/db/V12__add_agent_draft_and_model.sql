ALTER TABLE agent
    ADD CONSTRAINT uk_agent_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE agent_model (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    tenant_model_provider_id BIGINT NOT NULL,
    model_id VARCHAR(256) NOT NULL,
    temperature NUMERIC(4,3) NOT NULL DEFAULT 0.4
        CHECK (temperature BETWEEN 0 AND 2),
    top_p NUMERIC(4,3) NOT NULL DEFAULT 0.9
        CHECK (top_p BETWEEN 0 AND 1),
    max_tokens INTEGER NOT NULL DEFAULT 2048
        CHECK (max_tokens BETWEEN 1 AND 200000),
    timeout_seconds INTEGER NOT NULL DEFAULT 60
        CHECK (timeout_seconds BETWEEN 1 AND 600),
    deleted_at TIMESTAMP WITH TIME ZONE,
    deleted_by BIGINT,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_model_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_agent_model_provider_tenant
        FOREIGN KEY (tenant_model_provider_id, tenant_id)
        REFERENCES tenant_model_provider(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_model_deleted_by_tenant
        FOREIGN KEY (deleted_by, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_model_deleted_actor
        CHECK ((deleted_at IS NULL AND deleted_by IS NULL)
            OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL))
);

CREATE INDEX idx_agent_model_tenant_provider
    ON agent_model(tenant_id, tenant_model_provider_id)
    WHERE deleted_at IS NULL;

CREATE TRIGGER trigger_update_agent_model_timestamp
    BEFORE UPDATE ON agent_model
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

ALTER TABLE agent
    ADD COLUMN system_prompt TEXT NOT NULL DEFAULT '',
    ADD COLUMN tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN agent_model_id BIGINT,
    ADD COLUMN retrieval_top_k INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN retrieval_score_threshold NUMERIC(5,4) NOT NULL DEFAULT 0.2,
    ADD COLUMN draft_revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN published_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN current_snapshot_id BIGINT,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_debugged_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_debugged_by BIGINT,
    ADD COLUMN last_edited_by BIGINT,
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN deleted_by BIGINT;

UPDATE agent
SET system_prompt = COALESCE(role_description, '')
WHERE system_prompt = '' AND role_description IS NOT NULL;

ALTER TABLE agent
    ADD CONSTRAINT ck_agent_tags_array CHECK (
        jsonb_typeof(tags) = 'array'
        AND NOT jsonb_path_exists(tags, '$[*] ? (@.type() != "string")')
    ),
    ADD CONSTRAINT ck_agent_variables_array CHECK (jsonb_typeof(variables) = 'array'),
    ADD CONSTRAINT ck_agent_retrieval_top_k CHECK (retrieval_top_k BETWEEN 1 AND 20),
    ADD CONSTRAINT ck_agent_retrieval_score CHECK (retrieval_score_threshold BETWEEN 0 AND 1),
    ADD CONSTRAINT ck_agent_revisions CHECK (
        draft_revision >= 1
        AND published_revision >= 0
        AND published_revision <= draft_revision
        AND lock_version >= 0
    ),
    ADD CONSTRAINT ck_agent_deleted_actor CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    ),
    ADD CONSTRAINT uk_agent_model_owner UNIQUE (agent_model_id),
    ADD CONSTRAINT fk_agent_model_tenant FOREIGN KEY (agent_model_id, tenant_id)
        REFERENCES agent_model(id, tenant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_last_debugged_by_tenant FOREIGN KEY (last_debugged_by, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_last_edited_by_tenant FOREIGN KEY (last_edited_by, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_agent_deleted_by_tenant FOREIGN KEY (deleted_by, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT;

CREATE INDEX idx_agent_tenant_active_updated
    ON agent(tenant_id, update_time DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_agent_tags ON agent USING GIN(tags);

ALTER TABLE agent_knowledge
    DROP CONSTRAINT IF EXISTS agent_knowledge_agent_id_fkey,
    DROP CONSTRAINT IF EXISTS agent_knowledge_knowledge_id_fkey,
    ADD CONSTRAINT fk_agent_knowledge_agent_tenant
        FOREIGN KEY (agent_id, tenant_id)
        REFERENCES agent(id, tenant_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_agent_knowledge_knowledge_tenant
        FOREIGN KEY (knowledge_id, tenant_id)
        REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE;
