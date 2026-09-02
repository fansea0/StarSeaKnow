-- Public model-provider presets. Tenant-specific connections are introduced separately.
CREATE OR REPLACE FUNCTION validate_model_suggestions(models JSONB)
RETURNS BOOLEAN AS $$
DECLARE
    item JSONB;
    model_id TEXT;
    seen_model_ids TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF models IS NULL OR jsonb_typeof(models) <> 'array' THEN
        RETURN FALSE;
    END IF;

    FOR item IN SELECT value FROM jsonb_array_elements(models)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR jsonb_typeof(item -> 'modelId') <> 'string'
           OR btrim(item ->> 'modelId') = ''
           OR jsonb_typeof(item -> 'displayName') <> 'string'
           OR btrim(item ->> 'displayName') = ''
           OR jsonb_typeof(item -> 'contextWindow') <> 'number'
           OR (item ->> 'contextWindow')::BIGINT <= 0 THEN
            RETURN FALSE;
        END IF;

        model_id := item ->> 'modelId';
        IF model_id = ANY(seen_model_ids) THEN
            RETURN FALSE;
        END IF;
        seen_model_ids := array_append(seen_model_ids, model_id);
    END LOOP;

    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN
    RETURN FALSE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE TABLE model_provider_catalog (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    icon VARCHAR(128) NOT NULL,
    default_base_url VARCHAR(512) NOT NULL,
    protocol_type VARCHAR(32) NOT NULL
        CHECK (protocol_type IN ('OPENAI_COMPATIBLE')),
    auth_type VARCHAR(16) NOT NULL
        CHECK (auth_type IN ('API_KEY', 'NONE')),
    suggested_models JSONB NOT NULL DEFAULT '[]'::JSONB
        CHECK (validate_model_suggestions(suggested_models)),
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER trigger_update_model_provider_catalog_timestamp
    BEFORE UPDATE ON model_provider_catalog
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

INSERT INTO model_provider_catalog
    (code, name, icon, default_base_url, protocol_type, auth_type, suggested_models)
VALUES
    ('OPENAI', 'OpenAI', 'provider/openai', 'https://api.openai.com/v1',
     'OPENAI_COMPATIBLE', 'API_KEY',
     '[{"modelId":"gpt-4o-mini","displayName":"GPT-4o Mini","contextWindow":128000},
       {"modelId":"gpt-4o","displayName":"GPT-4o","contextWindow":128000}]'::JSONB),
    ('ANTHROPIC', 'Anthropic', 'provider/anthropic', 'https://api.anthropic.com/v1',
     'OPENAI_COMPATIBLE', 'API_KEY', '[]'::JSONB),
    ('DEEPSEEK', 'DeepSeek', 'provider/deepseek', 'https://api.deepseek.com',
     'OPENAI_COMPATIBLE', 'API_KEY', '[]'::JSONB),
    ('QWEN', '通义千问', 'provider/qwen', 'https://dashscope.aliyuncs.com/compatible-mode/v1',
     'OPENAI_COMPATIBLE', 'API_KEY', '[]'::JSONB),
    ('ZHIPU', '智谱 AI', 'provider/zhipu', 'https://open.bigmodel.cn/api/paas/v4',
     'OPENAI_COMPATIBLE', 'API_KEY', '[]'::JSONB),
    ('OLLAMA', 'Ollama', 'provider/ollama', 'http://localhost:11434/v1',
     'OPENAI_COMPATIBLE', 'NONE', '[]'::JSONB)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    icon = EXCLUDED.icon,
    default_base_url = EXCLUDED.default_base_url,
    protocol_type = EXCLUDED.protocol_type,
    auth_type = EXCLUDED.auth_type,
    suggested_models = EXCLUDED.suggested_models,
    update_time = CURRENT_TIMESTAMP;
