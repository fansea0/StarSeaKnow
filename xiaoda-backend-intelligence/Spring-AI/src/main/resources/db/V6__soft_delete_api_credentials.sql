ALTER TABLE api_credential
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_api_credential_tenant_visible
    ON api_credential(tenant_id, created_at DESC)
    WHERE deleted_at IS NULL;
