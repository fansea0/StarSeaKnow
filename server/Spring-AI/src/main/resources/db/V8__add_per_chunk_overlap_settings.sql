ALTER TABLE document_chunk
    ADD COLUMN overlap_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN overlap_token_limit INTEGER NOT NULL DEFAULT 40;

WITH legacy_source AS (
    SELECT fp.file_id,
           fp.tenant_id,
           fp.knowledge_id,
           lower(COALESCE(fp.context_policy ->> 'overlapEnabled', 'false')) = 'true'
               AS overlap_requested,
           COALESCE(fp.context_policy ->> 'overlapTokens', '') AS overlap_tokens_text
    FROM file_processing fp
),
legacy AS (
    SELECT file_id,
           tenant_id,
           knowledge_id,
           overlap_requested,
           CASE
               WHEN overlap_tokens_text ~ '^-?[0-9]+$' THEN
                   CASE
                       WHEN overlap_tokens_text::NUMERIC BETWEEN 1 AND 512
                           THEN overlap_tokens_text::INTEGER
                       ELSE NULL
                   END
               ELSE NULL
           END AS overlap_token_limit
    FROM legacy_source
)
UPDATE document_chunk dc
SET overlap_enabled = (legacy.overlap_requested
                           AND legacy.overlap_token_limit IS NOT NULL)
                          OR NULLIF(BTRIM(dc.overlap_content), '') IS NOT NULL,
    overlap_token_limit = LEAST(512, GREATEST(
            COALESCE(legacy.overlap_token_limit, 40),
            CASE
                WHEN NULLIF(BTRIM(dc.overlap_content), '') IS NOT NULL
                    THEN GREATEST(dc.overlap_token_count, 1)
                ELSE 1
            END))
FROM legacy
WHERE legacy.file_id = dc.file_id
  AND legacy.tenant_id = dc.tenant_id
  AND legacy.knowledge_id = dc.knowledge_id;

ALTER TABLE document_chunk
    ADD CONSTRAINT chk_document_chunk_overlap_token_limit
        CHECK (overlap_token_limit BETWEEN 1 AND 512);

COMMENT ON COLUMN document_chunk.overlap_enabled IS
    'Per-chunk switch for deriving read-only overlap context';
COMMENT ON COLUMN document_chunk.overlap_token_limit IS
    'User-editable per-chunk overlap token limit, retained for source-driven recalculation';
