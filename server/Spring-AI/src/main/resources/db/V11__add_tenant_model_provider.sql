ALTER TABLE app_user
    ADD CONSTRAINT uk_app_user_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE tenant_model_provider (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    catalog_provider_id BIGINT REFERENCES model_provider_catalog(id) ON DELETE RESTRICT,
    custom_name VARCHAR(64),
    custom_icon VARCHAR(128),
    base_url VARCHAR(512) NOT NULL CHECK (btrim(base_url) <> ''),
    protocol_type VARCHAR(32) NOT NULL
        CHECK (protocol_type IN ('OPENAI_COMPATIBLE')),
    auth_type VARCHAR(16) NOT NULL
        CHECK (auth_type IN ('API_KEY', 'NONE')),
    selectable_models JSONB NOT NULL DEFAULT '[]'::JSONB
        CHECK (validate_model_suggestions(selectable_models)),
    api_key_ciphertext TEXT,
    api_key_nonce VARCHAR(64),
    api_key_version VARCHAR(32),
    api_key_last_four VARCHAR(4),
    last_verified_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_model_provider_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_tenant_model_provider_creator
        FOREIGN KEY (created_by, tenant_id) REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_model_provider_identity CHECK (
        (catalog_provider_id IS NOT NULL AND custom_name IS NULL AND custom_icon IS NULL)
        OR
        (catalog_provider_id IS NULL
            AND custom_name IS NOT NULL AND btrim(custom_name) <> ''
            AND custom_icon IS NOT NULL AND btrim(custom_icon) <> '')
    ),
    CONSTRAINT ck_tenant_model_provider_secret CHECK (
        (auth_type = 'API_KEY'
            AND api_key_ciphertext IS NOT NULL AND btrim(api_key_ciphertext) <> ''
            AND api_key_nonce IS NOT NULL AND btrim(api_key_nonce) <> ''
            AND api_key_version IS NOT NULL AND btrim(api_key_version) <> ''
            AND api_key_last_four IS NOT NULL AND btrim(api_key_last_four) <> '')
        OR
        (auth_type = 'NONE'
            AND api_key_ciphertext IS NULL
            AND api_key_nonce IS NULL
            AND api_key_version IS NULL
            AND api_key_last_four IS NULL)
    )
);

CREATE UNIQUE INDEX uk_tenant_model_provider_catalog
    ON tenant_model_provider(tenant_id, catalog_provider_id)
    WHERE catalog_provider_id IS NOT NULL;

CREATE UNIQUE INDEX uk_tenant_model_provider_custom_name
    ON tenant_model_provider(tenant_id, lower(custom_name))
    WHERE catalog_provider_id IS NULL;

CREATE INDEX idx_tenant_model_provider_tenant
    ON tenant_model_provider(tenant_id, update_time DESC);

CREATE TRIGGER trigger_update_tenant_model_provider_timestamp
    BEFORE UPDATE ON tenant_model_provider
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
