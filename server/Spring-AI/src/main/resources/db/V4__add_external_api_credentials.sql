ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS public_id UUID;
UPDATE knowledge SET public_id = gen_random_uuid() WHERE public_id IS NULL;
ALTER TABLE knowledge ALTER COLUMN public_id SET DEFAULT gen_random_uuid();
ALTER TABLE knowledge ALTER COLUMN public_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_public_id ON knowledge(public_id);
ALTER TABLE knowledge ADD CONSTRAINT uk_knowledge_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE file ADD COLUMN IF NOT EXISTS public_id UUID;
UPDATE file SET public_id = gen_random_uuid() WHERE public_id IS NULL;
ALTER TABLE file ALTER COLUMN public_id SET DEFAULT gen_random_uuid();
ALTER TABLE file ALTER COLUMN public_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_public_id ON file(public_id);

CREATE TABLE api_credential (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    credential_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    key_id VARCHAR(32) NOT NULL UNIQUE,
    secret_digest CHAR(64) NOT NULL,
    pepper_version VARCHAR(16) NOT NULL,
    environment VARCHAR(16) NOT NULL CHECK (environment IN ('test','live')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('active','disabled','revoked')),
    expires_at TIMESTAMP WITH TIME ZONE,
    allowed_ip_cidrs JSONB NOT NULL DEFAULT '[]'::jsonb,
    requests_per_minute INTEGER NOT NULL CHECK (requests_per_minute BETWEEN 1 AND 100000),
    burst_capacity INTEGER NOT NULL CHECK (burst_capacity BETWEEN 1 AND 100000),
    max_concurrency INTEGER NOT NULL CHECK (max_concurrency BETWEEN 1 AND 10000),
    authorization_version BIGINT NOT NULL DEFAULT 1,
    display_prefix VARCHAR(32) NOT NULL,
    display_last_four CHAR(4) NOT NULL,
    rotated_from_id BIGINT REFERENCES api_credential(id),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    last_used_ip VARCHAR(64),
    UNIQUE (id, tenant_id, credential_type)
);
CREATE INDEX idx_api_credential_tenant_type_status
    ON api_credential(tenant_id, credential_type, status);

CREATE TABLE api_credential_knowledge (
    tenant_id BIGINT NOT NULL,
    credential_id BIGINT NOT NULL,
    credential_type VARCHAR(32) NOT NULL DEFAULT 'RAG_RETRIEVAL'
        CHECK (credential_type = 'RAG_RETRIEVAL'),
    knowledge_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (credential_id, knowledge_id),
    FOREIGN KEY (credential_id, tenant_id, credential_type)
        REFERENCES api_credential(id, tenant_id, credential_type) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_id, tenant_id)
        REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE
);
