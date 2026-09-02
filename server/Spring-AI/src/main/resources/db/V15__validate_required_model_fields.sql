-- Missing JSON fields produce SQL NULL, so ordinary <> checks are insufficient.
-- Replace the shared validator without changing the already deployed V10 checksum.
CREATE OR REPLACE FUNCTION validate_model_suggestions(models JSONB)
RETURNS BOOLEAN AS $$
DECLARE
    item JSONB;
    model_id TEXT;
    seen_model_ids TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF jsonb_typeof(models) IS DISTINCT FROM 'array' THEN
        RETURN FALSE;
    END IF;

    FOR item IN SELECT value FROM jsonb_array_elements(models)
    LOOP
        IF jsonb_typeof(item) IS DISTINCT FROM 'object'
           OR jsonb_typeof(item -> 'modelId') IS DISTINCT FROM 'string'
           OR btrim(item ->> 'modelId') = ''
           OR jsonb_typeof(item -> 'displayName') IS DISTINCT FROM 'string'
           OR btrim(item ->> 'displayName') = ''
           OR jsonb_typeof(item -> 'contextWindow') IS DISTINCT FROM 'number'
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
