-- ============================================================
-- V1: Auth + Multi-tenant
-- Apply order: existing tables first get tenant_id, then new tables.
-- ============================================================

-- Base application tables are created here so a fresh database can apply this
-- migration without a separate manual `rag.sql` bootstrap step.
CREATE TABLE IF NOT EXISTS agent (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    prologue VARCHAR(512),
    role_description VARCHAR(512),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS file (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(32) NOT NULL,
    size BIGINT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0 CHECK (status IN (0, 1)),
    type VARCHAR(32) NOT NULL,
    path VARCHAR(256) NOT NULL,
    embedding_status SMALLINT NOT NULL DEFAULT 0 CHECK (embedding_status IN (0, 1, 2)),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_knowledge (
    agent_id BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    knowledge_id BIGINT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, knowledge_id)
);

CREATE TABLE IF NOT EXISTS knowledge_file (
    knowledge_id BIGINT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL REFERENCES file(id) ON DELETE CASCADE,
    PRIMARY KEY (knowledge_id, file_id)
);

CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_update_agent_timestamp ON agent;
CREATE TRIGGER trigger_update_agent_timestamp BEFORE UPDATE ON agent
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
DROP TRIGGER IF EXISTS trigger_update_knowledge_timestamp ON knowledge;
CREATE TRIGGER trigger_update_knowledge_timestamp BEFORE UPDATE ON knowledge
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
DROP TRIGGER IF EXISTS trigger_update_file_timestamp ON file;
CREATE TRIGGER trigger_update_file_timestamp BEFORE UPDATE ON file
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE INDEX IF NOT EXISTS idx_agent_name ON agent(name);
CREATE INDEX IF NOT EXISTS idx_knowledge_name ON knowledge(name);
CREATE INDEX IF NOT EXISTS idx_file_name ON file(file_name);
CREATE INDEX IF NOT EXISTS idx_file_status ON file(status);
CREATE INDEX IF NOT EXISTS idx_file_type ON file(type);

-- 0. Drop existing business data (per spec: 全新建)
TRUNCATE agent, knowledge, file, agent_knowledge, knowledge_file RESTART IDENTITY CASCADE;

-- 1. Add tenant_id to existing tables
ALTER TABLE agent           ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge       ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE file            ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_knowledge ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_file  ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_agent_tenant            ON agent(tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_tenant        ON knowledge(tenant_id);
CREATE INDEX IF NOT EXISTS idx_file_tenant             ON file(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_tenant  ON agent_knowledge(tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_file_tenant   ON knowledge_file(tenant_id);

-- 2. tenant
CREATE TABLE IF NOT EXISTS tenant (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DROP TRIGGER IF EXISTS trigger_update_tenant_timestamp ON tenant;
CREATE TRIGGER trigger_update_tenant_timestamp BEFORE UPDATE ON tenant
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

-- 3. app_user
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    role          VARCHAR(32)  NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP WITH TIME ZONE,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, username)
);
CREATE INDEX IF NOT EXISTS idx_app_user_tenant ON app_user(tenant_id);
DROP TRIGGER IF EXISTS trigger_update_app_user_timestamp ON app_user;
CREATE TRIGGER trigger_update_app_user_timestamp BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

-- 4. platform_admin
CREATE TABLE IF NOT EXISTS platform_admin (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. invite
CREATE TABLE IF NOT EXISTS invite (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    intended_role VARCHAR(32)  NOT NULL,
    accepted_by   BIGINT       REFERENCES app_user(id),
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_invite_tenant ON invite(tenant_id);

-- 6. refresh_token
CREATE TABLE IF NOT EXISTS refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id   UUID         NOT NULL,
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    parent_id   BIGINT       REFERENCES refresh_token(id),
    rotated_at  TIMESTAMP WITH TIME ZONE,
    revoked_at  TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    user_agent  VARCHAR(256),
    ip          VARCHAR(64),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_refresh_user   ON refresh_token(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_token(family_id);

-- 7. Seed: default tenant (id=1); platform admin su 密码由 AuthBootstrapRunner 在运行时生成
INSERT INTO tenant (id, code, name) VALUES (1, 'default', 'Default Tenant')
    ON CONFLICT (id) DO NOTHING;
-- 调整 sequence,使后续 INSERT 从 2 起(避免与种子 id=1 冲突)
SELECT setval(pg_get_serial_sequence('tenant', 'id'), GREATEST((SELECT MAX(id) FROM tenant), 1));
