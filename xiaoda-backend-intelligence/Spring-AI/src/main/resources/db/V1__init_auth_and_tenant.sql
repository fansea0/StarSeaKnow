-- ============================================================
-- V1: Auth + Multi-tenant
-- Apply order: existing tables first get tenant_id, then new tables.
-- ============================================================

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
